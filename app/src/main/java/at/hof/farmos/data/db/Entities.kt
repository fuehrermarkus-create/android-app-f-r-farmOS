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
)

@Entity(tableName = "animal_types")
data class AnimalTypeEntity(
    @PrimaryKey val id: String,
    val name: String,
    val pending: Boolean = false,
)

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
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
    /** "animal", "animal_type", "log", "quantity", "upload" oder "attach" */
    val entityKind: String,
    val entityId: String,
    val createdAt: Long = System.currentTimeMillis(),
    val attempts: Int = 0,
    val lastError: String? = null,
    /** Dauerhaft fehlgeschlagen (z. B. Validierungsfehler) – wird nicht automatisch wiederholt. */
    val failed: Boolean = false,
)
