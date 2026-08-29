package com.example.udid.mpi

/**
 * Pure Kotlin data classes for the MPI (Mental Peace Index) calculation.
 *
 * These have ZERO dependency on Room, Android SDK, or Compose — they are
 * plain data carriers so the formula can be unit-tested in isolation on
 * the JVM without any instrumentation.
 *
 * ## How the rolling average works (simple explanation)
 *
 * Instead of showing today's raw score alone (which could swing wildly —
 * e.g. 85 → 20 in one bad day), we blend it with recent history:
 *
 *   smoothed = today×0.70 + yesterday×0.20 + two-days-ago×0.10
 *
 * Missing history days are treated as 100 (the best possible score), so
 * a single bad day after several good days doesn't crater the displayed
 * score — it gets softened by the recent good performance. This is the
 * "trend/smoothing" factor (~15% of the overall scoring weight).
 *
 * A small bonus (up to +5 points) is also added when today's raw score
 * is better than the rolling average, rewarding the *direction* of change
 * even if the absolute score is still below ideal.
 *
 * ## Future extension: weekday vs weekend limits
 *
 * [AppLimit.dailyLimitMinutes] is a single value today. A future
 * enhancement could split this into `weekdayLimitMinutes` and
 * `weekendLimitMinutes` so users can set different comfort thresholds
 * for workdays vs weekends. The per-app breakdown structure already
 * carries the package name, so swapping in day-type-aware limits only
 * requires changing [AppLimit] and the caller that builds [MpiInput].
 */

// ── Inputs ────────────────────────────────────────────────────────────────

/**
 * One app's actual usage for the current day.
 *
 * All durations are in **milliseconds** to match the Room/DAO convention
 * used everywhere else in the app. The calculator converts internally.
 */
data class AppUsageToday(
    val packageName: String,
    val appName: String,
    /** Total foreground duration across all sessions, in ms. */
    val durationMs: Long,
    /** How many separate open→close sessions occurred. */
    val sessionCount: Int,
    /** Duration that fell within the late-night window, in ms. */
    val lateNightDurationMs: Long
)

/**
 * A user-configured daily limit for one distracting app.
 *
 * This mirrors [com.example.udid.data.DistractingAppConfig] but without
 * Room annotations, keeping the calculator decoupled from the data layer.
 */
data class AppLimit(
    val packageName: String,
    val dailyLimitMinutes: Int
)

/**
 * A single day's MPI snapshot from recent history, used for the rolling
 * weighted average that smooths the displayed score.
 */
data class MpiHistoryEntry(
    /** Human-readable label (e.g. "2026-08-28") — informational only. */
    val dateLabel: String,
    /** The final (already-smoothed) MPI score for that day, 0–100. */
    val score: Int
)

/**
 * Everything the calculator needs to produce today's MPI score.
 *
 * @param todayPerApp Per-app usage for the current day.
 * @param limits      The user's configured limits for each distracting app.
 *                    Only apps present here are evaluated — apps the user
 *                    hasn't marked as distracting are simply ignored.
 * @param history     Recent daily MPI snapshots (most recent first, up to
 *                    2 entries). Used for trend smoothing.
 */
data class MpiInput(
    val todayPerApp: List<AppUsageToday>,
    val limits: List<AppLimit>,
    val history: List<MpiHistoryEntry> = emptyList()
)

// ── Outputs ───────────────────────────────────────────────────────────────

/**
 * Per-app contribution to the MPI penalty.
 *
 * Returned as part of [MpiResult] so the UI can later show explanations
 * like "Main reason: Instagram exceeded your limit by 45 minutes."
 */
data class MpiAppBreakdown(
    val packageName: String,
    val appName: String,
    /** How many minutes this app exceeded its configured limit (0 if under). */
    val overageMinutes: Int,
    /** This app's share of the total duration penalty, 0.0–1.0. */
    val durationPenaltyShare: Float,
    /** This app's share of the total frequency penalty, 0.0–1.0. */
    val frequencyPenaltyShare: Float,
    /** This app's share of the total late-night penalty, 0.0–1.0. */
    val lateNightPenaltyShare: Float
)

/**
 * The full result of an MPI calculation.
 *
 * @param score          Final MPI score, 0–100, whole number. Higher = better.
 * @param rawScore       Score before trend smoothing (for debugging/comparison).
 * @param smoothedScore  Score after rolling-average smoothing but before trend bonus.
 * @param dominantReason Human-readable summary of the biggest penalty contributor,
 *                       or null when there is no penalty (score = 100).
 * @param perApp         Per-app breakdown for detailed UI display.
 */
data class MpiResult(
    val score: Int,
    val rawScore: Int,
    val smoothedScore: Int,
    val dominantReason: String?,
    val perApp: List<MpiAppBreakdown>
)
