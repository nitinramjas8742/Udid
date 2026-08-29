package com.example.udid.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row summarizes a single calendar day of usage, kept in the database
 * indefinitely.
 *
 * This is the **long-term history** store. Raw [SessionEntity] rows are purged
 * after [ReportRepository.RETENTION_DAYS] days, but a [DailySummary] is written
 * for every day that sessions were ever loaded — so reports can still answer
 * "what was my screen time in a previous period that is now older than the raw
 * retention window?" The comparison feature uses this as its fallback source.
 *
 * Durations are stored in **milliseconds** (matching [SessionDao]) and only
 * rounded to whole seconds at display time, so aggregated values stay exact.
 *
 * The per-app breakdowns are stored as JSON strings (via android's org.json)
 * purely to satisfy the retention requirement that each day keep its per-app
 * times and open counts for export later (Step 4). The comparison logic itself
 * only reads [totalScreenTimeMs].
 */
@Entity(tableName = "daily_summary")
data class DailySummaryEntity(
    @PrimaryKey
    val dateMillis: Long,

    /** Total screen time for the whole calendar day, in milliseconds. */
    val totalScreenTimeMs: Long,

    /** JSON object: { "<packageName>": durationMs, ... } for that day. */
    val perAppTimeJson: String,

    /** JSON object: { "<packageName>": openCount, ... } for that day. */
    val perAppOpenCountJson: String,

    /**
     * Mental Peace Index score for this day (0–100). Computed by
     * [com.example.udid.mpi.MpiScoreCalculator] after sessions are loaded.
     * 0 means "not yet calculated" (existing rows from before MPI was added).
     */
    val mpiScore: Int = 0
)
