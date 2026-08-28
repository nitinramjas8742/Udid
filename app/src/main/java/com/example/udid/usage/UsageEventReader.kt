package com.example.udid.usage

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppSession(
    val packageName: String,
    val appName: String,
    val startedAt: Long,
    val endedAt: Long,
    val durationSec: Long,
    val isActive: Boolean = false
)

class UsageEventReader(
    private val context: Context
) {

    companion object {
        private const val TAG = "UsageEventReader"
        private const val MIN_SESSION_DURATION_SEC = 1L

        private val IGNORED_PACKAGES = setOf(
            "com.android.systemui",
            "com.android.launcher",
            "com.android.launcher3",
            "com.google.android.apps.nexuslauncher",
            "com.android.quicksearchbox",
        )
    }

    private val packageManager: PackageManager =
        context.packageManager

    suspend fun readSessions(
        startTime: Long,
        endTime: Long
    ): List<AppSession> = withContext(Dispatchers.IO) {

        val launchableApps = getFilteredLaunchableApps()
        Log.d(TAG, "Launchable apps found: ${launchableApps.size}")
        Log.d(TAG, "Launchable packages: ${launchableApps.keys.take(20)}")

        val usageStatsManager =
            context.getSystemService(
                Context.USAGE_STATS_SERVICE
            ) as UsageStatsManager

        val sessions = mutableListOf<AppSession>()

        val eventSessions = readEventSessions(
            usageStatsManager, launchableApps, startTime, endTime
        )
        sessions.addAll(eventSessions)

        val eventPackages = eventSessions.map { it.packageName }.toSet()

        val statsSessions = readUsageStatsSessions(
            usageStatsManager, launchableApps, startTime, endTime,
            excludePackages = eventPackages
        )
        sessions.addAll(statsSessions)

        Log.d(TAG, "Event sessions: ${eventSessions.size}, Stats-only sessions: ${statsSessions.size}, Total: ${sessions.size}")

        sessions.sortedByDescending { it.startedAt }
    }

    private fun readEventSessions(
        usageStatsManager: UsageStatsManager,
        launchableApps: Map<String, String>,
        startTime: Long,
        endTime: Long
    ): List<AppSession> {

        val usageEvents = usageStatsManager.queryEvents(startTime, endTime)
        val event = UsageEvents.Event()
        val foregroundTimestamps = mutableMapOf<String, Long>()
        val sessions = mutableListOf<AppSession>()
        var totalEvents = 0
        var matchedEvents = 0

        while (usageEvents.hasNextEvent()) {

            usageEvents.getNextEvent(event)
            totalEvents++

            val packageName = event.packageName
            val timestamp = event.timeStamp

            if (launchableApps[packageName] == null) continue
            if (packageName == context.packageName) continue
            matchedEvents++

            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND,
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    foregroundTimestamps[packageName] = timestamp
                }

                UsageEvents.Event.MOVE_TO_BACKGROUND,
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED -> {
                    val startedAt =
                        foregroundTimestamps.remove(packageName)
                            ?: continue

                    if (timestamp <= startedAt) continue

                    val durationSec = (timestamp - startedAt) / 1000
                    if (durationSec < MIN_SESSION_DURATION_SEC) continue

                    sessions.add(
                        AppSession(
                            packageName = packageName,
                            appName = launchableApps[packageName] ?: packageName,
                            startedAt = startedAt,
                            endedAt = timestamp,
                            durationSec = durationSec,
                            isActive = false
                        )
                    )
                }
            }
        }

        for ((packageName, startedAt) in foregroundTimestamps) {
            val now = System.currentTimeMillis()
            val durationSec = (now - startedAt) / 1000
            if (durationSec < MIN_SESSION_DURATION_SEC) continue

            sessions.add(
                AppSession(
                    packageName = packageName,
                    appName = launchableApps[packageName] ?: packageName,
                    startedAt = startedAt,
                    endedAt = now,
                    durationSec = durationSec,
                    isActive = true
                )
            )
        }

        val merged = mergeSessions(sessions)
        Log.d(TAG, "Events total=$totalEvents matched=$matchedEvents raw=${sessions.size} merged=${merged.size}")
        return merged
    }

    private fun readUsageStatsSessions(
        usageStatsManager: UsageStatsManager,
        launchableApps: Map<String, String>,
        startTime: Long,
        endTime: Long,
        excludePackages: Set<String>
    ): List<AppSession> {

        val usageStatsList = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY, startTime, endTime
        )

        val sessions = mutableListOf<AppSession>()

        for (stats in usageStatsList) {
            val packageName = stats.packageName
            if (launchableApps[packageName] == null) continue
            if (packageName == context.packageName) continue
            if (excludePackages.contains(packageName)) continue

            val totalTimeMs = stats.totalTimeInForeground
            if (totalTimeMs < MIN_SESSION_DURATION_SEC * 1000) continue

            val lastUsed = stats.lastTimeUsed

            sessions.add(
                AppSession(
                    packageName = packageName,
                    appName = launchableApps[packageName] ?: packageName,
                    startedAt = lastUsed - totalTimeMs,
                    endedAt = lastUsed,
                    durationSec = totalTimeMs / 1000,
                    isActive = false
                )
            )
        }

        Log.d(TAG, "UsageStats sessions: ${sessions.size}")
        return sessions
    }

    private fun mergeSessions(sessions: List<AppSession>): List<AppSession> {
        if (sessions.isEmpty()) return emptyList()

        val MERGE_GAP_MS = 10_000L

        val grouped = sessions.groupBy { it.packageName }
        val merged = mutableListOf<AppSession>()

        for ((_, group) in grouped) {
            val sorted = group.sortedBy { it.startedAt }
            var current = sorted.first()

            for (i in 1 until sorted.size) {
                val next = sorted[i]
                val gap = next.startedAt - current.endedAt

                if (gap <= MERGE_GAP_MS) {
                    current = AppSession(
                        packageName = current.packageName,
                        appName = current.appName,
                        startedAt = current.startedAt,
                        endedAt = maxOf(current.endedAt, next.endedAt),
                        durationSec = (maxOf(current.endedAt, next.endedAt) - current.startedAt) / 1000,
                        isActive = current.isActive || next.isActive
                    )
                } else {
                    merged.add(current)
                    current = next
                }
            }
            merged.add(current)
        }

        return merged
    }

    private fun getFilteredLaunchableApps(): Map<String, String> {

        val result = mutableMapOf<String, String>()

        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val launchableActivities =
            packageManager.queryIntentActivities(
                launcherIntent,
                PackageManager.MATCH_ALL
            )

        Log.d(TAG, "queryIntentActivities returned: ${launchableActivities.size} results")

        for (resolveInfo in launchableActivities) {

            val applicationInfo =
                resolveInfo.activityInfo.applicationInfo
            val packageName = applicationInfo.packageName

            if (IGNORED_PACKAGES.contains(packageName)) continue

            val appName =
                applicationInfo
                    .loadLabel(packageManager)
                    .toString()

            result[packageName] = appName
        }

        return result
    }
}
