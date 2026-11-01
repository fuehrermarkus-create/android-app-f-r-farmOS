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
            "OR (',' || locationIds || ',') LIKE '%,' || :id || ',%' ORDER BY timestamp DESC"
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
    @Query("UPDATE pending_ops SET body = REPLACE(body, :old, :new)")
    abstract suspend fun replaceInOps(old: String, new: String)

    @Query("SELECT * FROM pending_ops ORDER BY seq")
    abstract suspend fun allOpsNow(): List<PendingOp>

    @Query("SELECT * FROM logs WHERE pending = 1")
    abstract suspend fun pendingLogsNow(): List<LogEntity>

    // ---------------------------------------------------------------- Kategorien
    @Query("SELECT * FROM categories ORDER BY name COLLATE NOCASE")
    abstract fun categories(): Flow<List<CategoryEntity>>

    @Query("DELETE FROM categories")
    abstract suspend fun clearCategories()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertCategories(items: List<CategoryEntity>)

    @Transaction
    open suspend fun replaceCategories(items: List<CategoryEntity>) {
        clearCategories()
        upsertCategories(items)
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
        wipeAnimals(); wipeLogs(); wipeTypes(); clearLocations(); clearOps(); clearCategories()
    }
}
