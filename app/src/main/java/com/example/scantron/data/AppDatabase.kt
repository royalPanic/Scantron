package com.example.scantron.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.UUID

@Database(
    entities = [Container::class, ContainerItem::class, PendingDeletion::class],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun containerDao(): ContainerDao
    abstract fun itemDao(): ItemDao
    abstract fun deletionDao(): DeletionDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE container_items ADD COLUMN barcode TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_container_items_barcode ON container_items(barcode)")
            }
        }

        /**
         * Adds the stable [ContainerItem.uuid] identity column.
         *
         * Existing rows are backfilled with a generated UUID instead of an empty string so every
         * row gains a distinct, non-empty identity immediately. SQLite cannot parameterise a
         * per-row random value inside a single UPDATE, so the backfill issues one statement per
         * affected row inside an explicit transaction.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE container_items ADD COLUMN uuid TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_container_items_uuid ON container_items(uuid)")

                db.beginTransaction()
                try {
                    val pendingIds = ArrayList<Long>()
                    db.query("SELECT id FROM container_items WHERE TRIM(uuid) = ''").use { cursor ->
                        while (cursor.moveToNext()) {
                            pendingIds.add(cursor.getLong(0))
                        }
                    }
                    pendingIds.forEach { rowId ->
                        db.execSQL(
                            "UPDATE container_items SET uuid = ? WHERE id = ?",
                            arrayOf<Any>(UUID.randomUUID().toString(), rowId),
                        )
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        }

        /**
         * Adds the `pending_deletions` tombstone log.
         *
         * A tombstone is what lets a deletion survive long enough to reach a peer that was offline
         * when it happened. The table's primary key is (kind, id) rather than a synthetic row id so
         * recording the same deletion twice replaces instead of duplicating - see
         * [com.example.scantron.data.PendingDeletion].
         *
         * Declared in SQL that matches Room's own generated schema exactly (`NOT NULL` on every
         * column, composite primary key), because a mismatch here is only discovered at runtime as
         * an integrity check failure on the next open.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pending_deletions` (" +
                        "`kind` TEXT NOT NULL, " +
                        "`id` TEXT NOT NULL, " +
                        "`containerId` TEXT NOT NULL, " +
                        "`deletedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`kind`, `id`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_pending_deletions_deletedAt` " +
                        "ON `pending_deletions` (`deletedAt`)",
                )
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "scantron_inventory_db"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
