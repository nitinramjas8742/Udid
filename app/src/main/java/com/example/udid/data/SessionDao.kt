package com.example.udid.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Immutable result row: total usage of one app within a range.
 * Used by the report queries. `openCount` counts how many separate sessions
 * that app had in the range (a session counts if it *overlaps* the range).
 *
 * Durations are returned in **milliseconds** so that clipping every session
 * to the period [start, end) is exact integer arithmetic. This guarantees a
 * daily total is a precise subset of its weekly/monthly totals — no per-second
 * rounding drift that would make the numbers feel inconsistent. Callers round
 * to whole seconds only when formatting for display.
 */
data class AppUsageRow(
    val packageName: String,
    val appName: String,
    val durationMs: Long,
    val openCount: Int
)

/**
 * A single scalar aggregate (sum of overlapping milliseconds in a range).
 */
data class TotalRow(
    val totalMs: Long
)

/**
 * Row holding the single most-used app within a range.
 */
data class MostUsedRow(
    val packageName: String,
    val appName: String,
    val totalMs: Long
)

/**
 * DAO for the `sessions` table and all report aggregations.
 *
 * All queries are written in SQL but return plain Kotlin data classes.
 * They are `suspend` so Room runs them off the main thread automatically.
 *
 * Every report query filters by **overlap** with the requested range:
 *   overlap = MAX(0, MIN(endedAt, :endTime) - MAX(startedAt, :startTime))
 * Sessions that straddle a period boundary are clipped to the part inside the
 * period, so each instant of usage is counted for exactly one period and the
 * day/week/month numbers are always internally consistent.
 */
@Dao
interface SessionDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(session: SessionEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(sessions: List<SessionEntity>)

    /** a. Total screen time (sum of per-session overlap) in the range. */
    @Query(
        "SELECT COALESCE(SUM(MAX(0, MIN(endedAt, :endTime) - MAX(startedAt, :startTime))), 0) AS totalMs " +
            "FROM sessions " +
            "WHERE startedAt < :endTime AND endedAt > :startTime"
    )
    suspend fun totalScreenTime(startTime: Long, endTime: Long): TotalRow

    /** b. Screen time grouped by app, sorted descending. */
    @Query(
        "SELECT packageName, " +
            "MAX(appName) AS appName, " +
            "SUM(MAX(0, MIN(endedAt, :endTime) - MAX(startedAt, :startTime))) AS durationMs, " +
            "COUNT(*) AS openCount " +
            "FROM sessions " +
            "WHERE startedAt < :endTime AND endedAt > :startTime " +
            "GROUP BY packageName " +
            "ORDER BY durationMs DESC"
    )
    suspend fun screenTimeByApp(startTime: Long, endTime: Long): List<AppUsageRow>

    /** c. Open count per app (number of overlapping sessions per app). */
    @Query(
        "SELECT packageName, " +
            "MAX(appName) AS appName, " +
            "SUM(MAX(0, MIN(endedAt, :endTime) - MAX(startedAt, :startTime))) AS durationMs, " +
            "COUNT(*) AS openCount " +
            "FROM sessions " +
            "WHERE startedAt < :endTime AND endedAt > :startTime " +
            "GROUP BY packageName " +
            "ORDER BY openCount DESC"
    )
    suspend fun openCountByApp(startTime: Long, endTime: Long): List<AppUsageRow>

    /** d. The single most-used app in the range. */
    @Query(
        "SELECT packageName, MAX(appName) AS appName, " +
            "SUM(MAX(0, MIN(endedAt, :endTime) - MAX(startedAt, :startTime))) AS totalMs " +
            "FROM sessions " +
            "WHERE startedAt < :endTime AND endedAt > :startTime " +
            "GROUP BY packageName " +
            "ORDER BY totalMs DESC " +
            "LIMIT 1"
    )
    suspend fun mostUsedApp(startTime: Long, endTime: Long): MostUsedRow?

    /**
     * Deletes rows older than the given epoch millis.
     * Used for retention (e.g. keep only 31+ days of data).
     */
    @Query("DELETE FROM sessions WHERE startedAt < :beforeEpochMillis")
    suspend fun deleteOlderThan(beforeEpochMillis: Long): Int

    /**
     * All sessions overlapping [startMs, endMs), returned as raw entities.
     * Used by [com.example.udid.mpi.MpiScoreCalculator] to compute
     * per-app late-night duration for the MPI formula.
     */
    @Query(
        "SELECT * FROM sessions " +
            "WHERE startedAt < :endMs AND endedAt > :startMs " +
            "ORDER BY startedAt ASC"
    )
    suspend fun sessionsForDay(startMs: Long, endMs: Long): List<SessionEntity>
}
