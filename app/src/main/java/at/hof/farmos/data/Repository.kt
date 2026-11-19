package at.hof.farmos.data

import at.hof.farmos.data.db.AnimalEntity
import at.hof.farmos.data.db.AnimalTypeEntity
import at.hof.farmos.data.db.TermEntity
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
    INPUT("input", "Gabe/Fütterung"),
    ACTIVITY("activity", "Aktivität"),
    MOVEMENT("activity", "Umstallen/Weide", movement = true),
    HARVEST("harvest", "Ernte"),
    SEEDING("seeding", "Aussaat");

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

/** Arbeiten auf dem Feld – jede bekommt ein eigenes, passendes Formular. */
enum class FieldWork(val label: String, val logType: String, val category: String) {
    SEEDING("Aussaat", "seeding", "Aussaat"),
    SPRAYING("Pflanzenschutz", "input", "Pflanzenschutz"),
    FERTILIZING("Düngung", "input", "Düngung"),
    HARVEST("Ernte", "harvest", "Ernte"),
}

/** Menge mit Einheit, z. B. 2,5 + "l/ha". */
data class Amount(val value: String, val unit: String)

data class FieldWorkDraft(
    val work: FieldWork,
    val field: AssetRef?,                 // Fläche (land) oder Gebäude
    val timestamp: Long,
    val notes: String?,
    val cropName: String? = null,         // Aussaat: Kultur, z. B. "Winterweizen"
    val variety: String? = null,          // Aussaat: Sorte
    val plants: List<AssetRef> = emptyList(), // vorhandene Kulturen auf dem Feld
    val material: String? = null,         // Mittel bzw. Dünger
    val amount: Amount? = null,
    val water: Amount? = null,            // Pflanzenschutz: Wasseraufwand
    val treatedHa: String? = null,        // behandelte Fläche in ha
    val reason: String? = null,           // Pflanzenschutz: Grund / Schaderreger
    val photos: List<String> = emptyList(),
)

/** Einheiten mit passender farmOS-Messgröße. */
val UNIT_MEASURE = linkedMapOf(
    "kg/ha" to "rate", "l/ha" to "rate", "m³/ha" to "rate", "t/ha" to "rate", "dt/ha" to "rate",
    "Körner/m²" to "rate", "kg" to "weight", "dt" to "weight", "t" to "weight",
    "l" to "volume", "m³" to "volume", "Stk" to "count", "Ballen" to "count", "ha" to "area",
)

