package at.hof.farmos.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** 1.7 -> 1.8: Listen und Positionen über farmOS teilen. */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `locations` ADD COLUMN `pinLogId` TEXT")
        db.execSQL("ALTER TABLE `terms` ADD COLUMN `description` TEXT")
        db.execSQL("ALTER TABLE `field_lists` ADD COLUMN `remote` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `field_list_items` ADD COLUMN `logId` TEXT")
    }
}

/** 1.6 -> 1.7: Maschinen. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `equipment` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                "`manufacturer` TEXT, `model` TEXT, `serial` TEXT, `notes` TEXT, `status` TEXT NOT NULL, " +
                "`imageUrls` TEXT NOT NULL, `pending` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )
        db.execSQL("ALTER TABLE `logs` ADD COLUMN `equipmentIds` TEXT NOT NULL DEFAULT ''")
    }
}

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
        EquipmentEntity::class,
        PendingOp::class,
    ],
    version = 7,
    exportSchema = false,
)
abstract class FarmDatabase : RoomDatabase() {
    abstract fun dao(): FarmDao

    companion object {
        fun create(context: Context): FarmDatabase =
            Room.databaseBuilder(context, FarmDatabase::class.java, "farmos_hof.db")
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                .fallbackToDestructiveMigration()
                .build()
    }
}
