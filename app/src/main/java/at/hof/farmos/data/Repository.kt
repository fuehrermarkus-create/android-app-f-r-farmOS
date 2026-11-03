package at.hof.farmos.data

import at.hof.farmos.data.db.AnimalEntity
import at.hof.farmos.data.db.AnimalTypeEntity
import at.hof.farmos.data.db.CategoryEntity
import at.hof.farmos.data.db.FarmDao
import at.hof.farmos.data.db.LocationEntity
import at.hof.farmos.data.db.LogEntity
import at.hof.farmos.data.db.PendingOp
import at.hof.farmos.data.db.urlList
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.math.BigDecimal
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** Log-Arten, die die App anbietet. */
enum class LogKind(val apiType: String, val label: String, val movement: Boolean = false) {
    OBSERVATION("observation", "Beobachtung"),
    MEDICAL("medical", "Behandlung"),
    INPUT("input", "Fütterung/Gabe"),
    ACTIVITY("activity", "Aktivität"),
    MOVEMENT("activity", "Umstallen/Weide", movement = true),
    HARVEST("harvest", "Ernte");

    companion object {
        fun labelFor(apiType: String, movement: Boolean): String =
            if (movement) MOVEMENT.label
            else entries.firstOrNull { it.apiType == apiType && !it.movement }?.label ?: apiType
    }
}

/** Referenz auf ein Asset mit JSON:API-Typ, z. B. "asset--animal". */
data class AssetRef(val id: String, val type: String, val name: String) {
    /** "animal" aus "asset--animal" */
    val bundle: String get() = type.substringAfter("--")
}

/** Alles, was zu einem neuen Eintrag gehört. */
data class LogDraft(
    val kind: LogKind,
    val name: String,
    val timestamp: Long,
    val notes: String?,
    val assets: List<AssetRef>,
    val locations: List<AssetRef>,
    val quantityValue: String?,
    val quantityUnit: String?,
    val photos: List<String> = emptyList(),   // lokale Dateipfade
    val latitude: Double? = null,
    val longitude: Double? = null,
    val categoryIds: List<String> = emptyList(),
    /** Fotos zusätzlich direkt beim Tier/bei der Kultur ablegen. */
    val attachPhotosToAssets: Boolean = false,
)

data class SyncResult(val pushed: Int, val failed: Int, val error: String?, val offline: Boolean)

