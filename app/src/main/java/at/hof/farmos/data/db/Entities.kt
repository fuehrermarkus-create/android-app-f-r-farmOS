package at.hof.farmos.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Bild-Adressen werden zeilenweise (\n) gespeichert: Server-URLs oder lokale file://-Pfade. */
fun String.urlList(): List<String> = split("\n").filter { it.isNotBlank() }

@Entity(tableName = "animals")
data class AnimalEntity(
    @PrimaryKey val id: String,
    val name: String,
    val sex: String?,          // "F", "M" oder null
    val birthdate: Long?,      // Sekunden (Unix)
    val idTag: String?,
    val animalTypeId: String?,
    val animalTypeName: String?,
    val notes: String?,
    val locationNames: String?,
    val status: String,
    val imageUrls: String = "",
    val pending: Boolean = false,
)

/**
 * Flächen (land), Gebäude (structure) und Kulturen/Pflanzungen (plant).
 * Flächen und Gebäude dienen als Standort, Kulturen als zugeordnetes Asset.
 */
@Entity(tableName = "locations")
data class LocationEntity(
    @PrimaryKey val id: String,
    val kind: String,          // "land", "structure" oder "plant"
    val name: String,
    val subtype: String?,      // land_type, structure_type bzw. Kulturart
    val notes: String?,
    val areaInfo: String?,
    val locationNames: String?, // bei Kulturen: wo sie stehen
    val status: String,
    val imageUrls: String = "",
    /** farmOS-Markierungen (Flags), kommagetrennt, z. B. "bluehflaeche" */
    val flags: String = "",
    /** bei Kulturen: IDs der Flächen, auf denen sie stehen (kommagetrennt) */
    val locationIds: String = "",
    /** Größe in Hektar (aus farmOS oder aus den Notizen, z. B. „1,6821 ha“) */
    val areaHa: Double? = null,
    /** Mittelpunkt der Geometrie aus farmOS */
    val lat: Double? = null,
    val lon: Double? = null,
    /** In der App gesetzte Position (z. B. Zufahrt) – bleibt beim Abgleich erhalten */
    val pinLat: Double? = null,
    val pinLon: Double? = null,
) {
    fun flagList(): List<String> = flags.split(",").filter { it.isNotBlank() }

    /** Position zum Teilen: in der App gesetzt, sonst aus farmOS */
    fun position(): Pair<Double, Double>? = when {
        pinLat != null && pinLon != null -> pinLat to pinLon
        lat != null && lon != null -> lat to lon
        else -> null
    }
}

@Entity(tableName = "animal_types")
data class AnimalTypeEntity(
    @PrimaryKey val id: String,
    val name: String,
    val pending: Boolean = false,
)

/**
 * Begriffe aus farmOS-Taxonomien (vocab = plant_type, material_type, unit, log_category)
 * sowie verfügbare Markierungen (vocab = "flag", id = "flag:<name>").
 */
@Entity(tableName = "terms")
data class TermEntity(
    @PrimaryKey val id: String,
    val vocab: String,
    val name: String,
    val pending: Boolean = false,
)

@Entity(tableName = "logs")
data class LogEntity(
    @PrimaryKey val id: String,
    val type: String,          // observation, activity, medical, input, harvest
    val name: String,
    val timestamp: Long,       // Sekunden (Unix)
    val notes: String?,
    val status: String,
    val isMovement: Boolean,
    val assetIds: String,      // kommagetrennt
    val locationIds: String,   // kommagetrennt
    val imageUrls: String = "",
    val hasGeo: Boolean = false,
    val pending: Boolean = false,
    /** Kategorien als Namen, kommagetrennt (z. B. "Pflanzenschutz") */
    val categoryNames: String = "",
)

/** Warteschlange für Änderungen, die noch zum Server müssen. */
@Entity(tableName = "pending_ops")
data class PendingOp(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    /** POST, PATCH oder UPLOAD (dann ist body der lokale Dateipfad). */
    val method: String,
    val path: String,
    val body: String,
    val description: String,
    /** "animal", "animal_type", "log", "quantity", "upload", "attach", "term", "asset" oder "flag" */
    val entityKind: String,
    val entityId: String,
    val createdAt: Long = System.currentTimeMillis(),
    val attempts: Int = 0,
    val lastError: String? = null,
    /** Dauerhaft fehlgeschlagen (z. B. Validierungsfehler) – wird nicht automatisch wiederholt. */
    val failed: Boolean = false,
)

/** Feldliste, z. B. „Silo 1. Schnitt“ oder „Gülle KW 47“ (wird nur in der App gespeichert). */
@Entity(tableName = "field_lists")
data class FieldListEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** Name eines ListWork-Werts oder null */
    val work: String?,
    val notes: String?,
    val createdAt: Long,
    val archived: Boolean = false,
)

@Entity(tableName = "field_list_items", primaryKeys = ["listId", "locationId"])
data class FieldListItem(
    val listId: String,
    val locationId: String,
    val done: Boolean = false,
    /** Sekunden (Unix) */
    val doneAt: Long? = null,
    /** schon als Log in farmOS eingetragen */
    val logged: Boolean = false,
)
