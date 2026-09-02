package com.example.udid.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * DAO for the `session_notes` table.
 *
 * Notes are linked to sessions via a composite string key rather than
 * a foreign key, because the UI model [com.example.udid.usage.AppSession]
 * does not carry the Room auto-generated session id.
 */
@Dao
interface SessionNoteDao {

    /**
     * Insert or update a note. If a note with the same [SessionNote.sessionKey]
     * already exists, it is replaced (conflict strategy = REPLACE).
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(note: SessionNote)

    /** Get the note for a single session, or null if none exists. */
    @Query("SELECT * FROM session_notes WHERE sessionKey = :sessionKey LIMIT 1")
    suspend fun getNoteForSession(sessionKey: String): SessionNote?

    /**
     * All notes within [startMs, endMs), newest first.
     * Used by the "Notes only" filter in the Activity tab.
     */
    @Query(
        "SELECT * FROM session_notes " +
            "WHERE createdAt >= :startMs AND createdAt < :endMs " +
            "ORDER BY createdAt DESC"
    )
    suspend fun getNotesInRange(startMs: Long, endMs: Long): List<SessionNote>

    /** Delete notes older than [cutoffMillis] (retention purge). */
    @Query("DELETE FROM session_notes WHERE createdAt < :cutoffMillis")
    suspend fun deleteOlderThan(cutoffMillis: Long)

    /** Delete a specific note by its session key. */
    @Query("DELETE FROM session_notes WHERE sessionKey = :sessionKey")
    suspend fun deleteNote(sessionKey: String)
}
