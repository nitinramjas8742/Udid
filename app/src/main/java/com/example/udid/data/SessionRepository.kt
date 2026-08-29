package com.example.udid.data

import com.example.udid.usage.AppSession
import org.json.JSONObject
import java.util.Calendar

/**
 * The persistence bridge between the app usage reader and Room.
 *
 * It stores raw [AppSession] rows and rolls them up into the `daily_summary`
 * table — one aggregate row per calendar day. Daily summaries are refreshed
 * whenever sessions are loaded and are never purged, so even after the raw
 * [SessionEntity] rows are deleted by retention the long-term history needed
 * for period-over-period comparison survives.
 */
class SessionRepository(
    private val sessionDao: SessionDao,
    private val dailySummaryDao: DailySummaryDao
) {

    /**
     * Persist sessions and refresh the daily summaries for every day the new
     * sessions touch.
     *
     * Raw rows are inserted with [SessionDao.insertAll] (deduplicated by the
     * unique index). Each affected day's summary is then **recomputed from the
     * raw table for that whole day** (via [SessionDao.screenTimeByApp] /
     * [SessionDao.openCountByApp], which use the same clip math as the reports)
     * and upserted. Recomputing from the raw table — rather than only from the
     * just-loaded list — guarantees a day's summary stays correct even when a
     * day is loaded again across midnight with only part of its sessions.
     */
    suspend fun storeSessions(sessions: List<AppSession>) {
        if (sessions.isEmpty()) return

        sessionDao.insertAll(
            sessions.map {
                SessionEntity(
                    packageName = it.packageName,
                    appName = it.appName,
                    startedAt = it.startedAt,
                    endedAt = it.endedAt,
                    durationSec = it.durationSec
                )
            }
        )

        val affectedDays = sessions
            .flatMap { daysFor(it.startedAt, it.endedAt) }
            .distinct()
            .sorted()

        for (dayStart in affectedDays) {
            refreshDailySummary(dayStart)
        }
    }

    /**
     * Delete raw [SessionEntity] rows that started before [epochMillis]
     * (retention). The corresponding daily summaries are NOT deleted — they are
     * the long-term history that outlives the raw rows.
     */
    suspend fun deleteSessionsOlderThan(epochMillis: Long) {
        sessionDao.deleteOlderThan(epochMillis)
    }

    private suspend fun refreshDailySummary(dayStart: Long) {
        val dayEnd = dayStart + DAY_MILLIS

        val perAppMs = sessionDao.screenTimeByApp(dayStart, dayEnd)
        val perAppOpen = sessionDao.openCountByApp(dayStart, dayEnd)
        val totalMs = perAppMs.sumOf { it.durationMs }

        val timeJson = JSONObject()
        val openJson = JSONObject()
        perAppMs.forEach {
            timeJson.put(it.packageName, it.durationMs)
        }
        perAppOpen.forEach {
            openJson.put(it.packageName, it.openCount)
        }

        dailySummaryDao.upsert(
            DailySummaryEntity(
                dateMillis = dayStart,
                totalScreenTimeMs = totalMs,
                perAppTimeJson = timeJson.toString(),
                perAppOpenCountJson = openJson.toString()
            )
        )
    }

    /** The set of calendar-day start-millis covered by [startMs, endMs). */
    private fun daysFor(startMs: Long, endMs: Long): List<Long> {
        val result = mutableListOf<Long>()
        var day = startOfDay(startMs)
        val lastDay = startOfDay(endMs)
        while (true) {
            result.add(day)
            if (day == lastDay) break
            day += DAY_MILLIS
        }
        return result
    }

    private fun startOfDay(epochMillis: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = epochMillis }
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private companion object {
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}
