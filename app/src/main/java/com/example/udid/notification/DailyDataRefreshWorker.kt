package com.example.udid.notification

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.udid.data.AppDatabase
import com.example.udid.data.ReportRepository
import com.example.udid.mpi.MpiScoreCalculator
import com.example.udid.usage.UsageEventReader
import java.util.Calendar

/**
 * Automatically loads today's usage data and calculates MPI before the
 * daily notification fires (scheduled at ~11:30 PM, 25 min before the
 * 11:55 PM notification).
 *
 * This ensures the notification always has fresh data to show, even if
 * the user never manually loaded data that day. The late-night window
 * (11 PM – 5 AM) is mostly complete by this time.
 *
 * Steps:
 *  1. Query UsageStatsManager for today's sessions (midnight → now)
 *  2. Store sessions in the Room database
 *  3. Run retention cleanup (delete sessions older than 30 days)
 *  4. Calculate and store today's MPI score
 *
 * The worker is idempotent — running it twice for the same day just
 * replaces the same daily_summary row via UPSERT.
 */
class DailyDataRefreshWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val reader = UsageEventReader(context)
            val db = AppDatabase.getInstance(context)
            val sessionRepository = com.example.udid.data.SessionRepository(
                db.sessionDao(),
                db.dailySummaryDao()
            )

            // 1. Read today's sessions (midnight → now)
            val now = System.currentTimeMillis()
            val todayStart = startOfDay(now)
            val sessions = reader.readSessions(todayStart, now)

            // 2. Store sessions in DB
            sessionRepository.storeSessions(sessions)

            // 3. Retention cleanup
            val retentionCutoff = now -
                (ReportRepository.RETENTION_DAYS.toLong() * 24 * 60 * 60 * 1000)
            sessionRepository.deleteSessionsOlderThan(retentionCutoff)

            // 4. Calculate and store today's MPI
            val mpiCalculator = MpiScoreCalculator(
                db.sessionDao(),
                db.dailySummaryDao(),
                db.distractingAppConfigDao()
            )
            mpiCalculator.calculateAndStoreToday()

            Log.d(TAG, "Data refresh complete: ${sessions.size} sessions stored, MPI calculated")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Data refresh failed", e)
            Result.retry()
        }
    }

    private fun startOfDay(epochMillis: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = epochMillis }
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    companion object {
        const val WORK_NAME = "daily_data_refresh"
        private const val TAG = "DailyDataRefresh"
    }
}
