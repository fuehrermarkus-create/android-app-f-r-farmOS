package at.hof.farmos.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
abstract class FarmDao {

    // ---------------------------------------------------------------- Tiere
    @Query("SELECT * FROM animals WHERE status = 'active' ORDER BY name COLLATE NOCASE")
    abstract fun animals(): Flow<List<AnimalEntity>>

    @Query("SELECT * FROM animals WHERE id = :id")
    abstract fun animal(id: String): Flow<AnimalEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertAnimals(items: List<AnimalEntity>)

    @Query("DELETE FROM animals WHERE pending = 0")
    abstract suspend fun clearSyncedAnimals()

    @Transaction
    open suspend fun replaceAnimals(items: List<AnimalEntity>) {
        clearSyncedAnimals()
        upsertAnimals(items)
    }

    @Query("UPDATE animals SET pending = 0 WHERE id = :id")
    abstract suspend fun markAnimalSynced(id: String)

    // ---------------------------------------------------------------- Standorte
    @Query("SELECT * FROM locations WHERE status = 'active' ORDER BY kind, name COLLATE NOCASE")
    abstract fun locations(): Flow<List<LocationEntity>>

    @Query("SELECT * FROM locations WHERE id = :id")
    abstract fun location(id: String): Flow<LocationEntity?>

    @Query("DELETE FROM locations")
    abstract suspend fun clearLocations()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertLocations(items: List<LocationEntity>)

    @Transaction
    open suspend fun replaceLocations(items: List<LocationEntity>) {
        clearLocations()
        upsertLocations(items)
    }

    // ---------------------------------------------------------------- Tierarten
    @Query("SELECT * FROM animal_types ORDER BY name COLLATE NOCASE")
    abstract fun animalTypes(): Flow<List<AnimalTypeEntity>>

    @Query("DELETE FROM animal_types WHERE pending = 0")
    abstract suspend fun clearSyncedTypes()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertTypes(items: List<AnimalTypeEntity>)

    @Transaction
    open suspend fun replaceTypes(items: List<AnimalTypeEntity>) {
        clearSyncedTypes()
        upsertTypes(items)
    }

    // ---------------------------------------------------------------- Logs
    @Query("SELECT * FROM logs ORDER BY timestamp DESC LIMIT 300")
    abstract fun recentLogs(): Flow<List<LogEntity>>

    @Query(
        "SELECT * FROM logs WHERE (',' || assetIds || ',') LIKE '%,' || :id || ',%' " +
            "OR (',' || locationIds || ',') LIKE '%,' || :id || ',%' " +
            "OR (',' || equipmentIds || ',') LIKE '%,' || :id || ',%' ORDER BY timestamp DESC"
    )
    abstract fun logsFor(id: String): Flow<List<LogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertLogs(items: List<LogEntity>)

    @Query("DELETE FROM logs WHERE pending = 0")
    abstract suspend fun clearSyncedLogs()

    @Transaction
    open suspend fun replaceLogs(items: List<LogEntity>) {
        clearSyncedLogs()
        upsertLogs(items)
    }

    // ---------------------------------------------------------------- Warteschlange
    @Insert
    abstract suspend fun enqueue(op: PendingOp)

    @Query("SELECT * FROM pending_ops ORDER BY seq")
    abstract fun pendingOps(): Flow<List<PendingOp>>

    @Query("SELECT * FROM pending_ops WHERE failed = 0 ORDER BY seq")
    abstract suspend fun openOps(): List<PendingOp>

    @Query("SELECT COUNT(*) FROM pending_ops WHERE failed = 0")
    abstract suspend fun openOpCount(): Int

    @Update
    abstract suspend fun updateOp(op: PendingOp)

    @Query("DELETE FROM pending_ops WHERE seq = :seq")
    abstract suspend fun deleteOp(seq: Long)

    @Query("UPDATE pending_ops SET failed = 0, attempts = 0 WHERE failed = 1")
    abstract suspend fun retryFailedOps()

    @Query("DELETE FROM pending_ops")
    abstract suspend fun clearOps()

    @Query("UPDATE animal_types SET pending = 0 WHERE id = :id")
    abstract suspend fun markTypeSynced(id: String)

    @Query("UPDATE logs SET pending = 0 WHERE id = :id")
    abstract suspend fun markLogSynced(id: String)

    @Query("DELETE FROM animals WHERE id = :id AND pending = 1")
    abstract suspend fun deletePendingAnimal(id: String)

    @Query("DELETE FROM logs WHERE id = :id AND pending = 1")
    abstract suspend fun deletePendingLog(id: String)

    @Query("DELETE FROM animal_types WHERE id = :id AND pending = 1")
    abstract suspend fun deletePendingType(id: String)

    /** Ersetzt Foto-Platzhalter durch die echte Datei-ID vom Server. */

    @Query("UPDATE pending_ops SET body = REPLACE(body, :oldText, :newText)")
    abstract suspend fun replaceInOps(oldText: String, newText: String)
    
    @Query("SELECT * FROM pending_ops ORDER BY seq")
    abstract suspend fun allOpsNow(): List<PendingOp>

    @Query("SELECT * FROM logs WHERE pending = 1")
    abstract suspend fun pendingLogsNow(): List<LogEntity>

    // ---------------------------------------------------------------- Begriffe / Markierungen
    @Query("SELECT * FROM terms WHERE vocab = :vocab ORDER BY name COLLATE NOCASE")
    abstract fun terms(vocab: String): Flow<List<TermEntity>>

