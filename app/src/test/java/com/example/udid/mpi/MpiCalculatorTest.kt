package com.example.udid.mpi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [MpiCalculator.calculateMpi].
 *
 * All tests run on the JVM — no Android instrumentation needed.
 * Each test documents the expected score and dominant reason so the
 * formula's behaviour can be sanity-checked before wiring to real data.
 */
class MpiCalculatorTest {

    // ── Test 1: Perfect day ───────────────────────────────────────────────

    @Test
    fun `perfect day - all apps within limits, score is 100`() {
        val input = MpiInput(
            todayPerApp = listOf(
                // Instagram: 30 min used, 45 min limit → under limit
                AppUsageToday(
                    packageName = "com.instagram.android",
                    appName = "Instagram",
                    durationMs = 30 * 60_000L,
                    sessionCount = 1,
                    lateNightDurationMs = 0
                ),
                // Twitter: 20 min used, 30 min limit → under limit
                AppUsageToday(
                    packageName = "com.twitter.android",
                    appName = "Twitter",
                    durationMs = 20 * 60_000L,
                    sessionCount = 1,
                    lateNightDurationMs = 0
                )
            ),
            limits = listOf(
                AppLimit("com.instagram.android", dailyLimitMinutes = 45),
                AppLimit("com.twitter.android", dailyLimitMinutes = 30)
            ),
            history = emptyList()
        )

        val result = MpiCalculator.calculateMpi(input)

        assertEquals("Score should be 100 (perfect day)", 100, result.score)
        assertEquals("Raw score should be 100", 100, result.rawScore)
        assertNull("No dominant reason on a perfect day", result.dominantReason)
        assertEquals("Two apps in breakdown", 2, result.perApp.size)
        // Both apps should have zero overage
        assertTrue(
            "Instagram overage should be 0",
            result.perApp.all { it.overageMinutes == 0 }
        )
    }

    // ── Test 2: Bad day ───────────────────────────────────────────────────

    @Test
    fun `bad day - significant excess across multiple apps, score is low`() {
        val input = MpiInput(
            todayPerApp = listOf(
                // Instagram: 120 min used, 45 min limit → 2.67× limit
                //   duration excess ratio = 1.67 → penalty = 1.67 × 25 = 41.7
                //   sessions: 12 → frequency penalty = 11 × 0.5 = 5.5
                //   late-night: 30 min → ratio = 30/45 = 0.667 → penalty = 0.667 × 15 = 10
                AppUsageToday(
                    packageName = "com.instagram.android",
                    appName = "Instagram",
                    durationMs = 120 * 60_000L,
                    sessionCount = 12,
                    lateNightDurationMs = 30 * 60_000L
                ),
                // Twitter: 150 min used, 30 min limit → 5× limit
                //   duration excess ratio = 4.0 → penalty = 4.0 × 25 = 100, clamped to 50
                //   sessions: 8 → frequency penalty = 7 × 0.5 = 3.5
                //   late-night: 0
                AppUsageToday(
                    packageName = "com.twitter.android",
                    appName = "Twitter",
                    durationMs = 150 * 60_000L,
                    sessionCount = 8,
                    lateNightDurationMs = 0
                )
            ),
            limits = listOf(
                AppLimit("com.instagram.android", dailyLimitMinutes = 45),
                AppLimit("com.twitter.android", dailyLimitMinutes = 30)
            ),
            history = emptyList()
        )

        val result = MpiCalculator.calculateMpi(input)

        // Duration penalty: min(41.7 + 100, 50) = 50
        // Frequency penalty: min(5.5 + 3.5, 20) = 9
        // Late-night penalty: min(10 + 0, 15) = 10
        // Total penalty: 50 + 9 + 10 = 69
        // Raw score: 100 - 69 = 31
        assertEquals("Raw score should be 31", 31, result.rawScore)

        // No history → defaults to 100 for missing days:
        // smoothed = round(31×0.70 + 100×0.20 + 100×0.10) = round(51.7) = 52
        // trendBonus: raw (31) not > smoothed (52) → 0
        assertEquals("Smoothed score should be 52", 52, result.smoothedScore)
        assertEquals("Final score should be 52", 52, result.score)

        assertTrue(
            "Dominant reason should mention exceeding limit",
            result.dominantReason!!.contains("exceeded your limit")
        )

        // Per-app breakdown checks
        val ig = result.perApp.first { it.packageName == "com.instagram.android" }
        assertEquals("Instagram overage: (120-45) = 75 min", 75, ig.overageMinutes)
        assertTrue("Instagram has duration penalty share > 0", ig.durationPenaltyShare > 0f)

        val tw = result.perApp.first { it.packageName == "com.twitter.android" }
        assertEquals("Twitter overage: (150-30) = 120 min", 120, tw.overageMinutes)
    }

    // ── Test 3: No distracting apps configured ────────────────────────────

    @Test
    fun `no distracting apps configured - score is 100`() {
        val input = MpiInput(
            todayPerApp = emptyList(),
            limits = emptyList(),   // nothing configured
            history = emptyList()
        )

        val result = MpiCalculator.calculateMpi(input)

        assertEquals("Score should be 100 when no apps configured", 100, result.score)
        assertNull("No dominant reason", result.dominantReason)
        assertEquals("Empty breakdown", 0, result.perApp.size)
    }

