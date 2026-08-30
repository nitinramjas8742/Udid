package com.example.udid.data

import org.json.JSONObject
import java.util.Calendar

/**
 * Builds [UsageReport] objects for arbitrary date ranges, including a
 * period-over-period comparison against the immediately previous equivalent
 * period (today vs yesterday, this week vs last week, this month vs last month).
 *
 * # Data-source rule (single source of truth for WHERE each number comes from)
 *
 * Raw `sessions` rows are purged after [RETENTION_DAYS]. `daily_summary` keeps
 * one total per calendar day **indefinitely**. To keep this decision in exactly
 * one place:
 *
 *  - The **current** period (the one on screen, containing "now") is always read
 *    from raw `sessions` — it is by definition recent, so nothing has been purged.
 *
 *  - The **previous** period is read from raw `sessions` if and only if its
 *    entire range starts at or after the retention cutoff
 *    ([isCoveredByRawSessions]). Otherwise (partially or fully older than the
 *    cutoff) it is read from `daily_summary`, because the raw rows no longer
 *    exist there. This is exactly what lets a monthly comparison reach back a
 *    full month even though only the last 30 days of raw sessions are kept.
 *
 * All database reads run off the main thread (Room's suspend functions), so the
 * caller (ReportViewModel) never blocks the UI thread.
 */
