package com.example.udid.mpi

import kotlin.math.min
import kotlin.math.round

/**
 * Pure-function MPI (Mental Peace Index) calculator.
 *
 * Zero dependency on Room, Android SDK, or Compose. Takes plain data
 * classes ([MpiInput]) and returns a plain result ([MpiResult]).
 *
 * ## Scoring formula
 *
 * The score starts at 100 and subtracts three penalty components, each
 * targeting a different dimension of "unhealthy" usage:
 *
 * | Factor          | Weight | What it measures                                      |
 * |-----------------|--------|-------------------------------------------------------|
 * | Duration excess | ~50 %  | How far actual usage exceeded each app's personal limit |
 * | Frequency       | ~20 %  | Number of separate sessions (compulsive checking)      |
 * | Late-night      | ~15 %  | Usage within the configurable late-night window         |
 * | Trend/smoothing | ~15 %  | Rolling weighted average + improvement bonus            |
 *
 * ### Duration penalty (0–50 pts)
 *
 * For each distracting app, compute `ratio = actualMs / limitMs`.
 * If `ratio > 1` the app exceeded its limit; the overage beyond 1.0 is
 * summed across all apps, multiplied by [DURATION_PENALTY_SCALE], and
 * clamped to 50. This means:
 *   - One app at 2× its limit → penalty ≈ 25 pts
 *   - One app at 3× its limit → penalty = 50 pts (max)
 *   - Two apps each at 1.5×   → penalty ≈ 25 pts total
 *
 * ### Frequency penalty (0–20 pts)
 *
 * Each session (open) beyond the first adds [FREQ_PENALTY_PER_SESSION]
 * points, clamped to 20. Twelve sessions → 6 pts, thirty-four → 20 pts.
 * The intuition: many short sessions signal compulsive checking, which
 * is worse than one long session of the same total duration.
 *
 * ### Late-night penalty (0–15 pts)
 *
 * Usage between [LATE_NIGHT_START_HOUR]–[LATE_NIGHT_END_HOUR] (default
 * 23:00–05:00) is penalised proportionally to a 2-hour reference window.
 * The window hours are a configurable constant — not hardcoded inline —
 * so they can be adjusted later without touching the formula.
 *
 * ### Trend smoothing
 *
 * The raw score (100 − penalties) is blended with recent history via a
 * weighted rolling average:
 *
 *   smoothed = raw×0.70 + yesterday×0.20 + two-days-ago×0.10
 *
 * Missing history days default to 100 (best possible), so a single bad
 * day after several good days doesn't cause a jarring swing.
 *
 * A bonus of up to +5 pts is added when today's raw score exceeds the
 * rolling average, rewarding *improvement* — the direction of change,
 * not just the absolute state.
 *
 * ### No distracting apps
 *
 * If the user hasn't configured any distracting apps, the score is 100
 * (there is nothing to penalise). [MpiResult.dominantReason] is null
 * and [MpiResult.perApp] is empty in this case.
 */
object MpiCalculator {

    // ── Configurable constants ────────────────────────────────────────────

    /** Maximum penalty for duration excess (pts). */
    const val MAX_DURATION_PENALTY = 50

    /** Maximum penalty for session frequency (pts). */
    const val MAX_FREQUENCY_PENALTY = 20

    /** Maximum penalty for late-night usage (pts). */
    const val MAX_LATENIGHT_PENALTY = 15

    /**
     * Scaling factor for duration excess.
     * penalty = clamp(excessRatio × SCALE, 0, MAX_DURATION_PENALTY)
     * where excessRatio = sum over apps of max(0, actualMs/limitMs − 1).
     *
     * At SCALE = 25: one app at 2× limit → 25 pts, at 3× → 50 pts (max).
     */
    const val DURATION_PENALTY_SCALE = 25

    /** Penalty per session beyond the first (pts). */
    const val FREQ_PENALTY_PER_SESSION = 0.5

    /** Start hour (inclusive) of the late-night window, 0–23. */
    const val LATE_NIGHT_START_HOUR = 23

    /** End hour (exclusive) of the late-night window, 0–23. */
    const val LATE_NIGHT_END_HOUR = 5

    /**
     * Length of the late-night reference window in hours, used to
     * normalise the late-night penalty. Default: 6 h (23:00–05:00).
     */
    const val LATENIGHT_REFERENCE_HOURS = 6.0

    /** Maximum bonus for positive trend (pts). */
    const val MAX_TREND_BONUS = 5

    // ── Rolling average weights ───────────────────────────────────────────

    /**
     * Weight for today's score in the rolling average.
     * The three weights must sum to 1.0.
     */
    const val WEIGHT_TODAY = 0.70