/** farmOS-Markierung für Blühflächen (muss in farmOS angelegt sein, siehe README). */
const val BLUEH_FLAG = "bluehflaeche"

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
    val categories = dao.terms("log_category")
    val plantTypes = dao.terms("plant_type")
    val materials = dao.terms("material_type")
    val flags = dao.terms("flag")
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
            "term" -> dao.markTermSynced(op.entityId)
        }
    }

    /** Verwirft eine abgelehnte Änderung samt lokalem Eintrag. */
    suspend fun discardOp(op: PendingOp) {
        dao.deleteOp(op.seq)
        when (op.entityKind) {
            "animal" -> dao.deletePendingAnimal(op.entityId)
            "animal_type" -> dao.deletePendingType(op.entityId)
            "log" -> dao.deletePendingLog(op.entityId)
            "term" -> dao.deletePendingTerm(op.entityId)
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

        // Begriffe: Kategorien, Kulturarten, Mittel/Dünger, Einheiten
        val termNames = mutableMapOf<String, String>()
        for ((vocab, label) in listOf(
            "log_category" to "Kategorien", "plant_type" to "Kulturarten",
            "material_type" to "Mittel/Dünger", "unit" to "Einheiten",
        )) {
            val list = fetch(label, "/api/taxonomy_term/$vocab?page[limit]=50").data.map {
                TermEntity(it.str("id")!!, vocab, it.attributes.str("name") ?: "?")
            }
            dao.replaceTerms(vocab, list)
            list.forEach { termNames[it.id] = it.name }
        }

        // Verfügbare Markierungen (Flags) für Flächen
        val flagList = runCatching { fetch("Markierungen", "/api/flag/flag").data }.getOrElse {
            if (FarmApi.isNetworkError(it)) throw it else emptyList()
        }.mapNotNull { o ->
            val a = o.attributes
            val machine = a.str("drupal_internal__id") ?: return@mapNotNull null
            val forAssets = (a.obj("entity_types")?.get("asset") as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content }
            if (forAssets != null && forAssets.none { it == "all" || it == "land" }) return@mapNotNull null
            TermEntity("flag:$machine", "flag", a.str("label") ?: machine)
        }
        dao.replaceTerms("flag", flagList)

        // Flächen und Gebäude
        val landList = fetch("Flächen", "/api/asset/land?filter[status]=active&page[limit]=50&include=image")
        val lands = landList.data.map { it.toLocation("land", "land_type", landList.included) }
        val structList = fetch("Gebäude", "/api/asset/structure?filter[status]=active&page[limit]=50&include=image")
        val structures = structList.data.map { it.toLocation("structure", "structure_type", structList.included) }
        val locNames = (lands + structures).associate { it.id to it.name }

        // Kulturen (Pflanzungen) mit Kulturart
        val plantTypes = termNames
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
                locationIds = o.relationIds("location").joinToString(","),
                lat = geoCenter(a)?.first,
                lon = geoCenter(a)?.second,
            )
        }
        // In der App gesetzte Positionen behalten
        val pins = dao.locationsNow().filter { it.pinLat != null && it.pinLon != null }.associateBy { it.id }
        dao.replaceLocations((lands + structures + plants).map { loc ->
            pins[loc.id]?.let { p -> loc.copy(pinLat = p.pinLat, pinLon = p.pinLon) } ?: loc
        })

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
                    categoryNames = o.relationIds("category").mapNotNull { termNames[it] }.joinToString(", "),
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
            "Kulturarten" to "/api/taxonomy_term/plant_type",
            "Mittel/Dünger" to "/api/taxonomy_term/material_type",
            "Einheiten" to "/api/taxonomy_term/unit",
            "Markierungen" to "/api/flag/flag",
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
        val notes = a.textField("notes")
        // Fläche: aus farmOS (m²) oder aus den Notizen ("… 1,6821 ha …", z. B. aus dem MFA-Import)
        val ha = a.str("area")?.toDoubleOrNull()?.let { it / 10000.0 }
            ?: notes?.let { Regex("([0-9]+[.,][0-9]+|[0-9]+)\\s*ha").find(it)?.groupValues?.get(1) }
                ?.replace(',', '.')?.toDoubleOrNull()
        val area = ha?.let { String.format(Locale.GERMAN, "%.2f ha", it) }
        val flagValues = (a["flag"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
        val center = geoCenter(a)
        return LocationEntity(
            id = str("id")!!,
            kind = kind,
            name = a.str("name") ?: "?",
            subtype = a.str(subtypeField),
            notes = notes,
            areaInfo = area,
            locationNames = null,
            status = a.str("status") ?: "active",
            imageUrls = imageUrls(this, included),
            flags = flagValues.joinToString(","),
            areaHa = ha,
            lat = center?.first,
            lon = center?.second,
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

    // ---------------------------------------------------------------- Bausteine

    /** Sucht einen Begriff (Kulturart, Mittel, Einheit, Kategorie) oder legt ihn an. */
    suspend fun ensureTerm(vocab: String, name: String): String {
        val clean = name.trim()
        dao.termsNow(vocab).firstOrNull { it.name.equals(clean, ignoreCase = true) }?.let { return it.id }
        val id = UUID.randomUUID().toString()
        val body = buildJsonObject {
            putJsonObject("data") {
                put("type", "taxonomy_term--$vocab")
                put("id", id)
                putJsonObject("attributes") { put("name", clean) }
            }
        }
        dao.upsertTerms(listOf(TermEntity(id, vocab, clean, pending = true)))
        dao.enqueue(
            PendingOp(
                method = "POST", path = "/api/taxonomy_term/$vocab", body = body.toString(),
                description = "Begriff „$clean“", entityKind = "term", entityId = id,
            )
        )
        return id
    }

    /**
     * Legt eine Menge an. Mit [unitTerm] = true wird die Einheit als farmOS-Einheit
     * (Taxonomie „unit“) gespeichert, sonst nur als Beschriftung.
     */
    private suspend fun enqueueQuantity(
        value: String?,
        unit: String?,
        label: String?,
        logName: String,
        unitTerm: Boolean,
    ): String? {
        val qty = value?.replace(',', '.')?.trim()?.toBigDecimalOrNull() ?: return null
        val q = qty.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }
        val denominator = BigDecimal.TEN.pow(q.scale()).toBigInteger()
        val unitId = if (unitTerm && !unit.isNullOrBlank()) ensureTerm("unit", unit) else null
        val measure = unit?.let { UNIT_MEASURE[it.trim()] }
        val text = when {
            unitTerm -> label
            !unit.isNullOrBlank() -> unit.trim()
            else -> label
        }
        val id = UUID.randomUUID().toString()
        val body = buildJsonObject {
            putJsonObject("data") {
                put("type", "quantity--standard")
                put("id", id)
                putJsonObject("attributes") {
                    putJsonObject("value") {
                        put("numerator", JsonPrimitive(q.unscaledValue()))
                        put("denominator", JsonPrimitive(denominator))
                        put("decimal", q.toPlainString())
                    }
                    if (!text.isNullOrBlank()) put("label", text)
                    if (unitTerm && measure != null) put("measure", measure)
                }
                if (unitId != null) putJsonObject("relationships") {
                    putJsonObject("units") {
                        putJsonObject("data") { put("type", "taxonomy_term--unit"); put("id", unitId) }
                    }
                }
            }
        }
        dao.enqueue(
            PendingOp(
                method = "POST", path = "/api/quantity/standard", body = body.toString(),
                description = "Menge zu „$logName“", entityKind = "quantity", entityId = id,
            )
        )
        return id
    }

    /** Alles, was ein Log braucht – gemeinsam für Schnell-Log und Feldarbeit. */
    private class LogSpec(
        val apiType: String,
        val typeLabel: String,
        val name: String,
        val timestamp: Long,
        val notes: String?,
        val movement: Boolean = false,
        val assets: List<AssetRef> = emptyList(),
        val locations: List<AssetRef> = emptyList(),
        val quantityIds: List<String> = emptyList(),
        val photos: List<String> = emptyList(),
        val latitude: Double? = null,
        val longitude: Double? = null,
        val categoryIds: List<String> = emptyList(),
        val categoryNames: List<String> = emptyList(),
        /** weitere Beziehungen, z. B. "material_type" -> [(taxonomy_term--material_type, id)] */
        val extraRefs: Map<String, List<Pair<String, String>>> = emptyMap(),
        val attachPhotosToAssets: Boolean = false,
    )

    private suspend fun enqueueLog(spec: LogSpec): String {
        val id = UUID.randomUUID().toString()

        // Fotos zuerst hochladen (die Datei-ID kennt erst der Server -> Platzhalter im Log)
        val photoKeys = spec.photos.map { path ->
            val key = UUID.randomUUID().toString()
            dao.enqueue(
                PendingOp(
                    method = "UPLOAD", path = "/api/log/${spec.apiType}/image", body = path,
                    description = "Foto zu „${spec.name}“", entityKind = "upload", entityId = key,
                )
            )
            key
        }

        fun refArray(list: List<Pair<String, String>>) = buildJsonArray {
            list.forEach { (type, rid) -> add(buildJsonObject { put("type", type); put("id", rid) }) }
        }

        val relations = mutableMapOf<String, List<Pair<String, String>>>()
        if (spec.assets.isNotEmpty()) relations["asset"] = spec.assets.map { it.type to it.id }
        if (spec.locations.isNotEmpty()) relations["location"] = spec.locations.map { it.type to it.id }
        if (spec.quantityIds.isNotEmpty()) relations["quantity"] = spec.quantityIds.map { "quantity--standard" to it }
        if (photoKeys.isNotEmpty()) relations["image"] = photoKeys.map { "file--file" to placeholder(it) }
        if (spec.categoryIds.isNotEmpty()) {
            relations["category"] = spec.categoryIds.map { "taxonomy_term--log_category" to it }
        }
        relations.putAll(spec.extraRefs)

        val body = buildJsonObject {
            putJsonObject("data") {
                put("type", "log--${spec.apiType}")
                put("id", id)
                putJsonObject("attributes") {
                    put("name", spec.name)
                    put("timestamp", isoTime(spec.timestamp))
                    put("status", "done")
                    if (spec.movement) put("is_movement", true)
                    if (!spec.notes.isNullOrBlank()) putJsonObject("notes") {
                        put("value", spec.notes); put("format", "default")
                    }
                    if (spec.latitude != null && spec.longitude != null) putJsonObject("geometry") {
                        put("value", wktPoint(spec.latitude, spec.longitude))
                    }
                }
                if (relations.isNotEmpty()) putJsonObject("relationships") {
                    relations.forEach { (rel, list) ->
                        putJsonObject(rel) { put("data", refArray(list)) }
                    }
                }
            }
        }
        dao.upsertLogs(
            listOf(
                LogEntity(
                    id = id, type = spec.apiType, name = spec.name, timestamp = spec.timestamp,
                    notes = spec.notes?.ifBlank { null }, status = "done", isMovement = spec.movement,
                    assetIds = spec.assets.joinToString(",") { it.id },
                    locationIds = spec.locations.joinToString(",") { it.id },
                    imageUrls = spec.photos.joinToString("\n") { "file://$it" },
                    hasGeo = spec.latitude != null,
                    pending = true,
                    categoryNames = spec.categoryNames.joinToString(", "),
                )
            )
        )
        dao.enqueue(
            PendingOp(
                method = "POST", path = "/api/log/${spec.apiType}", body = body.toString(),
                description = "${spec.typeLabel} „${spec.name}“", entityKind = "log", entityId = id,
            )
        )

        // Optional: Fotos zusätzlich direkt am Tier / an der Kultur ablegen
        if (spec.attachPhotosToAssets) {
            for (asset in spec.assets) for (path in spec.photos) {
                dao.enqueue(
                    PendingOp(
                        method = "UPLOAD", path = "/api/asset/${asset.bundle}/${asset.id}/image", body = path,
                        description = "Foto bei „${asset.name}“", entityKind = "attach",
                        entityId = UUID.randomUUID().toString(),
                    )
                )
            }
        }
        return id
    }

    // ---------------------------------------------------------------- Schnell-Log

    suspend fun createLog(d: LogDraft) {
        val quantityId = enqueueQuantity(d.quantityValue, d.quantityUnit, null, d.name, unitTerm = false)
        val catNames = dao.termsNow("log_category").filter { it.id in d.categoryIds }.map { it.name }
        enqueueLog(
            LogSpec(
                apiType = d.kind.apiType, typeLabel = d.kind.label, name = d.name, timestamp = d.timestamp,
                notes = d.notes, movement = d.kind.movement, assets = d.assets, locations = d.locations,
                quantityIds = listOfNotNull(quantityId), photos = d.photos,
                latitude = d.latitude, longitude = d.longitude,
                categoryIds = d.categoryIds, categoryNames = catNames,
                attachPhotosToAssets = d.attachPhotosToAssets,
            )
        )
    }

    // ---------------------------------------------------------------- Feldarbeit

    suspend fun createFieldWork(d: FieldWorkDraft) {
        val w = d.work
        val fieldName = d.field?.name
        val year = Instant.ofEpochSecond(d.timestamp).atZone(ZoneId.systemDefault()).year
        val categoryId = ensureTerm("log_category", w.category)
        val assets = d.plants.toMutableList()
        val extra = mutableMapOf<String, List<Pair<String, String>>>()

        // Aussaat: neue Kultur (farmOS „Plant“) anlegen, die dann auf dem Feld steht
        if (w == FieldWork.SEEDING) {
            val crop = d.cropName?.trim().orEmpty().ifBlank { "Kultur" }
            val typeId = ensureTerm("plant_type", crop)
            val plantId = UUID.randomUUID().toString()
            val plantName = listOfNotNull(crop, d.variety?.trim()?.ifBlank { null }).joinToString(" ") +
                (fieldName?.let { " – $it" } ?: "") + " $year"
            val plantBody = buildJsonObject {
                putJsonObject("data") {
                    put("type", "asset--plant")
                    put("id", plantId)
                    putJsonObject("attributes") {
                        put("name", plantName)
                        put("status", "active")
                        if (!d.variety.isNullOrBlank()) putJsonObject("notes") {
                            put("value", "Sorte: ${d.variety.trim()}"); put("format", "default")
                        }
                    }
                    putJsonObject("relationships") {
                        putJsonObject("plant_type") {
                            putJsonArray("data") {
                                add(buildJsonObject { put("type", "taxonomy_term--plant_type"); put("id", typeId) })
                            }
                        }
                    }
                }
            }
            dao.enqueue(
                PendingOp(
                    method = "POST", path = "/api/asset/plant", body = plantBody.toString(),
                    description = "Kultur „$plantName“", entityKind = "asset", entityId = plantId,
                )
            )
            dao.upsertLocation(
                LocationEntity(
                    id = plantId, kind = "plant", name = plantName, subtype = crop, notes = null,
                    areaInfo = null, locationNames = fieldName, status = "active",
                    locationIds = d.field?.id.orEmpty(),
                )
            )
            assets += AssetRef(plantId, "asset--plant", plantName)
        }

        // Mittel / Dünger
        if ((w == FieldWork.SPRAYING || w == FieldWork.FERTILIZING) && !d.material.isNullOrBlank()) {
            val matId = ensureTerm("material_type", d.material)
            extra["material_type"] = listOf("taxonomy_term--material_type" to matId)
        }

        val title = buildString {
            append(w.label)
            when (w) {
                FieldWork.SEEDING -> d.cropName?.let { append(" ").append(it.trim()) }
                FieldWork.SPRAYING, FieldWork.FERTILIZING -> d.material?.takeIf { it.isNotBlank() }
                    ?.let { append(": ").append(it.trim()) }
                FieldWork.HARVEST -> if (d.plants.isNotEmpty()) append(": ").append(d.plants.joinToString(", ") { it.name })
            }
            fieldName?.let { append(" – ").append(it) }
        }

        val amountLabel = when (w) {
            FieldWork.SEEDING -> "Saatmenge"
            FieldWork.SPRAYING -> "Aufwandmenge"
            FieldWork.FERTILIZING -> "Düngermenge"
            FieldWork.HARVEST -> "Erntemenge"
        }
        val quantities = listOfNotNull(
            d.amount?.let { enqueueQuantity(it.value, it.unit, amountLabel, title, unitTerm = true) },
            d.water?.let { enqueueQuantity(it.value, it.unit, "Wasser", title, unitTerm = true) },
            d.treatedHa?.let { enqueueQuantity(it, "ha", "Behandelte Fläche", title, unitTerm = true) },
        )

        val notes = listOfNotNull(
            d.reason?.trim()?.ifBlank { null }?.let { "Grund: $it" },
            d.notes?.trim()?.ifBlank { null },
        ).joinToString("\n").ifBlank { null }

        enqueueLog(
            LogSpec(
                apiType = w.logType, typeLabel = w.label, name = title, timestamp = d.timestamp,
                notes = notes,
                movement = w == FieldWork.SEEDING && d.field != null, // Kultur steht danach auf dem Feld
                assets = assets,
                locations = listOfNotNull(d.field),
                quantityIds = quantities,
                photos = d.photos,
                categoryIds = listOf(categoryId),
                categoryNames = listOf(w.category),
                extraRefs = extra,
            )
        )
    }

    // ---------------------------------------------------------------- Position einer Fläche

    /**
     * Speichert die Position in der App. Hat die Fläche in farmOS noch keine Geometrie,
     * wird der Punkt auch dort eingetragen (dann sieht man ihn auf der farmOS-Karte).
     */
    suspend fun setPosition(location: LocationEntity, lat: Double?, lon: Double?) {
        dao.setPin(location.id, lat, lon)
        if (lat != null && lon != null && location.lat == null && location.kind != "plant") {
            val body = buildJsonObject {
                putJsonObject("data") {
                    put("type", "asset--${location.kind}")
                    put("id", location.id)
                    putJsonObject("attributes") {
                        putJsonObject("intrinsic_geometry") { put("value", wktPoint(lat, lon)) }
                    }
                }
            }
            dao.enqueue(
                PendingOp(
                    method = "PATCH", path = "/api/asset/${location.kind}/${location.id}", body = body.toString(),
                    description = "Position von „${location.name}“", entityKind = "geo", entityId = location.id,
                )
            )
        }
    }

    // ---------------------------------------------------------------- Markierung (z. B. Blühfläche)

    suspend fun setFlag(location: LocationEntity, flag: String, on: Boolean) {
        val current = location.flagList().toMutableList()
        if (on && flag !in current) current += flag
        if (!on) current.remove(flag)
        dao.setFlags(location.id, current.joinToString(","))
        val body = buildJsonObject {
            putJsonObject("data") {
                put("type", "asset--${location.kind}")
                put("id", location.id)
                putJsonObject("attributes") {
                    putJsonArray("flag") { current.forEach { add(JsonPrimitive(it)) } }
                }
            }
        }
        dao.enqueue(
            PendingOp(
                method = "PATCH", path = "/api/asset/${location.kind}/${location.id}", body = body.toString(),
                description = (if (on) "Markierung setzen: " else "Markierung entfernen: ") + location.name,
                entityKind = "flag", entityId = location.id,
            )
        )
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

        /**
         * Mittelpunkt einer farmOS-Geometrie. farmOS liefert lat/lon meist schon mit,
         * sonst Mittelwert der Punkte aus dem WKT-Text.
         */
        fun geoCenter(attributes: JsonObject): Pair<Double, Double>? {
            for (key in listOf("intrinsic_geometry", "geometry")) {
                val g = attributes[key] as? JsonObject ?: continue
                val la = g.str("lat")?.toDoubleOrNull()
                val lo = g.str("lon")?.toDoubleOrNull()
                if (la != null && lo != null && !(la == 0.0 && lo == 0.0)) return la to lo
                val wkt = g.str("value") ?: continue
                val pts = Regex("(-?[0-9]+(?:\\.[0-9]+)?)\\s+(-?[0-9]+(?:\\.[0-9]+)?)").findAll(wkt)
                    .mapNotNull { m ->
                        val x = m.groupValues[1].toDoubleOrNull()
                        val y = m.groupValues[2].toDoubleOrNull()
                        if (x != null && y != null) y to x else null // WKT: „Länge Breite“
                    }.toList()
                if (pts.isNotEmpty()) return pts.map { it.first }.average() to pts.map { it.second }.average()
            }
            return null
        }

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
