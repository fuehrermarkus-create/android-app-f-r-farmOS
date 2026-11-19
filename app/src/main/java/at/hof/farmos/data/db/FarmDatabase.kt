package at.hof.farmos.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** 1.4 -> 1.5: Feldlisten. */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `field_lists` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                "`work` TEXT, `notes` TEXT, `createdAt` INTEGER NOT NULL, `archived` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `field_list_items` (`listId` TEXT NOT NULL, `locationId` TEXT NOT NULL, " +
                "`done` INTEGER NOT NULL, `doneAt` INTEGER, `logged` INTEGER NOT NULL, " +
                "PRIMARY KEY(`listId`, `locationId`))"
        )
    }
}

/** 1.3 -> 1.4: Positionen der Flächen. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        for (c in listOf("lat", "lon", "pinLat", "pinLon")) {
            db.execSQL("ALTER TABLE `locations` ADD COLUMN `$c` REAL")
        }
    }
}

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
        FieldListEntity::class,
        FieldListItem::class,
        PendingOp::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class FarmDatabase : RoomDatabase() {
    abstract fun dao(): FarmDao

    companion object {
        fun create(context: Context): FarmDatabase =
            Room.databaseBuilder(context, FarmDatabase::class.java, "farmos_hof.db")
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .fallbackToDestructiveMigration()
                .build()
    }
}
