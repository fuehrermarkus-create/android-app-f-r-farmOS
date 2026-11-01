package at.hof.farmos.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        AnimalEntity::class,
        LocationEntity::class,
        AnimalTypeEntity::class,
        LogEntity::class,
        CategoryEntity::class,
        PendingOp::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class FarmDatabase : RoomDatabase() {
    abstract fun dao(): FarmDao

    companion object {
        fun create(context: Context): FarmDatabase =
            Room.databaseBuilder(context, FarmDatabase::class.java, "farmos_hof.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
