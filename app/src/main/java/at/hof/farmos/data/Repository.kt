package at.hof.farmos.data

import at.hof.farmos.data.db.AnimalEntity
import at.hof.farmos.data.db.AnimalTypeEntity
import at.hof.farmos.data.db.EquipmentEntity
import at.hof.farmos.data.db.TermEntity
import at.hof.farmos.data.db.FarmDao
import at.hof.farmos.data.db.FieldListEntity
import at.hof.farmos.data.db.FieldListItem
import at.hof.farmos.data.db.LocationEntity
import at.hof.farmos.data.db.LogEntity
import at.hof.farmos.data.db.PendingOp
import at.hof.farmos.data.db.urlList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    SEEDING("seeding", "Aussaat"),
    MAINTENANCE("maintenance", "Wartung");

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
    val equipment: List<AssetRef> = emptyList(), // eingesetzte Maschinen
)

/** Arbeiten an Maschinen. */
enum class MachineWork(val label: String, val logType: String, val category: String) {
    MAINTENANCE("Wartung", "maintenance", "Wartung"),
    REPAIR("Reparatur", "maintenance", "Reparatur"),
    DAMAGE("Schaden / Beobachtung", "observation", "Maschine"),
}

data class MachineLogDraft(
    val work: MachineWork,
    val machine: AssetRef,
    val timestamp: Long,
    val notes: String?,
    val hours: String? = null,      // Betriebsstunden-Stand
    val photos: List<String> = emptyList(),
)

/** Was auf den Feldern einer Liste gemacht wird – bestimmt, wie es in farmOS eingetragen wird. */
enum class ListWork(val label: String, val logType: String, val category: String?, val material: String? = null) {
    SILAGE("Silo / Schnitt", "harvest", "Silo"),
    HAY("Heu", "harvest", "Heu"),
    MANURE("Gülle", "input", "Düngung", "Gülle"),
    FERTILIZING("Düngung", "input", "Düngung"),
    SPRAYING("Pflanzenschutz", "input", "Pflanzenschutz"),
    HARVEST("Ernte", "harvest", "Ernte"),
    CARE("Pflege / Mulchen", "activity", "Pflege"),
    OTHER("Sonstiges", "activity", null),
}

/** Einheiten mit passender farmOS-Messgröße. */
val UNIT_MEASURE = linkedMapOf(
    "kg/ha" to "rate", "l/ha" to "rate", "m³/ha" to "rate", "t/ha" to "rate", "dt/ha" to "rate",
    "Körner/m²" to "rate", "kg" to "weight", "dt" to "weight", "t" to "weight",
    "l" to "volume", "m³" to "volume", "Stk" to "count", "Ballen" to "count", "ha" to "area",
    "h" to "time",
)

/** Kategorie-Präfixe für geteilte Feldlisten und Kategorie für Positionen (Zufahrt). */
const val LIST_PREFIX = "Liste: "
const val ARCHIVE_PREFIX = "Archiv: "
const val POSITION_CATEGORY = "Position"

/** farmOS-Markierung für Blühflächen (muss in farmOS angelegt sein, siehe README). */
const val BLUEH_FLAG = "bluehflaeche"

data class SyncResult(val pushed: Int, val failed: Int, val error: String?, val offline: Boolean)