    // ── Test 4: Trend smoothing softens a single bad day ──────────────────

    @Test
    fun `trend smoothing - single bad day is softened by recent good history`() {
        // This test demonstrates that the rolling weighted average softens
        // the impact of a single bad day. We compare the raw score (what a
        // naive same-day-only calculation would give) against the final score
        // (after smoothing with recent history).

        val input = MpiInput(
            todayPerApp = listOf(
                // Instagram: 180 min used, 45 min limit → 4× limit
                //   duration excess ratio = 3.0 → penalty = 3.0 × 25 = 75, clamped to 50
                //   sessions: 15 → frequency penalty = 14 × 0.5 = 7
                //   late-night: 45 min → ratio = 45/45 = 1.0 → penalty = 1.0 × 15 = 15
                AppUsageToday(
                    packageName = "com.instagram.android",
                    appName = "Instagram",
                    durationMs = 180 * 60_000L,
                    sessionCount = 15,
                    lateNightDurationMs = 45 * 60_000L
                )
            ),
            limits = listOf(
                AppLimit("com.instagram.android", dailyLimitMinutes = 45)
            ),
            // Recent history was GOOD — user had 85 and 90 on previous days.
            // This should soften today's bad score significantly.
            history = listOf(
                MpiHistoryEntry("yesterday", 85),
                MpiHistoryEntry("two-days-ago", 90)
            )
        )

        val result = MpiCalculator.calculateMpi(input)

        // ── Raw (naive same-day-only) calculation ──
        // Duration penalty: min(75, 50) = 50
        // Frequency penalty: min(7, 20) = 7
        // Late-night penalty: min(15, 15) = 15
        // Total penalty: 50 + 7 + 15 = 72
        // Raw score: 100 - 72 = 28
        assertEquals("Raw (naive) score should be 28", 28, result.rawScore)

        // ── Smoothed calculation ──
        // smoothed = round(28×0.70 + 85×0.20 + 90×0.10)
        //          = round(19.6 + 17.0 + 9.0) = round(45.6) = 46
        assertEquals("Smoothed score should be 46", 46, result.smoothedScore)

        // Trend bonus: raw (28) not > smoothed (46) → 0
        // Final = 46
        assertEquals("Final score should be 46", 46, result.score)

        // KEY ASSERTION: The smoothed score (46) is much higher than the
        // raw score (28). The recent good history softened today's bad day
        // by 18 points, preventing a jarring swing.
        assertTrue(
            "Smoothed score (${result.score}) should be significantly higher than raw (${result.rawScore})",
            result.score > result.rawScore + 10
        )
    }

    // ── Additional: Trend bonus when today improves ───────────────────────

    @Test
    fun `trend bonus - today better than recent average gets a small boost`() {
        // Recent history was mediocre (60, 55). Today is decent (75 raw).
        // Raw (75) > smoothed → trend bonus applies.
        val input = MpiInput(
            todayPerApp = listOf(
                // Instagram: 38 min used, 45 min limit → under limit
                AppUsageToday(
                    packageName = "com.instagram.android",
                    appName = "Instagram",
                    durationMs = 38 * 60_000L,
                    sessionCount = 2,
                    lateNightDurationMs = 0
                )
            ),
            limits = listOf(
                AppLimit("com.instagram.android", dailyLimitMinutes = 45)
            ),
            history = listOf(
                MpiHistoryEntry("yesterday", 60),
                MpiHistoryEntry("two-days-ago", 55)
            )
        )

        val result = MpiCalculator.calculateMpi(input)

        // All within limits → raw = 100
        assertEquals("Raw score should be 100", 100, result.rawScore)

        // Smoothed = 100×0.70 + 60×0.20 + 55×0.10 = 70 + 12 + 5.5 = 87.5 → 88
        assertEquals("Smoothed should be 88", 88, result.smoothedScore)

        // Trend bonus: raw (100) > smoothed (88) → improvement = 12, bonus = min(6, 5) = 5
        // Final = 88 + 5 = 93
        assertEquals("Final score should be 93 (with trend bonus)", 93, result.score)
        assertTrue("Score should be higher than smoothed", result.score > result.smoothedScore)
    }

    // ── Additional: Exactly at limit = no duration penalty ─────────────────

    @Test
    fun `exactly at limit - no duration penalty`() {
        val input = MpiInput(
            todayPerApp = listOf(
                // Exactly 45 min used, 45 min limit → ratio = 1.0, excess = 0
                AppUsageToday(
                    packageName = "com.instagram.android",
                    appName = "Instagram",
                    durationMs = 45 * 60_000L,
                    sessionCount = 3,
                    lateNightDurationMs = 0
                )
            ),
            limits = listOf(
                AppLimit("com.instagram.android", dailyLimitMinutes = 45)
            ),
            history = emptyList()
        )

        val result = MpiCalculator.calculateMpi(input)

        // Duration penalty: 0 (exactly at limit)
        // Frequency penalty: 2 × 0.5 = 1
        // Total: 1
        // Score: 99
        assertEquals("Score should be 99 (only frequency penalty)", 99, result.score)
        assertEquals(
            "Instagram overage should be 0 min",
            0,
            result.perApp.first().overageMinutes
        )
    }
}
