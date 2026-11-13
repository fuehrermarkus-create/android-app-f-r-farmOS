package at.hof.farmos.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** 1.2 -> 1.3: Begriffe, Markierungen, Kulturen am Feld; Warteschlange bleibt erhalten. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `terms` (`id` TEXT NOT NULL, `vocab` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, `pending` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )
        db.execSQL("ALTER TABLE `locations` ADD COLUMN `flags` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `locations` ADD COLUMN `locationIds` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `locations` ADD COLUMN `areaHa` REAL")
        db.execSQL("ALTER TABLE `logs` ADD COLUMN `categoryNames` TEXT NOT NULL DEFAULT ''")
        db.execSQL("DROP TABLE IF EXISTS `categories`")
    }
}

@Database(
    entities = [
        AnimalEntity::class,
        LocationEntity::class,
        AnimalTypeEntity::class,
        LogEntity::class,
        TermEntity::class,
        PendingOp::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class FarmDatabase : RoomDatabase() {
    abstract fun dao(): FarmDao

    companion object {
        fun create(context: Context): FarmDatabase =
            Room.databaseBuilder(context, FarmDatabase::class.java, "farmos_hof.db")
                .addMigrations(MIGRATION_2_3)
                .fallbackToDestructiveMigration()
                .build()
    }
}