    @Query("SELECT * FROM terms WHERE vocab = :vocab")
    abstract suspend fun termsNow(vocab: String): List<TermEntity>

    @Query("DELETE FROM terms WHERE vocab = :vocab AND pending = 0")
    abstract suspend fun clearSyncedTerms(vocab: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertTerms(items: List<TermEntity>)

    @Transaction
    open suspend fun replaceTerms(vocab: String, items: List<TermEntity>) {
        clearSyncedTerms(vocab)
        upsertTerms(items)
    }

    @Query("UPDATE terms SET pending = 0 WHERE id = :id")
    abstract suspend fun markTermSynced(id: String)

    @Query("DELETE FROM terms WHERE id = :id AND pending = 1")
    abstract suspend fun deletePendingTerm(id: String)

    @Query("DELETE FROM terms")
    abstract suspend fun wipeTerms()

    @Query("UPDATE locations SET pinLat = :lat, pinLon = :lon WHERE id = :id")
    abstract suspend fun setPin(id: String, lat: Double?, lon: Double?)

    @Query("SELECT * FROM locations")
    abstract suspend fun locationsNow(): List<LocationEntity>

    @Query("UPDATE locations SET flags = :flags WHERE id = :id")
    abstract suspend fun setFlags(id: String, flags: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertLocation(item: LocationEntity)

    // ---------------------------------------------------------------- Maschinen
    @Query("SELECT * FROM equipment WHERE status = 'active' ORDER BY name COLLATE NOCASE")
    abstract fun equipment(): Flow<List<EquipmentEntity>>

    @Query("SELECT * FROM equipment WHERE id = :id")
    abstract fun equipmentItem(id: String): Flow<EquipmentEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertEquipment(items: List<EquipmentEntity>)

    @Query("DELETE FROM equipment WHERE pending = 0")
    abstract suspend fun clearSyncedEquipment()

    @Transaction
    open suspend fun replaceEquipment(items: List<EquipmentEntity>) {
        clearSyncedEquipment()
        upsertEquipment(items)
    }

    @Query("UPDATE equipment SET pending = 0 WHERE id = :id")
    abstract suspend fun markEquipmentSynced(id: String)

    @Query("DELETE FROM equipment WHERE id = :id AND pending = 1")
    abstract suspend fun deletePendingEquipment(id: String)

    @Query("DELETE FROM equipment")
    abstract suspend fun wipeEquipment()

    // ---------------------------------------------------------------- Feldlisten
    @Query("SELECT * FROM field_lists ORDER BY archived, createdAt DESC")
    abstract fun fieldLists(): Flow<List<FieldListEntity>>

    @Query("SELECT * FROM field_lists WHERE id = :id")
    abstract fun fieldList(id: String): Flow<FieldListEntity?>

    @Query("SELECT * FROM field_lists WHERE id = :id")
    abstract suspend fun fieldListNow(id: String): FieldListEntity?

    @Query("SELECT * FROM field_list_items")
    abstract fun allListItems(): Flow<List<FieldListItem>>

    @Query("SELECT * FROM field_list_items WHERE listId = :listId")
    abstract fun listItems(listId: String): Flow<List<FieldListItem>>

    @Query("SELECT * FROM field_list_items WHERE listId = :listId")
    abstract suspend fun listItemsNow(listId: String): List<FieldListItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertFieldList(list: FieldListEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertListItemsIgnore(items: List<FieldListItem>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertListItems(items: List<FieldListItem>)

    @Query("DELETE FROM field_list_items WHERE listId = :listId AND locationId NOT IN (:keep)")
    abstract suspend fun deleteListItemsNotIn(listId: String, keep: List<String>)

    @Query("DELETE FROM field_list_items WHERE listId = :listId")
    abstract suspend fun deleteAllListItems(listId: String)

    @Query("UPDATE field_list_items SET done = :done, doneAt = :doneAt WHERE listId = :listId AND locationId = :locationId")
    abstract suspend fun setItemDone(listId: String, locationId: String, done: Boolean, doneAt: Long?)

    @Query("UPDATE field_list_items SET logged = 1 WHERE listId = :listId AND locationId = :locationId")
    abstract suspend fun setItemLogged(listId: String, locationId: String)

    @Query("DELETE FROM field_lists WHERE id = :id")
    abstract suspend fun deleteFieldListRow(id: String)

    /** Liste speichern; Felder, die drin bleiben, behalten ihren Erledigt-Status. */
    @Transaction
    open suspend fun saveFieldList(list: FieldListEntity, locationIds: List<String>) {
        upsertFieldList(list)
        if (locationIds.isEmpty()) deleteAllListItems(list.id) else deleteListItemsNotIn(list.id, locationIds)
        insertListItemsIgnore(locationIds.map { FieldListItem(list.id, it) })
    }

    @Transaction
    open suspend fun deleteFieldList(id: String) {
        deleteAllListItems(id)
        deleteFieldListRow(id)
    }

    // ---------------------------------------------------------------- Abmelden
    @Query("DELETE FROM animals")
    abstract suspend fun wipeAnimals()
    @Query("DELETE FROM logs")
    abstract suspend fun wipeLogs()
    @Query("DELETE FROM animal_types")
    abstract suspend fun wipeTypes()

    @Transaction
    open suspend fun wipeAll() {
        wipeAnimals(); wipeLogs(); wipeTypes(); clearLocations(); clearOps(); wipeTerms(); wipeEquipment()
    }
}
