package com.example.udid.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** A single scalar: sum of one or more [DailySummaryEntity] totals. */
data class DailyTotalRow(
    val totalMs: Long
)

/**
 * One row for MPI trend: the date and its stored score.
 * Used by the 7-day trend display in the MPI card.
 */
data class MpiScoreRow(
    val dateMillis: Long,
    val mpiScore: Int
)

/**
 * DAO for the `daily_summary` table.
 *
 * Rows are keyed by calendar-day start millis and written once per day.
 * [upsert] replaces an existing row for the same day, so reloading the same
 * day's data never creates duplicates and always reflects the latest values.
 */
@Dao
interface DailySummaryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(summary: DailySummaryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(summaries: List<DailySummaryEntity>)

    /** Total screen time across all stored days within [startTime, endTime). */
    @Query(
        "SELECT COALESCE(SUM(totalScreenTimeMs), 0) AS totalMs " +
            "FROM daily_summary " +
            "WHERE dateMillis >= :startTime AND dateMillis < :endTime"
    )
    suspend fun totalScreenTime(startTime: Long, endTime: Long): DailyTotalRow

    /** Get the full summary for a specific calendar day, or null if none. */
    @Query("SELECT * FROM daily_summary WHERE dateMillis = :dateMillis LIMIT 1")
    suspend fun getByDateMillis(dateMillis: Long): DailySummaryEntity?

    /** Update only the MPI score column for a specific day. */
    @Query("UPDATE daily_summary SET mpiScore = :mpiScore WHERE dateMillis = :dateMillis")
    suspend fun updateMpiScore(dateMillis: Long, mpiScore: Int)

    /**
     * Fetch the last [count] daily summaries ending at [beforeDateMillis],
     * ordered newest-first. Used to build the 7-day MPI trend display.
     */
    @Query(
        "SELECT dateMillis, mpiScore FROM daily_summary " +
            "WHERE dateMillis < :beforeDateMillis AND mpiScore > 0 " +
            "ORDER BY dateMillis DESC LIMIT :count"
    )
    suspend fun getRecentMpiScores(beforeDateMillis: Long, count: Int): List<MpiScoreRow>

    /**
     * Simple average of all non-zero MPI scores in [startTime, endTime).
     *
     * mpiScore 0 means "not yet calculated" — it is excluded from the
     * average so that pre-MPI days don't drag the number down. Returns
     * null when there are no non-zero scores in the range.
     */
    @Query(
        "SELECT AVG(mpiScore) FROM daily_summary " +
            "WHERE dateMillis >= :startTime AND dateMillis < :endTime " +
            "AND mpiScore > 0"
    )
    suspend fun getAverageMpiForRange(startTime: Long, endTime: Long): Double?
}