class ReportRepository(
    private val sessionDao: SessionDao,
    private val dailySummaryDao: DailySummaryDao
) {

    /** Report covering a single calendar day that contains dateEpochMillis. */
    suspend fun getDailyReport(dateEpochMillis: Long): UsageReport {
        val start = startOfDay(dateEpochMillis)
        val end = start + DAY_MILLIS
        return buildReport(start, end, previousRange = { previousStart ->
            previousStart - DAY_MILLIS to previousStart
        })
    }

    /** Report covering the 7-day period starting at weekStartEpochMillis. */
    suspend fun getWeeklyReport(weekStartEpochMillis: Long): UsageReport {
        val start = startOfDay(weekStartEpochMillis)
        val end = start + 7 * DAY_MILLIS
        return buildReport(start, end, previousRange = { previousStart ->
            previousStart - 7 * DAY_MILLIS to previousStart
        })
    }

    /** Report covering the calendar month containing monthStartEpochMillis. */
    suspend fun getMonthlyReport(monthStartEpochMillis: Long): UsageReport {
        val cal = Calendar.getInstance().apply {
            timeInMillis = monthStartEpochMillis
            set(Calendar.DAY_OF_MONTH, 1)
        }
        val start = startOfDay(cal.timeInMillis)
        val end = start + monthLengthMillis(start)
        return buildReport(start, end) { previousStart ->
            val prev = Calendar.getInstance().apply { timeInMillis = previousStart }
            prev.add(Calendar.MONTH, -1)
            val prevStart = startOfDay(prev.timeInMillis)
            val prevEnd = prevStart + monthLengthMillis(prevStart)
            prevStart to prevEnd
        }
    }

    /**
     * Convenience: start of the current calendar week (Sunday), or current
     * month, for quick callers.
     */
    fun startOfWeek(referenceMillis: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = referenceMillis }
        cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
        return startOfDay(cal.timeInMillis)
    }

    fun startOfMonth(referenceMillis: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = referenceMillis
            set(Calendar.DAY_OF_MONTH, 1)
        }
        return startOfDay(cal.timeInMillis)
    }

    /**
     * Simple average of daily MPI scores in [startDateEpochMillis, endDateEpochMillis).
     *
     * Returns null when there is no MPI data at all in the range (e.g. the
     * period is entirely before MPI was set up, or no distracting apps were
     * ever configured). This is the reusable building block for notifications
     * ("This week's MPI: 81/100") and the report UI.
     */
    suspend fun getAverageMpi(
        startDateEpochMillis: Long,
        endDateEpochMillis: Long
    ): Int? {
        val avg = dailySummaryDao.getAverageMpiForRange(
            startDateEpochMillis,
            endDateEpochMillis
        ) ?: return null
        return kotlin.math.round(avg).toInt()
    }

    private suspend fun buildReport(
        start: Long,
        end: Long,
        previousRange: (Long) -> Pair<Long, Long>
    ): UsageReport {
        val report = buildCurrentReport(start, end)

        val comparison = buildComparison(
            currentTotalMs = report.totalScreenTimeMs,
            currentStart = start,
            previousRange = previousRange
        )

        val avgMpi = getAverageMpi(start, end)

        return report.copy(comparison = comparison, averageMpi = avgMpi)
    }

    private suspend fun buildCurrentReport(start: Long, end: Long): UsageReport {
        return if (isCoveredByRawSessions(start)) {
            // Raw sessions are available — use them directly.
            val totalRow = sessionDao.totalScreenTime(start, end)
            val perApp = sessionDao.screenTimeByApp(start, end)
            val mostUsed = sessionDao.mostUsedApp(start, end)
            val openCounts = sessionDao.openCountByApp(start, end)
            UsageReport(
                startTime = start,
                endTime = end,
                totalScreenTimeMs = totalRow.totalMs,
                perAppBreakdown = perApp,
                mostUsedApp = mostUsed,
                openCountByApp = openCounts
            )
        } else {
            // Raw sessions purged — fall back to daily_summary for each day in range.
            val summaries = dailySummaryDao.summariesInRange(start, end)
            val totalMs = summaries.sumOf { it.totalScreenTimeMs }

            // Merge per-app JSON from each day in the range.
            val mergedTime = mutableMapOf<String, Long>()
            val mergedOpen = mutableMapOf<String, Int>()
            for (summary in summaries) {
                try {
                    val timeJson = JSONObject(summary.perAppTimeJson)
                    for (key in timeJson.keys()) {
                        mergedTime[key] = (mergedTime[key] ?: 0L) + timeJson.getLong(key)
                    }
                } catch (_: Exception) { /* skip malformed JSON */ }
                try {
                    val openJson = JSONObject(summary.perAppOpenCountJson)
                    for (key in openJson.keys()) {
                        mergedOpen[key] = (mergedOpen[key] ?: 0) + openJson.getInt(key)
                    }
                } catch (_: Exception) { /* skip malformed JSON */ }
            }

            val perApp = mergedTime.entries
                .sortedByDescending { it.value }
                .map { (pkg, durationMs) ->
                    AppUsageRow(
                        packageName = pkg,
                        appName = pkg.substringAfterLast('.'),
                        durationMs = durationMs,
                        openCount = mergedOpen[pkg] ?: 0
                    )
                }

            val mostUsed = perApp.maxByOrNull { it.durationMs }

            UsageReport(
                startTime = start,
                endTime = end,
                totalScreenTimeMs = totalMs,
                perAppBreakdown = perApp,
                mostUsedApp = mostUsed?.let {
                    MostUsedRow(it.packageName, it.appName, it.durationMs)
                },
                openCountByApp = perApp.sortedByDescending { it.openCount }
            )
        }
    }

    private suspend fun buildComparison(
        currentTotalMs: Long,
        currentStart: Long,
        previousRange: (Long) -> Pair<Long, Long>
    ): PeriodComparison? {
        val (prevStart, prevEnd) = previousRange(currentStart)
        val prevTotalMs = totalForRange(prevStart, prevEnd)

        // No prior usage to compare against -> no meaningful percentage.
        if (prevTotalMs <= 0L) return null

        val percent = ((currentTotalMs - prevTotalMs).toDouble() / prevTotalMs) * 100.0

        return PeriodComparison(
            currentTotalMs = currentTotalMs,
            previousTotalMs = prevTotalMs,
            previousStartMs = prevStart,
            previousEndMs = prevEnd,
            percentChange = percent
        )
    }

    /** Source-selected total for a range (see class docs for the rule). */
    private suspend fun totalForRange(start: Long, end: Long): Long {
        return if (isCoveredByRawSessions(start)) {
            sessionDao.totalScreenTime(start, end).totalMs
        } else {
            dailySummaryDao.totalScreenTime(start, end).totalMs
        }
    }

    /**
     * A range is covered by raw `sessions` when its start is at or after the
     * retention cutoff. Because purge deletes rows with `startedAt < cutoff`,
     * any range that begins on/after the cutoff is guaranteed fully present;
     * any range that begins before it is partially (or fully) purged and must
     * fall back to [DailySummaryDao].
     */
    private fun isCoveredByRawSessions(rangeStart: Long): Boolean {
        val cutoff = System.currentTimeMillis() - RETENTION_DAYS * DAY_MILLIS
        return rangeStart >= cutoff
    }

    private fun startOfDay(epochMillis: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = epochMillis }
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun monthLengthMillis(monthStart: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = monthStart }
        val firstDay = cal.timeInMillis
        cal.add(Calendar.MONTH, 1)
        return cal.timeInMillis - firstDay
    }

    companion object {
        /** How many days of raw `sessions` are retained before purging. */
        const val RETENTION_DAYS = 30

        private const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}
