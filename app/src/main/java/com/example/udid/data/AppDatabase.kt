package com.example.udid.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The single Room database for the app.
 *
 * Only one table (sessions) and the DAO that serves it. Reports are
 * calculated from this table on the fly — no aggregate tables.
 */
@Database(
    entities = [
        SessionEntity::class,
        DailySummaryEntity::class,
        DistractingAppConfig::class
    ],
    version = 6,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun sessionDao(): SessionDao

    abstract fun dailySummaryDao(): DailySummaryDao

    abstract fun distractingAppConfigDao(): DistractingAppConfigDao

    companion object {
        private const val DATABASE_NAME = "udid.db"

        /**
         * Simple migration from v1 -> v2: adds an index on `packageName` so
         * the report queries that GROUP BY packageName run faster as the
         * table grows. It is additive only — no data is changed or deleted.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_sessions_packageName " +
                        "ON sessions (packageName)"
                )
            }
        }

        /**
         * Migration v2 -> v3: adds a unique index on
         * (packageName, startedAt, endedAt) so the same session — the same app
         * foreground interval read again on a later launch — is stored exactly
         * once. Tapping "Load Usage Data" repeatedly no longer inflates the
         * report totals or the per-app open counts.
         *
         * Because previous versions could already contain exact duplicates
         * (from loading the same data more than once), we first delete the
         * surplus copies keeping only the lowest-id row per triple, then create
         * the unique index. Without this dedupe first, CREATE UNIQUE INDEX
         * would throw SQLITE_CONSTRAINT and crash on launch.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "DELETE FROM sessions " +
                        "WHERE id NOT IN (" +
                        "  SELECT MIN(id) FROM sessions " +
                        "  GROUP BY packageName, startedAt, endedAt" +
                        ")"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "index_sessions_package_started_ended " +
                        "ON sessions (packageName, startedAt, endedAt)"
                )
            }
        }

        /**
         * Migration v3 -> v4: adds the `daily_summary` table, which stores one
         * aggregate row per calendar day indefinitely. This is the fallback
         * source for old periods after raw `sessions` rows are purged.
         *
         * The table is created empty; it is populated incrementally each time
         * sessions are loaded (see SessionRepository.storeSessions). It is a
         * brand-new table, so the migration is purely additive and safe.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS daily_summary (" +
                        "dateMillis INTEGER NOT NULL, " +
                        "totalScreenTimeMs INTEGER NOT NULL, " +
                        "perAppTimeJson TEXT NOT NULL, " +
                        "perAppOpenCountJson TEXT NOT NULL, " +
                        "PRIMARY KEY(dateMillis))"
                )
            }
        }

        /**
         * Migration v4 -> v5: adds the `distracting_app_config` table for
         * MPI (Mental Peace Index) setup. Stores one row per app the user
         * has marked as distracting, with their chosen daily usage limit.
         *
         * The table is empty on migration; it is populated when the user
         * first opens the MPI setup screen and toggles apps on. Additive
         * only -- no existing data is changed.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS distracting_app_config (" +
                        "packageName TEXT NOT NULL, " +
                        "appName TEXT NOT NULL, " +
                        "dailyLimitMinutes INTEGER NOT NULL, " +
                        "PRIMARY KEY(packageName))"
                )
            }
        }

        /**
         * Migration v5 -> v6: adds the `mpiScore` column to `daily_summary`.
         * Default 0 means "not yet calculated" for existing rows. New rows
         * written after this migration will have a real 0-100 score.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE daily_summary ADD COLUMN mpiScore INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        /**
         * Singleton accessor. Uses the double-checked locking pattern so the
         * database is created only once per process.
         */
        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .build().also { instance = it }
            }
        }
    }
}
