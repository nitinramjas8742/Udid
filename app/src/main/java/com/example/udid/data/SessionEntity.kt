package com.example.udid.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity representing a single app usage session.
 *
 * Every time the user opens an app (and for how long), we store one row here.
 * Reports for any period are computed on the fly from this table — there is
 * no separate daily/weekly/monthly table.
 */
@Entity(
    tableName = "sessions",
    indices = [
        Index(value = ["startedAt"]),
        Index(value = ["packageName"]),
        Index(
            value = ["packageName", "startedAt", "endedAt"],
            unique = true
        )
    ]
)
data class SessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val packageName: String,
    val appName: String,

    /** Epoch millis when the session started. */
    val startedAt: Long,

    /** Epoch millis when the session ended. */
    val endedAt: Long,

    /** Session length in seconds. */
    val durationSec: Long
)