    /** Weight for yesterday's score. */
    const val WEIGHT_YESTERDAY = 0.20

    /** Weight for the day before yesterday. */
    const val WEIGHT_TWO_DAYS_AGO = 0.10

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Calculate today's MPI score from per-app usage, configured limits,
     * and recent score history.
     *
     * This is a **pure function** — no side effects, no database reads,
     * no Android SDK calls. Safe to call from unit tests on the JVM.
     */
    fun calculateMpi(input: MpiInput): MpiResult {
        // If no distracting apps are configured, there is nothing to penalise.
        if (input.limits.isEmpty()) {
            return MpiResult(
                score = 100,
                rawScore = 100,
                smoothedScore = 100,
                dominantReason = null,
                perApp = emptyList()
            )
        }

        val limitMap = input.limits.associateBy { it.packageName }

        // ── Per-app penalties ─────────────────────────────────────────────

        var totalDurationPenalty = 0.0
        var totalFrequencyPenalty = 0.0
        var totalLateNightPenalty = 0.0

        // Track which app contributed most to each penalty category.
        var maxDurationApp = ""
        var maxDurationAppVal = 0.0
        var maxFreqApp = ""
        var maxFreqAppVal = 0.0
        var maxLateNightApp = ""
        var maxLateNightAppVal = 0.0

        val breakdowns = mutableListOf<MpiAppBreakdown>()

        for (usage in input.todayPerApp) {
            val limit = limitMap[usage.packageName] ?: continue
            val limitMs = limit.dailyLimitMinutes.toLong() * 60_000

            // ── Duration ──
            val durationRatio = if (limitMs > 0) {
                usage.durationMs.toDouble() / limitMs
            } else {
                0.0
            }
            val durationExcess = maxOf(0.0, durationRatio - 1.0)
            val durationPenalty = min(
                durationExcess * DURATION_PENALTY_SCALE,
                MAX_DURATION_PENALTY.toDouble()
            )
            totalDurationPenalty += durationPenalty
            if (durationPenalty > maxDurationAppVal) {
                maxDurationAppVal = durationPenalty
                maxDurationApp = usage.appName
            }

            // ── Frequency ──
            val sessionsBeyondFirst = maxOf(0, usage.sessionCount - 1)
            val frequencyPenalty = min(
                sessionsBeyondFirst * FREQ_PENALTY_PER_SESSION,
                MAX_FREQUENCY_PENALTY.toDouble()
            )
            totalFrequencyPenalty += frequencyPenalty
            if (frequencyPenalty > maxFreqAppVal) {
                maxFreqAppVal = frequencyPenalty
                maxFreqApp = usage.appName
            }

            // ── Late-night ──
            val lateNightRatio = if (limitMs > 0) {
                usage.lateNightDurationMs.toDouble() / limitMs
            } else {
                0.0
            }
            val lateNightPenalty = min(
                lateNightRatio * MAX_LATENIGHT_PENALTY,
                MAX_LATENIGHT_PENALTY.toDouble()
            )
            totalLateNightPenalty += lateNightPenalty
            if (lateNightPenalty > maxLateNightAppVal) {
                maxLateNightAppVal = lateNightPenalty
                maxLateNightApp = usage.appName
            }

            // Per-app breakdown
            val overageMs = maxOf(0L, usage.durationMs - limitMs)
            breakdowns.add(
                MpiAppBreakdown(
                    packageName = usage.packageName,
                    appName = usage.appName,
                    overageMinutes = (overageMs / 60_000).toInt(),
                    durationPenaltyShare = 0f,   // filled in below
                    frequencyPenaltyShare = 0f,
                    lateNightPenaltyShare = 0f
                )
            )
        }

        // ── Clamp each penalty category ───────────────────────────────────

        totalDurationPenalty = min(totalDurationPenalty, MAX_DURATION_PENALTY.toDouble())
        totalFrequencyPenalty = min(totalFrequencyPenalty, MAX_FREQUENCY_PENALTY.toDouble())
        totalLateNightPenalty = min(totalLateNightPenalty, MAX_LATENIGHT_PENALTY.toDouble())

        val totalPenalty = totalDurationPenalty + totalFrequencyPenalty + totalLateNightPenalty

        // ── Fill in per-app penalty shares ────────────────────────────────

        for (i in breakdowns.indices) {
            val usage = input.todayPerApp[i]
            val limit = limitMap[usage.packageName] ?: continue
            val limitMs = limit.dailyLimitMinutes.toLong() * 60_000

            val durationRatio = if (limitMs > 0) {
                usage.durationMs.toDouble() / limitMs
            } else {
                0.0
            }
            val durationExcess = maxOf(0.0, durationRatio - 1.0)
            val durationPenalty = min(
                durationExcess * DURATION_PENALTY_SCALE,
                MAX_DURATION_PENALTY.toDouble()
            )

            val sessionsBeyondFirst = maxOf(0, usage.sessionCount - 1)
            val frequencyPenalty = min(
                sessionsBeyondFirst * FREQ_PENALTY_PER_SESSION,
                MAX_FREQUENCY_PENALTY.toDouble()
            )

            val lateNightRatio = if (limitMs > 0) {
                usage.lateNightDurationMs.toDouble() / limitMs
            } else {
                0.0
            }
            val lateNightPenalty = min(
                lateNightRatio * MAX_LATENIGHT_PENALTY,
                MAX_LATENIGHT_PENALTY.toDouble()
            )

            breakdowns[i] = breakdowns[i].copy(
                durationPenaltyShare = if (totalDurationPenalty > 0) {
                    (durationPenalty / totalDurationPenalty).toFloat()
                } else 0f,
                frequencyPenaltyShare = if (totalFrequencyPenalty > 0) {
                    (frequencyPenalty / totalFrequencyPenalty).toFloat()
                } else 0f,
                lateNightPenaltyShare = if (totalLateNightPenalty > 0) {
                    (lateNightPenalty / totalLateNightPenalty).toFloat()
                } else 0f
            )
        }

        // ── Raw score ─────────────────────────────────────────────────────

        val rawScore = clampScore(100.0 - totalPenalty)

        // ── Determine dominant reason ─────────────────────────────────────

        val dominantReason = when {
            totalPenalty == 0.0 -> null
            totalDurationPenalty >= totalFrequencyPenalty &&
                totalDurationPenalty >= totalLateNightPenalty -> {
                val app = if (maxDurationApp.isNotEmpty()) maxDurationApp else "an app"
                // Reverse the penalty formula to recover the overage in minutes:
                // penalty = (ratio - 1) × SCALE  →  overageMin = (penalty / SCALE) × limitMin
                val usage = input.todayPerApp.firstOrNull { it.appName == maxDurationApp }
                val limitMin = if (usage != null) limitMap[usage.packageName]?.dailyLimitMinutes ?: 0 else 0
                val overageMin = (maxDurationAppVal / DURATION_PENALTY_SCALE * limitMin).toInt()
                "$app exceeded your limit by ${overageMin}m"
            }
            totalFrequencyPenalty >= totalLateNightPenalty -> {
                val app = if (maxFreqApp.isNotEmpty()) maxFreqApp else "an app"
                "$app had too many separate sessions"
            }
            else -> {
                val app = if (maxLateNightApp.isNotEmpty()) maxLateNightApp else "an app"
                "$app was used late at night"
            }
        }

        // ── Trend smoothing ───────────────────────────────────────────────
        //
        // The rolling average blends today's raw score with recent history
        // so that one bad day doesn't cause a jarring swing in the displayed
        // number. Missing history days are treated as 100 (best possible),
        // which means a single bad day after several good days is softened
        // by the recent good performance.

        val historyScores = input.history.map { it.score }
        val todayWeight = WEIGHT_TODAY
        val yesterdayWeight = WEIGHT_YESTERDAY
        val twoDaysAgoWeight = WEIGHT_TWO_DAYS_AGO

        val yesterdayScore = historyScores.getOrElse(0) { 100 }
        val twoDaysAgoScore = historyScores.getOrElse(1) { 100 }

        val smoothedScore = clampScore(
            rawScore * todayWeight +
                yesterdayScore * yesterdayWeight +
                twoDaysAgoScore * twoDaysAgoWeight
        )

        // ── Trend bonus ───────────────────────────────────────────────────
        //
        // A small bonus when today improved compared to the rolling average.
        // Rewards the *direction* of change, not just absolute state.

        val rollingAverage = smoothedScore  // same as the smoothed value
        val trendBonus = if (rawScore > rollingAverage) {
            val improvement = rawScore - rollingAverage
            min(improvement * 0.5, MAX_TREND_BONUS.toDouble())
        } else {
            0.0
        }

        val finalScore = clampScore(smoothedScore + trendBonus)

        return MpiResult(
            score = finalScore,
            rawScore = rawScore,
            smoothedScore = smoothedScore,
            dominantReason = dominantReason,
            perApp = breakdowns
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** Clamp a score to 0–100 and round to the nearest whole number. */
    private fun clampScore(value: Double): Int {
        return round(value).toInt().coerceIn(0, 100)
    }
}
