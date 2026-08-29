package com.example.udid.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per app the user has marked as "distracting" in the MPI setup.
 *
 * Only enabled apps appear here — apps the user has not toggled on are simply
 * absent from the table (not stored with isEnabled=false). This keeps the
 * table small and makes "get all distracting apps" a trivial query.
 *
 * [dailyLimitMinutes] is the comfortable daily usage limit the user set for
 * this app. The MPI calculation (a later step) will compare actual usage
 * against this limit.
 */
@Entity(tableName = "distracting_app_config")
data class DistractingAppConfig(
    @PrimaryKey
    val packageName: String,

    val appName: String,

    /** Comfortable daily usage limit in whole minutes. */
    val dailyLimitMinutes: Int
)
