package com.example.udid.mpi

import com.example.udid.data.DailySummaryDao
import com.example.udid.data.DistractingAppConfigDao
import com.example.udid.data.SessionDao
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Bridges the pure [MpiCalculator] formula with the Room database.
 *
 * Gathers real per-app usage data (duration, session count, late-night
 * duration) from the `sessions` table, merges it with the user's
 * configured limits from `distracting_app_config`, and feeds everything
 * into [MpiCalculator.calculateMpi]. The resulting score is stored back
 * into `daily_summary.mpiScore` for the given day.
 *
 * Late-night duration is computed from raw [com.example.udid.data.SessionEntity]
 * rows rather than from `daily_summary` JSON, because the JSON only stores
 * total per-app duration — not the time-of-day distribution needed to
 * calculate the late-night penalty.
 */
class MpiScoreCalculator(
    private val sessionDao: SessionDao,
    private val dailySummaryDao: DailySummaryDao,
    private val distractingAppConfigDao: DistractingAppConfigDao
) {

    /**
     * Calculate and store the MPI score for [dateEpochMillis] (start of day).
     *
     * This is the main entry point called after sessions are loaded.
     * If no distracting apps are configured, the score is 100 (nothing to
     * penalise) and is stored like any other score.
     */
    suspend fun calculateAndStore(dateEpochMillis: Long): MpiResult {
        val startMs = startOfDay(dateEpochMillis)
        val endMs = startMs + DAY_MILLIS

        val result = calculateForDay(startMs, endMs)
        dailySummaryDao.updateMpiScore(startMs, result.score)
        return result
    }

    /**
     * Calculate the MPI score for today and store it.
     * Convenience wrapper around [calculateAndStore].
     */
    suspend fun calculateAndStoreToday(): MpiResult {
        return calculateAndStore(System.currentTimeMillis())
    }

    /**
     * Calculate the MPI score for a given day without storing it.
     * Useful for previewing or testing.
     */
    suspend fun calculateForDay(startMs: Long, endMs: Long): MpiResult {
        // 1. Get configured distracting app limits.
        val configs = distractingAppConfigDao.getAll()
        val limits = configs.map {
            AppLimit(
                packageName = it.packageName,
                dailyLimitMinutes = it.dailyLimitMinutes
            )
        }

        // If no apps configured, calculator returns 100 with null reason.
        if (limits.isEmpty()) {
            return MpiCalculator.calculateMpi(
                MpiInput(
                    todayPerApp = emptyList(),
                    limits = emptyList(),
                    history = emptyList()
                )
            )
        }

        // 2. Gather recent MPI history for trend smoothing (last 2 days).
        val history = dailySummaryDao.getRecentMpiScores(startMs, 2)
            .map { entry ->
                val label = formatDayLabel(entry.dateMillis)
                MpiHistoryEntry(dateLabel = label, score = entry.mpiScore)
            }

        // 3. Get per-app usage for today from raw sessions.
        val sessions = sessionDao.sessionsForDay(startMs, endMs)
        val limitSet = limits.associateBy { it.packageName }

        // Aggregate per-app: duration, session count, late-night duration.
        data class AppAgg(
            val packageName: String,
            val appName: String,
            var durationMs: Long = 0,
            var sessionCount: Int = 0,
            var lateNightDurationMs: Long = 0
        )

        val aggMap = mutableMapOf<String, AppAgg>()

        for (session in sessions) {
            if (session.packageName !in limitSet) continue

            val agg = aggMap.getOrPut(session.packageName) {
                AppAgg(
                    packageName = session.packageName,
                    appName = session.appName
                )
            }

            // Clip session to [startMs, endMs) for duration.
            val clippedStart = maxOf(session.startedAt, startMs)
            val clippedEnd = minOf(session.endedAt, endMs)
            val duration = maxOf(0L, clippedEnd - clippedStart)
            agg.durationMs += duration
            agg.sessionCount += 1

            // Clip session to late-night window for late-night duration.
            agg.lateNightDurationMs += clipToLateNight(
                session.startedAt, session.endedAt, startMs
            )
        }

        val todayPerApp = aggMap.values.map { agg ->
            AppUsageToday(
                packageName = agg.packageName,
                appName = agg.appName,
                durationMs = agg.durationMs,
                sessionCount = agg.sessionCount,
                lateNightDurationMs = agg.lateNightDurationMs
            )
        }

        val input = MpiInput(
            todayPerApp = todayPerApp,
            limits = limits,
            history = history
        )

        return MpiCalculator.calculateMpi(input)
    }

    /**
     * Fetch the stored MPI scores for the last [days] days ending today.
     * Used by the 7-day trend display. Returns entries newest-first.
     * Only includes days that have a non-zero score (i.e. calculated).
     */
    suspend fun getRecentScores(days: Int = 7): List<MpiScoreRow> {
        val tomorrowStart = startOfDay(System.currentTimeMillis()) + DAY_MILLIS
        return dailySummaryDao.getRecentMpiScores(tomorrowStart, days)
    }

    /**
     * Get yesterday's MPI score for comparison, or null if not available.
     */
    suspend fun getYesterdayScore(): Int? {
        val todayStart = startOfDay(System.currentTimeMillis())
        val yesterdayStart = todayStart - DAY_MILLIS
        val summary = dailySummaryDao.getByDateMillis(yesterdayStart)
        return if (summary != null && summary.mpiScore > 0) summary.mpiScore else null
    }

    // ── Late-night clipping ───────────────────────────────────────────────

    /**
     * Clip a session [sessionStart, sessionEnd) to the late-night window
     * within a given calendar day and return the overlap in milliseconds.
     *
     * The late-night window is [LATE_NIGHT_START_HOUR:00, next day LATE_NIGHT_END_HOUR:00).
     * For the given day, that means [dayStart + 23h, dayStart + 24h + 5h).
     *
     * Sessions that span midnight are handled correctly: only the portion
     * that falls within the late-night window counts.
     */
    private fun clipToLateNight(
        sessionStart: Long,
        sessionEnd: Long,
        dayStart: Long
    ): Long {
        // Late-night window for this day: 23:00 dayStart .. 05:00 next day
        val lnStart = dayStart + MpiCalculator.LATE_NIGHT_START_HOUR.toLong() * 3600_000
        val lnEnd = dayStart + DAY_MILLIS +
            MpiCalculator.LATE_NIGHT_END_HOUR.toLong() * 3600_000

        val overlapStart = maxOf(sessionStart, lnStart)
        val overlapEnd = minOf(sessionEnd, lnEnd)
        return maxOf(0L, overlapEnd - overlapStart)
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun startOfDay(epochMillis: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = epochMillis }
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun formatDayLabel(epochMillis: Long): String {
        val fmt = SimpleDateFormat("EEE", Locale.getDefault())
        return fmt.format(java.util.Date(epochMillis))
    }

    private companion object {
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}

/** Alias for the DAO row type used in MPI trend queries. */
typealias MpiScoreRow = com.example.udid.data.MpiScoreRow