class Repository(
    private val api: FarmApi,
    private val dao: FarmDao,
    private val prefs: Prefs,
    private val photoDir: File,
) {
    val animals = dao.animals()
    val locations = dao.locations()
    val animalTypes = dao.animalTypes()
    val categories = dao.categories()
    val recentLogs = dao.recentLogs()
    val pendingOps = dao.pendingOps()
    fun animal(id: String) = dao.animal(id)
    fun location(id: String) = dao.location(id)
    fun logsFor(id: String) = dao.logsFor(id)

    // ================================================================ Synchronisation

    /** Erst lokale Änderungen hochladen, dann aktuelle Daten vom Server holen. */
    suspend fun sync(): SyncResult {
        val push = pushPending()
        if (push.offline || push.error != null && push.pushed == 0 && dao.openOpCount() > 0) {
            return push
        }
        return try {
            pull()
            prefs.lastSync = System.currentTimeMillis()
            cleanupPhotos()
            if (hiddenWarnings.isNotEmpty()) {
                push.copy(error = "farmOS blendet Einträge aus (fehlende Rechte): " +
                    hiddenWarnings.joinToString(", ") + ". Rolle des Benutzers / Scope prüfen.")
            } else push
        } catch (e: Exception) {
            push.copy(error = describe(e), offline = FarmApi.isNetworkError(e))
        }
    }

    suspend fun pushPending(): SyncResult {
        var pushed = 0
        var failed = 0
        var lastSeq = -1L
        while (true) {
            // Immer frisch lesen: Uploads ersetzen Platzhalter in späteren Einträgen.
            val op = dao.openOps().firstOrNull { it.seq > lastSeq } ?: break
            lastSeq = op.seq

            if (op.method != "UPLOAD" && op.body.contains(PLACEHOLDER_PREFIX)) {
                dao.updateOp(op.copy(failed = true, lastError = "Zugehöriges Foto konnte nicht hochgeladen werden"))
                failed++
                continue
            }
            try {
                if (op.method == "UPLOAD") {
                    val file = File(op.body)
                    if (!file.exists()) {
                        dao.updateOp(op.copy(failed = true, lastError = "Fotodatei nicht mehr vorhanden"))
                        failed++
                        continue
                    }
                    val resp = api.upload(op.path, file)
                    val fileId = resp?.obj("data")?.str("id")
                    if (op.entityKind == "upload") {
                        if (fileId == null) throw ApiException(500, "Server lieferte keine Datei-ID")
                        dao.replaceInOps(placeholder(op.entityId), fileId)
                    }
                } else {
                    api.send(op.method, op.path, op.body)
                }
                markDone(op)
                pushed++
            } catch (e: ApiException) {
                if (e.code == 409 && op.method != "UPLOAD") {
                    // Existiert schon (z. B. früherer Versuch kam doch an).
                    markDone(op); pushed++
                } else if (e.isPermanent) {
                    dao.updateOp(op.copy(attempts = op.attempts + 1, lastError = e.message, failed = true))
                    failed++
                } else {
                    dao.updateOp(op.copy(attempts = op.attempts + 1, lastError = e.message))
                    return SyncResult(pushed, failed, e.message, offline = false)
                }
            } catch (e: NotLoggedInException) {
                return SyncResult(pushed, failed, "Bitte neu anmelden", offline = false)
            } catch (e: Exception) {
                return SyncResult(pushed, failed, describe(e), offline = FarmApi.isNetworkError(e))
            }
        }
        return SyncResult(pushed, failed, if (failed > 0) "$failed Änderung(en) abgelehnt" else null, false)
    }

    private suspend fun markDone(op: PendingOp) {
        dao.deleteOp(op.seq)
        when (op.entityKind) {
            "animal" -> dao.markAnimalSynced(op.entityId)
            "animal_type" -> dao.markTypeSynced(op.entityId)
            "log" -> dao.markLogSynced(op.entityId)
        }
    }

    /** Verwirft eine abgelehnte Änderung samt lokalem Eintrag. */
    suspend fun discardOp(op: PendingOp) {
        dao.deleteOp(op.seq)
        when (op.entityKind) {
            "animal" -> dao.deletePendingAnimal(op.entityId)
            "animal_type" -> dao.deletePendingType(op.entityId)
            "log" -> dao.deletePendingLog(op.entityId)
            "upload" -> {
                // Verweis auf das Foto aus abhängigen Einträgen entfernen, damit diese trotzdem ankommen.
                val ref = Regex(",?\\{\"type\":\"file--file\",\"id\":\"" +
                    Regex.escape(placeholder(op.entityId)) + "\"\\}")
                for (other in dao.allOpsNow()) {
                    if (other.body.contains(placeholder(op.entityId))) {
                        val newBody = other.body.replace(ref, "").replace("[,", "[")
                        dao.updateOp(other.copy(body = newBody))
                    }
                }
            }
        }
    }

    suspend fun retryFailed() = dao.retryFailedOps()

    private fun absoluteUrl(url: String): String {
        // Immer über die in der App eingestellte Adresse laden (z. B. Tailscale), auch wenn
        // der Server eine andere Host-Adresse liefert.
        val path = runCatching {
            val u = URI(url)
            (u.rawPath ?: "") + (u.rawQuery?.let { "?$it" } ?: "")
        }.getOrDefault(url)
        return prefs.serverUrl + (if (path.startsWith("/")) path else "/$path")
    }

    private fun imageUrls(o: JsonObject, included: Map<String, JsonObject>): String =
        o.relationIds("image")
            .mapNotNull { included[it]?.attributes?.obj("uri")?.str("url") }
            .joinToString("\n") { absoluteUrl(it) }

    /** Bereiche, in denen farmOS Einträge wegen fehlender Rechte ausgeblendet hat. */
    private val hiddenWarnings = mutableListOf<String>()

    /**
     * Lädt eine Liste. 404 heißt: Modul in farmOS nicht installiert -> leer.
     * Andere Fehler werden mit dem Bereich beschriftet, damit man sieht, wo es hakt.
     */
    private suspend fun fetch(label: String, path: String, maxPages: Int = 40): ApiList =
        try {
            val l = api.getAll(path, maxPages)
            if (l.omitted) hiddenWarnings += label
            l
        } catch (e: ApiException) {
            if (e.code == 404) ApiList(emptyList(), emptyMap())
            else throw ApiException(e.code, "$label: ${e.message}")
        }

    private suspend fun pull() {
        hiddenWarnings.clear()

        // Tierarten
        val types = fetch("Tierarten", "/api/taxonomy_term/animal_type?page[limit]=50").data.map {
            AnimalTypeEntity(it.str("id")!!, it.attributes.str("name") ?: "?")
        }
        dao.replaceTypes(types)
        val typeNames = types.associate { it.id to it.name }

        // Log-Kategorien
        val cats = fetch("Kategorien", "/api/taxonomy_term/log_category?page[limit]=50").data.map {
            CategoryEntity(it.str("id")!!, it.attributes.str("name") ?: "?")
        }
        dao.replaceCategories(cats)

        // Flächen und Gebäude
        val landList = fetch("Flächen", "/api/asset/land?filter[status]=active&page[limit]=50&include=image")
        val lands = landList.data.map { it.toLocation("land", "land_type", landList.included) }
        val structList = fetch("Gebäude", "/api/asset/structure?filter[status]=active&page[limit]=50&include=image")
        val structures = structList.data.map { it.toLocation("structure", "structure_type", structList.included) }
        val locNames = (lands + structures).associate { it.id to it.name }

        // Kulturen (Pflanzungen) mit Kulturart
        val plantTypes = fetch("Kulturarten", "/api/taxonomy_term/plant_type?page[limit]=50").data
            .associate { it.str("id")!! to (it.attributes.str("name") ?: "?") }
        val plantList = fetch("Kulturen", "/api/asset/plant?filter[status]=active&page[limit]=50&include=image")
        val plants = plantList.data.map { o ->
            val a = o.attributes
            LocationEntity(
                id = o.str("id")!!,
                kind = "plant",
                name = a.str("name") ?: "?",
                subtype = o.relationIds("plant_type").mapNotNull { plantTypes[it] }
                    .joinToString(", ").ifBlank { null },
                notes = a.textField("notes"),
                areaInfo = null,
                locationNames = o.relationIds("location").mapNotNull { locNames[it] }
                    .joinToString(", ").ifBlank { null },
                status = a.str("status") ?: "active",
                imageUrls = imageUrls(o, plantList.included),
            )
        }
        dao.replaceLocations(lands + structures + plants)

        // Tiere
        val animalList = fetch("Tiere", "/api/asset/animal?filter[status]=active&page[limit]=50&include=image")
        val animals = animalList.data.map { o ->
            val a = o.attributes
            val typeId = o.relationIds("animal_type").firstOrNull()
            val tags = (a["id_tag"] as? JsonArray)?.objects()?.mapNotNull { it.str("id") }
            AnimalEntity(
                id = o.str("id")!!,
                name = a.str("name") ?: "?",
                sex = a.str("sex"),
                birthdate = parseTime(a.str("birthdate")),
                idTag = tags?.joinToString(", ")?.ifBlank { null },
                animalTypeId = typeId,
                animalTypeName = typeId?.let { typeNames[it] },
                notes = a.textField("notes"),
                locationNames = o.relationIds("location").mapNotNull { locNames[it] }
                    .joinToString(", ").ifBlank { null },
                status = a.str("status") ?: "active",
                imageUrls = imageUrls(o, animalList.included),
            )
        }
        dao.replaceAnimals(animals)

        // Logs (jeweils die neuesten 50 je Art)
        val logs = mutableListOf<LogEntity>()
        for (kind in LogKind.entries.filter { !it.movement }) {
            val type = kind.apiType
            val page = fetch("Logs ${kind.label}", "/api/log/$type?sort=-timestamp&page[limit]=50&include=image", 1)
            page.data.forEach { o ->
                val a = o.attributes
                logs += LogEntity(
                    id = o.str("id")!!,
                    type = type,
                    name = a.str("name") ?: "",
                    timestamp = parseTime(a.str("timestamp")) ?: 0L,
                    notes = a.textField("notes"),
                    status = a.str("status") ?: "done",
                    isMovement = a.str("is_movement") == "true",
                    assetIds = o.relationIds("asset").joinToString(","),
                    locationIds = o.relationIds("location").joinToString(","),
                    imageUrls = imageUrls(o, page.included),
                    hasGeo = !a.textField("geometry").isNullOrBlank(),
                )
            }
        }
        dao.replaceLogs(logs)
    }

    // ================================================================ Verbindung testen

    data class DiagItem(val title: String, val ok: Boolean, val detail: String)

    /** Prüft Schritt für Schritt Server, Anmeldung, Benutzer und jeden Datenbereich. */
    suspend fun diagnose(): List<DiagItem> {
        val out = mutableListOf<DiagItem>()

        // 1) Server erreichbar und antwortet farmOS?
        try {
            val (code, type, body) = api.rawGet("/api")
            val isJson = type.contains("json")
            out += DiagItem(
                "Server ${prefs.serverUrl}",
                isJson && code < 500 && code != 404,
                "HTTP $code, ${type.ifBlank { "kein Inhaltstyp" }}" +
                    if (!isJson) " – Antwort: " + body.trim().take(120).replace(Regex("\\s+"), " ") else "",
            )
        } catch (e: Exception) {
            out += DiagItem("Server ${prefs.serverUrl}", false, describe(e) + " – im richtigen WLAN / Tailscale an?")
            return out
        }

        // 2) Zugangstoken
        try {
            api.validToken()
            out += DiagItem("Anmeldung", true, "Token gültig")
        } catch (e: Exception) {
            out += DiagItem("Anmeldung", false, describe(e) + " – bitte abmelden und neu anmelden")
            return out
        }

        // 3) Zu welchem Benutzer gehört das Token?
        try {
            val root = api.send("GET", "/api", null)
            val meId = root?.obj("meta")?.obj("links")?.obj("me")?.obj("meta")?.str("id")
            if (meId == null) {
                out += DiagItem("Benutzer", false, "Token gehört zu keinem Benutzer – Scope/Grant des Consumers prüfen")
            } else {
                val u = api.send("GET", "/api/user/user/$meId", null)
                val nameText = u?.obj("data")?.attributes?.let { it.str("display_name") ?: it.str("name") } ?: meId
                out += DiagItem("Benutzer", true, nameText)
            }
        } catch (e: Exception) {
            out += DiagItem("Benutzer", false, describe(e))
        }

        // 4) Jeder Datenbereich einzeln
        val checks = listOf(
            "Tierarten" to "/api/taxonomy_term/animal_type",
            "Tiere" to "/api/asset/animal",
            "Flächen" to "/api/asset/land",
            "Gebäude" to "/api/asset/structure",
            "Kulturen" to "/api/asset/plant",
            "Kategorien" to "/api/taxonomy_term/log_category",
        ) + LogKind.entries.filter { !it.movement }.map { "Logs ${it.label}" to "/api/log/${it.apiType}" }
        for ((label, path) in checks) {
            out += try {
                val l = api.getFirstPage("$path?page[limit]=50")
                DiagItem(
                    label,
                    !l.omitted,
                    "${l.data.size}${if (l.hasMore) "+" else ""} Einträge" +
                        if (l.omitted) " – einige AUSGEBLENDET: fehlende Rechte (Rolle/Scope)" else "",
                )
            } catch (e: ApiException) {
                DiagItem(label, false, (e.message ?: "Fehler") + if (e.code == 404) " – Modul in farmOS nicht installiert" else "")
            } catch (e: Exception) {
                DiagItem(label, false, describe(e))
            }
        }
        return out
    }

    private fun JsonObject.toLocation(
        kind: String,
        subtypeField: String,
        included: Map<String, JsonObject>,
    ): LocationEntity {
        val a = attributes
        val area = a.str("area")?.toDoubleOrNull()?.let { String.format(Locale.GERMAN, "%.2f ha", it / 10000.0) }
        return LocationEntity(
            id = str("id")!!,
            kind = kind,
            name = a.str("name") ?: "?",
            subtype = a.str(subtypeField),
            notes = a.textField("notes"),
            areaInfo = area,
            locationNames = null,
            status = a.str("status") ?: "active",
            imageUrls = imageUrls(this, included),
        )
    }

    /** Löscht lokale Fotos, die übertragen sind und nicht mehr gebraucht werden. */
    private suspend fun cleanupPhotos() {
        val files = photoDir.listFiles() ?: return
        val referenced = mutableSetOf<String>()
        dao.allOpsNow().filter { it.method == "UPLOAD" }.forEach { referenced += it.body }
        dao.pendingLogsNow().forEach { l -> l.imageUrls.urlList().forEach { referenced += it.removePrefix("file://") } }
        val cutoff = System.currentTimeMillis() - 24 * 3600 * 1000L
        files.filter { it.absolutePath !in referenced && it.lastModified() < cutoff }.forEach { it.delete() }
    }

    // ================================================================ Anlegen (offline-fähig)

    suspend fun createAnimalType(name: String): String {
        val id = UUID.randomUUID().toString()
        val body = buildJsonObject {
            putJsonObject("data") {
                put("type", "taxonomy_term--animal_type")
                put("id", id)
                putJsonObject("attributes") { put("name", name) }
            }
        }
        dao.upsertTypes(listOf(AnimalTypeEntity(id, name, pending = true)))
        dao.enqueue(
            PendingOp(
                method = "POST", path = "/api/taxonomy_term/animal_type", body = body.toString(),
                description = "Tierart „$name“", entityKind = "animal_type", entityId = id,
            )
        )
        return id
    }

    suspend fun createAnimal(
        name: String,
        sex: String?,
        birthdate: LocalDate?,
        idTag: String?,
        typeId: String,
        typeName: String,
        notes: String?,
    ) {
        val id = UUID.randomUUID().toString()
        val birthTs = birthdate?.atStartOfDay(ZoneId.systemDefault())?.toEpochSecond()
        val body = buildJsonObject {
            putJsonObject("data") {
                put("type", "asset--animal")
                put("id", id)
                putJsonObject("attributes") {
                    put("name", name)
                    put("status", "active")
                    if (sex != null) put("sex", sex)
                    if (birthTs != null) put("birthdate", isoTime(birthTs))
                    if (!idTag.isNullOrBlank()) {
                        putJsonArray("id_tag") {
                            add(buildJsonObject {
                                put("id", idTag.trim())
                                put("type", "ear_tag")
                            })
                        }
                    }
                    if (!notes.isNullOrBlank()) putJsonObject("notes") {
                        put("value", notes); put("format", "default")
                    }
                }
                putJsonObject("relationships") {
                    putJsonObject("animal_type") {
                        putJsonObject("data") {
                            put("type", "taxonomy_term--animal_type")
                            put("id", typeId)
                        }
                    }
                }
            }
        }
        dao.upsertAnimals(
            listOf(
                AnimalEntity(
                    id = id, name = name, sex = sex, birthdate = birthTs, idTag = idTag?.ifBlank { null },
                    animalTypeId = typeId, animalTypeName = typeName, notes = notes?.ifBlank { null },
                    locationNames = null, status = "active", pending = true,
                )
            )
        )
        dao.enqueue(
            PendingOp(
                method = "POST", path = "/api/asset/animal", body = body.toString(),
                description = "Tier „$name“", entityKind = "animal", entityId = id,
            )
        )
    }

    suspend fun createLog(d: LogDraft) {
        val id = UUID.randomUUID().toString()
        val kind = d.kind

        // 1) Fotos hochladen (die Datei-ID kennt erst der Server -> Platzhalter im Log)
        val photoKeys = d.photos.map { path ->
            val key = UUID.randomUUID().toString()
            dao.enqueue(
                PendingOp(
                    method = "UPLOAD", path = "/api/log/${kind.apiType}/image", body = path,
                    description = "Foto zu „${d.name}“", entityKind = "upload", entityId = key,
                )
            )
            key
        }

        // 2) Menge
        var quantityId: String? = null
        val qty = d.quantityValue?.replace(',', '.')?.trim()?.toBigDecimalOrNull()
        if (qty != null) {
            quantityId = UUID.randomUUID().toString()
            val q = qty.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }
            val denominator = BigDecimal.TEN.pow(q.scale()).toBigInteger()
            val qBody = buildJsonObject {
                putJsonObject("data") {
                    put("type", "quantity--standard")
                    put("id", quantityId)
                    putJsonObject("attributes") {
                        putJsonObject("value") {
                            put("numerator", JsonPrimitive(q.unscaledValue()))
                            put("denominator", JsonPrimitive(denominator))
                            put("decimal", q.toPlainString())
                        }
                        if (!d.quantityUnit.isNullOrBlank()) put("label", d.quantityUnit.trim())
                    }
                }
            }
            dao.enqueue(
                PendingOp(
                    method = "POST", path = "/api/quantity/standard", body = qBody.toString(),
                    description = "Menge zu „${d.name}“", entityKind = "quantity", entityId = quantityId,
                )
            )
        }

        // 3) Der Log selbst
        val body = buildJsonObject {
            putJsonObject("data") {
                put("type", "log--${kind.apiType}")
                put("id", id)
                putJsonObject("attributes") {
                    put("name", d.name)
                    put("timestamp", isoTime(d.timestamp))
                    put("status", "done")
                    if (kind.movement) put("is_movement", true)
                    if (!d.notes.isNullOrBlank()) putJsonObject("notes") {
                        put("value", d.notes); put("format", "default")
                    }
                    if (d.latitude != null && d.longitude != null) putJsonObject("geometry") {
                        put("value", wktPoint(d.latitude, d.longitude))
                    }
                }
                putJsonObject("relationships") {
                    if (d.assets.isNotEmpty()) putJsonObject("asset") { put("data", refs(d.assets)) }
                    if (d.locations.isNotEmpty()) putJsonObject("location") { put("data", refs(d.locations)) }
                    if (quantityId != null) putJsonObject("quantity") {
                        putJsonArray("data") {
                            add(buildJsonObject { put("type", "quantity--standard"); put("id", quantityId) })
                        }
                    }
                    if (photoKeys.isNotEmpty()) putJsonObject("image") {
                        putJsonArray("data") {
                            photoKeys.forEach { k ->
                                add(buildJsonObject { put("type", "file--file"); put("id", placeholder(k)) })
                            }
                        }
                    }
                    if (d.categoryIds.isNotEmpty()) putJsonObject("category") {
                        putJsonArray("data") {
                            d.categoryIds.forEach { c ->
                                add(buildJsonObject { put("type", "taxonomy_term--log_category"); put("id", c) })
                            }
                        }
                    }
                }
            }
        }
        dao.upsertLogs(
            listOf(
                LogEntity(
                    id = id, type = kind.apiType, name = d.name, timestamp = d.timestamp,
                    notes = d.notes?.ifBlank { null }, status = "done", isMovement = kind.movement,
                    assetIds = d.assets.joinToString(",") { it.id },
                    locationIds = d.locations.joinToString(",") { it.id },
                    imageUrls = d.photos.joinToString("\n") { "file://$it" },
                    hasGeo = d.latitude != null,
                    pending = true,
                )
            )
        )
        dao.enqueue(
            PendingOp(
                method = "POST", path = "/api/log/${kind.apiType}", body = body.toString(),
                description = "${kind.label} „${d.name}“", entityKind = "log", entityId = id,
            )
        )

        // 4) Optional: Fotos zusätzlich direkt am Tier / an der Kultur ablegen
        if (d.attachPhotosToAssets) {
            for (asset in d.assets) for (path in d.photos) {
                dao.enqueue(
                    PendingOp(
                        method = "UPLOAD", path = "/api/asset/${asset.bundle}/${asset.id}/image", body = path,
                        description = "Foto bei „${asset.name}“", entityKind = "attach",
                        entityId = UUID.randomUUID().toString(),
                    )
                )
            }
        }
    }

    private fun refs(items: List<AssetRef>) = buildJsonArray {
        items.forEach { r ->
            add(buildJsonObject { put("type", r.type); put("id", r.id) })
        }
    }

    suspend fun logout() {
        dao.wipeAll()
        prefs.logout()
    }

    companion object {
        private const val PLACEHOLDER_PREFIX = "@@FILE:"
        private fun placeholder(key: String) = "$PLACEHOLDER_PREFIX$key@@"

        private val isoFormat = DateTimeFormatter.ISO_OFFSET_DATE_TIME

        fun wktPoint(lat: Double, lon: Double): String =
            String.format(Locale.US, "POINT (%.7f %.7f)", lon, lat)

        fun isoTime(epochSeconds: Long): String =
            OffsetDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), ZoneId.systemDefault())
                .withNano(0).format(isoFormat)

        /** farmOS liefert Zeitstempel als ISO-8601-Text (ältere Versionen als Zahl). */
        fun parseTime(v: String?): Long? {
            if (v.isNullOrBlank()) return null
            v.toLongOrNull()?.let { return it }
            return runCatching { OffsetDateTime.parse(v).toEpochSecond() }.getOrNull()
        }

        fun describe(e: Throwable): String = when (e) {
            is java.net.UnknownHostException -> "Server nicht gefunden – Adresse/Netz prüfen"
            is java.net.ConnectException -> "Server nicht erreichbar"
            is java.net.SocketTimeoutException -> "Zeitüberschreitung"
            is java.io.IOException -> "Netzwerkfehler: ${e.message}"
            else -> e.message ?: e.javaClass.simpleName
        }
    }
}
