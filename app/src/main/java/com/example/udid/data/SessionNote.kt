package com.example.udid.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A user-written note attached to a single usage session.
 *
 * Linked by [sessionKey] — a deterministic composite of
 * (packageName, startedAt, endedAt) that matches the in-memory
 * [com.example.udid.usage.AppSession] model without needing the
 * Room auto-generated id.
 *
 * Notes are auto-purged after [ReportRepository.RETENTION_DAYS]
 * days, same as raw session rows.
 */
@Entity(
    tableName = "session_notes",
    indices = [
        Index(value = ["sessionKey"], unique = true)
    ]
)
data class SessionNote(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Composite key: "${packageName}_${startedAt}_${endedAt}" */
    val sessionKey: String,

    val noteText: String,

    val createdAt: Long,

    val updatedAt: Long
)