class Repository(
    private val api: FarmApi,
    private val dao: FarmDao,
    private val prefs: Prefs,
    private val photoDir: File,
    val nextcloud: Nextcloud,
) {
    val animals = dao.animals()
    val locations = dao.locations()
    val animalTypes = dao.animalTypes()
    val categories = dao.terms("log_category")
    val plantTypes = dao.terms("plant_type")
    val materials = dao.terms("material_type")
    val flags = dao.terms("flag")
    val fieldLists = dao.fieldLists()
    val allListItems = dao.allListItems()
    fun fieldList(id: String) = dao.fieldList(id)
    val equipment = dao.equipment()
    fun equipmentItem(id: String) = dao.equipmentItem(id)
    fun listItems(listId: String) = dao.listItems(listId)
    val recentLogs = dao.recentLogs()
    val pendingOps = dao.pendingOps()
    fun animal(id: String) = dao.animal(id)
    fun location(id: String) = dao.location(id)
    fun logsFor(id: String) = dao.logsFor(id)

    // ================================================================ Synchronisation

    /** Verhindert, dass App und Hintergrund-Abgleich gleichzeitig dasselbe hochladen. */
    private val syncMutex = Mutex()

    /** Erst lokale Änderungen hochladen, dann aktuelle Daten vom Server holen. */
    suspend fun sync(): SyncResult = syncMutex.withLock { doSync() }

    /** Nur hochladen (schnell, z. B. direkt nach dem Speichern). */
    suspend fun pushPending(): SyncResult = syncMutex.withLock { doPush() }

    private suspend fun doSync(): SyncResult {
        val push = doPush()
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

    private suspend fun doPush(): SyncResult {
        // Ältere, nur lokal gespeicherte Listen und Positionen jetzt mit farmOS teilen
        runCatching { publishLocal() }
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
            if (op.method == "WEBDAV") {
                // Nextcloud-Fehler blockieren den farmOS-Abgleich nicht
                val file = File(op.body)
                when {
                    !nextcloud.configured -> {
                        dao.updateOp(op.copy(failed = true, lastError = "Nextcloud nicht eingerichtet"))
                        failed++
                    }
                    !file.exists() -> {
                        dao.updateOp(op.copy(failed = true, lastError = "Fotodatei nicht mehr vorhanden"))
                        failed++
                    }
                    else -> try {
                        nextcloud.upload(op.path, file)
                        markDone(op); pushed++
                    } catch (e: ApiException) {
                        dao.updateOp(op.copy(attempts = op.attempts + 1, lastError = e.message, failed = true))
                        failed++
                    } catch (e: Exception) {
                        dao.updateOp(op.copy(attempts = op.attempts + 1, lastError = describe(e)))
                    }
                }
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
                if ((e.code == 409 && op.method != "UPLOAD") || (e.code == 404 && op.method == "DELETE")) {
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
            "equipment" -> dao.markEquipmentSynced(op.entityId)
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
            "equipment" -> dao.deletePendingEquipment(op.entityId)
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

    /**
     * Aktive Assets laden – farmOS 3 filtert über „status“, farmOS 4 über „archived“.
     * Die App merkt sich, welche Variante der Server versteht.
     */
    private suspend fun fetchAssets(label: String, bundle: String): ApiList {
        val v4 = prefs.assetsUseArchived
        fun path(useArchived: Boolean) = "/api/asset/$bundle?" +
            (if (useArchived) "filter[archived]=0" else "filter[status]=active") + "&page[limit]=50&include=image"
        return try {
            fetch(label, path(v4))
        } catch (e: ApiException) {
            if (e.code == 400 || e.code == 422) {
                prefs.assetsUseArchived = !v4
                fetch(label, path(!v4))
            } else throw e
        }
    }

    /** "active"/"archived" aus farmOS 3 (status) oder farmOS 4 (archived = true/false). */
    private fun assetStatus(a: JsonObject): String {
        val archived = (a["archived"] as? JsonPrimitive)?.content
        return when {
            archived == "true" -> "archived"
            archived == "false" -> "active"
            else -> a.str("status") ?: "active"
        }
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
                TermEntity(it.str("id")!!, vocab, it.attributes.str("name") ?: "?",
                    description = it.attributes.textField("description"))
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
        val landList = fetchAssets("Flächen", "land")
        val lands = landList.data.map { it.toLocation("land", "land_type", landList.included) }
        val structList = fetchAssets("Gebäude", "structure")
        val structures = structList.data.map { it.toLocation("structure", "structure_type", structList.included) }
        val locNames = (lands + structures).associate { it.id to it.name }

        // Kulturen (Pflanzungen) mit Kulturart
        val plantTypes = termNames
        val plantList = fetchAssets("Kulturen", "plant")
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
                status = assetStatus(a),
                imageUrls = imageUrls(o, plantList.included),
                locationIds = o.relationIds("location").joinToString(","),
                lat = geoCenter(a)?.first,
                lon = geoCenter(a)?.second,
            )
        }
        // Positionen (Zufahrt): aus farmOS-Einträgen „Position“; noch nicht übertragene eigene bleiben
        val serverPins = fetchPins()
        val openIds = dao.openOps().map { it.entityId }.toSet()
        val local = dao.locationsNow().associateBy { it.id }
        dao.replaceLocations((lands + structures + plants).map { loc ->
            val mine = local[loc.id]
            val keepLocal = mine != null && (
                (mine.pinLogId != null && openIds.contains(mine.pinLogId)) ||
                    (mine.pinLat != null && mine.pinLogId == null)
                )
            val server = serverPins[loc.id]
            when {
                keepLocal && mine != null -> loc.copy(pinLat = mine.pinLat, pinLon = mine.pinLon, pinLogId = mine.pinLogId)
                server != null -> loc.copy(pinLat = server.first, pinLon = server.second, pinLogId = server.third)
                else -> loc
            }
        })

        // Maschinen
        val eqList = fetchAssets("Maschinen", "equipment")
        dao.replaceEquipment(eqList.data.map { o ->
            val a = o.attributes
            EquipmentEntity(
                id = o.str("id")!!,
                name = a.str("name") ?: "?",
                manufacturer = a.str("manufacturer"),
                model = a.str("model"),
                serial = a.str("serial_number"),
                notes = a.textField("notes"),
                status = assetStatus(a),
                imageUrls = imageUrls(o, eqList.included),
            )
        })

        // Tiere
        val animalList = fetchAssets("Tiere", "animal")
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
                status = assetStatus(a),
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
                    equipmentIds = o.relationIds("equipment").joinToString(","),
                )
            }
        }
        dao.replaceLogs(logs)

        // Geteilte Feldlisten
        try {
            pullLists()
        } catch (e: Exception) {
            if (FarmApi.isNetworkError(e)) throw e
            hiddenWarnings += "Feldlisten (${e.message})"
        }
    }

    /** Neueste „Position“-Einträge je Fläche: Fläche -> (Breite, Länge, Eintrag-ID). */
    private suspend fun fetchPins(): Map<String, Triple<Double, Double, String>> {
        val list = try {
            api.getAll(
                "/api/log/observation?filter[pos][condition][path]=category.name" +
                    "&filter[pos][condition][value]=$POSITION_CATEGORY&sort=-timestamp&page[limit]=50", 10,
            )
        } catch (e: ApiException) {
            return emptyMap()
        }
        val out = mutableMapOf<String, Triple<Double, Double, String>>()
        for (o in list.data) {
            val c = geoCenter(o.attributes) ?: continue
            val id = o.str("id") ?: continue
            for (loc in o.relationIds("location")) if (loc !in out) out[loc] = Triple(c.first, c.second, id)
        }
        return out
    }

    /** Feldlisten aus farmOS: Liste = Kategorie „Liste: …“, Feld = Eintrag mit dieser Kategorie. */
    private suspend fun pullLists() {
        if (dao.openOpCount() > 0) return // noch nicht alles übertragen -> lokalen Stand behalten
        val listTerms = dao.termsNow("log_category").filter { it.name.startsWith(LIST_PREFIX) && !it.pending }
        val byTerm = listTerms.associateBy { it.id }
        val locs = dao.locationsNow().associateBy { it.id }
        val items = mutableMapOf<Pair<String, String>, FieldListItem>()
        val firstTs = mutableMapOf<String, Long>()
        for (type in (ListWork.entries.map { it.logType } + LogKind.entries.map { it.apiType }).distinct()) {
            val page = try {
                api.getAll(
                    "/api/log/$type?filter[l][condition][path]=category.name" +
                        "&filter[l][condition][operator]=STARTS_WITH&filter[l][condition][value]=Liste%3A" +
                        "&page[limit]=50", 20,
                )
            } catch (e: ApiException) {
                if (e.code == 404) continue else throw e
            }
            for (o in page.data) {
                val logId = o.str("id") ?: continue
                val listIds = o.relationIds("category").filter { it in byTerm }
                if (listIds.isEmpty()) continue
                val a = o.attributes
                val done = a.str("status") != "pending"
                val ts = parseTime(a.str("timestamp"))
                // Feld direkt als Ort, sonst über die Kultur, auf der gearbeitet wurde
                val viaPlants = o.relationIds("asset").flatMap { id ->
                    locs[id]?.takeIf { it.kind == "plant" }?.locationIds?.split(",") ?: emptyList()
                }
                val fields = (o.relationIds("location") + viaPlants)
                    .filter { it.isNotBlank() && (locs[it]?.kind == "land" || locs[it]?.kind == "structure") }
                    .distinct()
                for (listId in listIds) for (f in fields) {
                    items[listId to f] = FieldListItem(listId, f, done = done, doneAt = if (done) ts else null,
                        logged = true, logId = logId)
                    if (ts != null) firstTs[listId] = minOf(firstTs[listId] ?: ts, ts)
                }
            }
        }
        val known = dao.fieldListsNow().associateBy { it.id }
        val lists = listTerms.map { t ->
            val desc = t.description.orEmpty()
            val work = Regex("Arbeit: ([A-Z_]+)").find(desc)?.groupValues?.get(1)
                ?.takeIf { w -> ListWork.entries.any { it.name == w } }
            val notes = desc.substringAfter("Notiz: ", "").trim().ifBlank { null }
            FieldListEntity(
                id = t.id, name = t.name.removePrefix(LIST_PREFIX).trim(), work = work, notes = notes,
                createdAt = known[t.id]?.createdAt ?: firstTs[t.id]?.let { it * 1000 } ?: System.currentTimeMillis(),
                remote = true,
            )
        }
        dao.replaceRemoteLists(lists, items.values.toList())
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

        out += DiagItem(
            "farmOS-Version",
            true,
            if (prefs.assetsUseArchived) "4.x erkannt (Archiv-Feld)" else "3.x (oder noch nicht erkannt)",
        )

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
            "Maschinen" to "/api/asset/equipment",
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
            status = assetStatus(a),
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
        dao.allOpsNow().filter { it.method == "UPLOAD" || it.method == "WEBDAV" }.forEach { referenced += it.body }
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
        val equipment: List<AssetRef> = emptyList(),
        /** "done" oder "pending" (geplant/offen) */
        val status: String = "done",
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
        if (spec.equipment.isNotEmpty()) relations["equipment"] = spec.equipment.map { it.type to it.id }
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
                    put("status", spec.status)
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
                    notes = spec.notes?.ifBlank { null }, status = spec.status, isMovement = spec.movement,
                    assetIds = spec.assets.joinToString(",") { it.id },
                    locationIds = spec.locations.joinToString(",") { it.id },
                    imageUrls = spec.photos.joinToString("\n") { "file://$it" },
                    hasGeo = spec.latitude != null,
                    pending = true,
                    categoryNames = spec.categoryNames.joinToString(", "),
                    equipmentIds = spec.equipment.joinToString(",") { it.id },
                )
            )
        )
        dao.enqueue(
            PendingOp(
                method = "POST", path = "/api/log/${spec.apiType}", body = body.toString(),
                description = "${spec.typeLabel} „${spec.name}“", entityKind = "log", entityId = id,
            )
        )

        // Fotos zusätzlich in Nextcloud ablegen: <Ordner>/<Feld oder Tier>/<Datum Titel>.jpg
        if (spec.photos.isNotEmpty() && nextcloud.configured) {
            val subject = (spec.locations + spec.assets).firstOrNull()?.name ?: "Allgemein"
            val stamp = Instant.ofEpochSecond(spec.timestamp).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm"))
            spec.photos.forEachIndexed { i, path ->
                val nr = if (spec.photos.size > 1) " (${i + 1})" else ""
                val remote = prefs.ncFolder + "/" + Nextcloud.clean(subject) + "/" +
                    Nextcloud.clean("$stamp ${spec.name}$nr") + ".jpg"
                dao.enqueue(
                    PendingOp(
                        method = "WEBDAV", path = remote, body = path,
                        description = "Foto nach Nextcloud: $subject", entityKind = "nextcloud",
                        entityId = UUID.randomUUID().toString(),
                    )
                )
            }
        }

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
                equipment = d.equipment,
            )
        )
    }

    // ---------------------------------------------------------------- Maschinen

    suspend fun createEquipment(name: String, manufacturer: String?, model: String?, serial: String?, notes: String?) {
        val id = UUID.randomUUID().toString()
        val body = buildJsonObject {
            putJsonObject("data") {
                put("type", "asset--equipment")
                put("id", id)
                putJsonObject("attributes") {
                    put("name", name.trim())
                    if (!manufacturer.isNullOrBlank()) put("manufacturer", manufacturer.trim())
                    if (!model.isNullOrBlank()) put("model", model.trim())
                    if (!serial.isNullOrBlank()) put("serial_number", serial.trim())
                    if (!notes.isNullOrBlank()) putJsonObject("notes") { put("value", notes); put("format", "default") }
                }
            }
        }
        dao.upsertEquipment(
            listOf(
                EquipmentEntity(
                    id, name.trim(), manufacturer?.ifBlank { null }, model?.ifBlank { null },
                    serial?.ifBlank { null }, notes?.ifBlank { null }, "active", pending = true,
                )
            )
        )
        dao.enqueue(
            PendingOp(
                method = "POST", path = "/api/asset/equipment", body = body.toString(),
                description = "Maschine „${name.trim()}“", entityKind = "equipment", entityId = id,
            )
        )
    }

    suspend fun createMachineLog(d: MachineLogDraft) {
        val w = d.work
        val title = "${w.label}: ${d.machine.name}"
        val catId = ensureTerm("log_category", w.category)
        val hoursQty = d.hours?.let { enqueueQuantity(it, "h", "Betriebsstunden", title, unitTerm = true) }
        enqueueLog(
            LogSpec(
                apiType = w.logType, typeLabel = w.label, name = title, timestamp = d.timestamp,
                notes = d.notes, assets = listOf(d.machine), quantityIds = listOfNotNull(hoursQty),
                photos = d.photos, categoryIds = listOf(catId), categoryNames = listOf(w.category),
            )
        )
    }

    // ---------------------------------------------------------------- Feldlisten (geteilt über farmOS)

    private fun workOf(list: FieldListEntity?): ListWork? = ListWork.entries.firstOrNull { it.name == list?.work }

    private fun listDescription(work: ListWork?, notes: String?) = buildString {
        append("Feldliste aus der App farmOS Hof")
        if (work != null) append("\nArbeit: ").append(work.name)
        if (!notes.isNullOrBlank()) append("\nNotiz: ").append(notes.trim())
    }

    /** Liste als Kategorie „Liste: <Name>“ in farmOS anlegen bzw. ändern. */
    private suspend fun enqueueListTerm(id: String, name: String, work: ListWork?, notes: String?, create: Boolean,
                                        prefix: String = LIST_PREFIX) {
        val full = prefix + name.trim()
        val desc = listDescription(work, notes)
        val body = buildJsonObject {
            putJsonObject("data") {
                put("type", "taxonomy_term--log_category")
                put("id", id)
                putJsonObject("attributes") {
                    put("name", full)
                    putJsonObject("description") { put("value", desc); put("format", "default") }
                }
            }
        }
        dao.upsertTerms(listOf(TermEntity(id, "log_category", full, pending = create, description = desc)))
        dao.enqueue(
            PendingOp(
                method = if (create) "POST" else "PATCH",
                path = if (create) "/api/taxonomy_term/log_category" else "/api/taxonomy_term/log_category/$id",
                body = body.toString(), description = "Liste „${name.trim()}“", entityKind = "term", entityId = id,
            )
        )
    }

    /** Ein Eintrag je Feld: offen (geplant) oder erledigt. */
    private suspend fun enqueueItemLog(
        listId: String, listName: String, work: ListWork, notes: String?,
        loc: LocationEntity, done: Boolean, doneAt: Long?,
    ): String {
        val catId = work.category?.let { ensureTerm("log_category", it) }
        val matId = work.material?.let { ensureTerm("material_type", it) }
        val title = "$listName – ${loc.name}"
        val areaQty = loc.areaHa?.let {
            enqueueQuantity(String.format(Locale.US, "%.4f", it), "ha", "Fläche", title, unitTerm = true)
        }
        return enqueueLog(
            LogSpec(
                apiType = work.logType, typeLabel = work.label, name = title,
                timestamp = doneAt ?: (System.currentTimeMillis() / 1000),
                notes = notes,
                locations = listOf(AssetRef(loc.id, "asset--${loc.kind}", loc.name)),
                quantityIds = listOfNotNull(areaQty),
                categoryIds = listOfNotNull(catId, listId),
                categoryNames = listOfNotNull(work.category),
                extraRefs = matId?.let { mapOf("material_type" to listOf("taxonomy_term--material_type" to it)) }
                    ?: emptyMap(),
                status = if (done) "done" else "pending",
            )
        )
    }

    private suspend fun logType(logId: String, fallback: String): String = dao.logNow(logId)?.type ?: fallback

    private suspend fun enqueueDeleteLog(logId: String, type: String, what: String) {
        dao.enqueue(
            PendingOp(
                method = "DELETE", path = "/api/log/$type/$logId", body = "",
                description = "Löschen: $what", entityKind = "delete", entityId = logId,
            )
        )
        dao.deleteLogRow(logId)
    }

    /** Liste von einem vorhandenen Eintrag lösen (Eintrag selbst bleibt). */
    private suspend fun enqueueRemoveListCategory(logId: String, type: String, listId: String) {
        val body = buildJsonObject {
            putJsonArray("data") {
                add(buildJsonObject { put("type", "taxonomy_term--log_category"); put("id", listId) })
            }
        }
        dao.enqueue(
            PendingOp(
                method = "DELETE", path = "/api/log/$type/$logId/relationships/category", body = body.toString(),
                description = "Feld aus Liste entfernen", entityKind = "rel", entityId = logId,
            )
        )
    }

    /** Ältere, nur auf dem Handy gespeicherte Liste in farmOS anlegen. */
    private suspend fun publishList(list: FieldListEntity) {
        val w = workOf(list) ?: ListWork.OTHER
        enqueueListTerm(list.id, list.name, workOf(list), list.notes, create = true)
        val locs = dao.locationsNow().associateBy { it.id }
        for (item in dao.listItemsNow(list.id)) {
            if (item.logId != null || item.logged) continue // bereits eingetragene nicht doppelt anlegen
            val loc = locs[item.locationId] ?: continue
            val logId = enqueueItemLog(list.id, list.name, w, list.notes, loc, item.done, item.doneAt)
            dao.setItemLogId(list.id, item.locationId, logId)
        }
        dao.upsertFieldList(list.copy(remote = true))
    }

    /** Alte lokale Listen und Positionen (vor Version 1.8) übertragen. */
    private suspend fun publishLocal() {
        if (!prefs.isLoggedIn) return
        for (list in dao.fieldListsNow()) if (!list.remote) publishList(list)
        for (loc in dao.locationsNow()) {
            if (loc.kind != "plant" && loc.pinLat != null && loc.pinLon != null && loc.pinLogId == null) {
                setPosition(loc, loc.pinLat, loc.pinLon, alsoGeometry = false)
            }
        }
    }

    suspend fun saveFieldList(id: String?, name: String, work: ListWork?, notes: String?, locationIds: List<String>): String {
        val before = id?.let { dao.fieldListNow(it) }
        if (before != null && !before.remote) publishList(before)
        val listId = id ?: UUID.randomUUID().toString()
        val cleanName = name.trim()
        val cleanNotes = notes?.trim()?.ifBlank { null }
        val w = work ?: ListWork.OTHER
        if (before == null) {
            enqueueListTerm(listId, cleanName, work, cleanNotes, create = true)
        } else if (before.name != cleanName || before.work != work?.name || before.notes != cleanNotes) {
            enqueueListTerm(listId, cleanName, work, cleanNotes, create = false)
        }
        dao.upsertFieldList(
            FieldListEntity(listId, cleanName, work?.name, cleanNotes, before?.createdAt ?: System.currentTimeMillis(),
                remote = true)
        )
        val wanted = locationIds.distinct()
        val current = dao.listItemsNow(listId).associateBy { it.locationId }
        // Herausgenommene Felder: offenen Eintrag löschen, erledigten nur von der Liste lösen
        for ((locId, item) in current) {
            if (locId in wanted) continue
            item.logId?.let { logId ->
                val type = logType(logId, w.logType)
                if (item.done) enqueueRemoveListCategory(logId, type, listId)
                else enqueueDeleteLog(logId, type, "$cleanName (offenes Feld)")
            }
            dao.deleteListItem(listId, locId)
        }
        // Neue Felder: offenen Eintrag anlegen
        val locs = dao.locationsNow().associateBy { it.id }
        for (locId in wanted) {
            if (locId in current) continue
            val loc = locs[locId] ?: continue
            val logId = enqueueItemLog(listId, cleanName, w, cleanNotes, loc, done = false, doneAt = null)
            dao.upsertListItems(listOf(FieldListItem(listId, locId, logId = logId)))
        }
        return listId
    }

    /** Liste aus vorhandenen Einträgen: die Einträge bekommen zusätzlich die Listen-Kategorie. */
    suspend fun createListFromLogs(name: String, work: ListWork?, entries: Map<String, Pair<Long, LogEntity>>): String {
        val id = UUID.randomUUID().toString()
        enqueueListTerm(id, name, work, null, create = true)
        dao.upsertFieldList(FieldListEntity(id, name.trim(), work?.name, null, System.currentTimeMillis(), remote = true))
        for (log in entries.values.map { it.second }.distinctBy { it.id }) {
            val body = buildJsonObject {
                putJsonArray("data") {
                    add(buildJsonObject { put("type", "taxonomy_term--log_category"); put("id", id) })
                }
            }
            dao.enqueue(
                PendingOp(
                    method = "POST", path = "/api/log/${log.type}/${log.id}/relationships/category",
                    body = body.toString(), description = "Eintrag zur Liste „${name.trim()}“",
                    entityKind = "rel", entityId = log.id,
                )
            )
        }
        dao.upsertListItems(entries.map { (loc, p) ->
            FieldListItem(id, loc, done = true, doneAt = p.first, logged = true, logId = p.second.id)
        })
        return id
    }

    suspend fun setListItemDone(listId: String, locationId: String, done: Boolean) {
        val list = dao.fieldListNow(listId) ?: return
        if (!list.remote) publishList(list)
        val now = System.currentTimeMillis() / 1000
        dao.setItemDone(listId, locationId, done, if (done) now else null)
        val item = dao.listItemsNow(listId).firstOrNull { it.locationId == locationId } ?: return
        val logId = item.logId ?: return
        val type = logType(logId, (workOf(list) ?: ListWork.OTHER).logType)
        val status = if (done) "done" else "pending"
        val body = buildJsonObject {
            putJsonObject("data") {
                put("type", "log--$type")
                put("id", logId)
                putJsonObject("attributes") {
                    put("status", status)
                    if (done) put("timestamp", isoTime(now))
                }
            }
        }
        dao.setLogStatus(logId, status)
        dao.enqueue(
            PendingOp(
                method = "PATCH", path = "/api/log/$type/$logId", body = body.toString(),
                description = (if (done) "Erledigt: " else "Wieder offen: ") + list.name,
                entityKind = "status", entityId = logId,
            )
        )
    }

    suspend fun setAllDone(listId: String) {
        dao.listItemsNow(listId).filter { !it.done }.forEach { setListItemDone(listId, it.locationId, true) }
    }

    /** Liste löschen: offene Einträge werden entfernt, erledigte bleiben als Aufzeichnung erhalten. */
    suspend fun deleteFieldList(id: String) {
        val list = dao.fieldListNow(id) ?: return
        if (list.remote) {
            val w = workOf(list) ?: ListWork.OTHER
            for (item in dao.listItemsNow(id)) {
                val logId = item.logId ?: continue
                if (!item.done) enqueueDeleteLog(logId, logType(logId, w.logType), "${list.name} (offenes Feld)")
            }
            // Kategorie umbenennen, damit die Liste nicht wieder auftaucht
            enqueueListTerm(id, list.name, workOf(list), list.notes, create = false, prefix = ARCHIVE_PREFIX)
        }
        dao.deleteFieldList(id)
    }

    // ---------------------------------------------------------------- Position einer Fläche

    /**
     * Position (z. B. Zufahrt) setzen oder löschen. Gespeichert als farmOS-Eintrag „Zufahrt: <Fläche>“
     * mit GPS-Punkt und Kategorie „Position“ – so sehen alle Handys dieselbe Position.
     * Hat die Fläche in farmOS noch keine Geometrie, wird der Punkt auch dort eingetragen.
     */
    suspend fun setPosition(location: LocationEntity, lat: Double?, lon: Double?, alsoGeometry: Boolean = true) {
        location.pinLogId?.let { old ->
            enqueueDeleteLog(old, logType(old, "observation"), "alte Position von „${location.name}“")
        }
        if (lat == null || lon == null) {
            dao.setPinFull(location.id, null, null, null)
            return
        }
        val catId = ensureTerm("log_category", POSITION_CATEGORY)
        val logId = enqueueLog(
            LogSpec(
                apiType = "observation", typeLabel = "Position", name = "Zufahrt: ${location.name}",
                timestamp = System.currentTimeMillis() / 1000,
                notes = "Position / Zufahrt (gesetzt in der App farmOS Hof)",
                locations = listOf(AssetRef(location.id, "asset--${location.kind}", location.name)),
                latitude = lat, longitude = lon,
                categoryIds = listOf(catId), categoryNames = listOf(POSITION_CATEGORY),
            )
        )
        dao.setPinFull(location.id, lat, lon, logId)
        if (alsoGeometry && location.lat == null && location.kind != "plant") {
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
