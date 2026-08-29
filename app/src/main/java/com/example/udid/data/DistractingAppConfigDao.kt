package com.example.udid.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * DAO for the `distracting_app_config` table.
 *
 * Only rows for apps the user has toggled ON are stored. To disable an app
 * (toggle off), simply delete its row. The table is tiny (a handful of rows
 * at most), so no indices beyond the primary key are needed.
 */
@Dao
interface DistractingAppConfigDao {

    /** Enable (or update the limit of) a distracting app. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(config: DistractingAppConfig)

    /** Disable a distracting app (remove its row). */
    @Query("DELETE FROM distracting_app_config WHERE packageName = :packageName")
    suspend fun delete(packageName: String)

    /** All currently-enabled distracting apps. */
    @Query("SELECT * FROM distracting_app_config ORDER BY appName ASC")
    suspend fun getAll(): List<DistractingAppConfig>

    /** Just the package names of enabled distracting apps. */
    @Query("SELECT packageName FROM distracting_app_config")
    suspend fun getEnabledPackageNames(): List<String>
}
