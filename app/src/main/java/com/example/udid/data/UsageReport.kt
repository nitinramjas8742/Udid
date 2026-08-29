package com.example.udid.data

/**
 * A comparison of the currently viewed period against the immediately previous
 * equivalent period (today vs yesterday, this week vs last week, this month vs
 * last month). Produced by [ReportRepository]; null when there is no usable
 * previous-period data.
 */
data class PeriodComparison(
    /** Total screen time of the current (on-screen) period, in milliseconds. */
    val currentTotalMs: Long,

    /** Total screen time of the previous period, in milliseconds. */
    val previousTotalMs: Long,

    /** Previous period's range, for labeling (e.g. "Last week"). */
    val previousStartMs: Long,
    val previousEndMs: Long,

    /**
     * Signed percentage change of the current vs previous period.
     * Positive = up (more screen time), negative = down (less). Null when the
     * previous period had no data at all, so the UI can show a friendly
     * "not enough data yet" instead of a divide-by-zero or misleading number.
     */
    val percentChange: Double?
)

/**
 * In-memory report (NOT a Room entity — never stored in the database).
 *
 * Produced on demand by [ReportRepository] for a given date range.
 *
 * Durations are kept in **milliseconds** (unclipped totals from the DAO).
 * Whole seconds are derived only when formatting for the UI, so that all
 * periods aggregate from the same exact stored values.
 */
data class UsageReport(
    val startTime: Long,
    val endTime: Long,
    val totalScreenTimeMs: Long,
    val perAppBreakdown: List<AppUsageRow>,
    val mostUsedApp: MostUsedRow?,
    val openCountByApp: List<AppUsageRow>,

    /**
     * Comparison against the previous equivalent period. Null when unavailable
     * (e.g. no previous data yet). The UI hides/softens the section when null.
     */
    val comparison: PeriodComparison? = null,

    /**
     * Simple average of daily MPI scores across this report's period.
     * Null when there is no MPI data in the range (e.g. the period is
     * entirely before MPI was set up, or no distracting apps were configured).
     * Rounded to a whole number, consistent with daily MPI display.
     */
    val averageMpi: Int? = null
)
