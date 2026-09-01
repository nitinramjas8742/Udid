package com.example.udid.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.udid.MainActivity
import com.example.udid.R
import com.example.udid.data.AppDatabase
import com.example.udid.data.ReportRepository
import com.example.udid.mpi.MpiScoreCalculator
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Fires a daily notification at ~11:55 PM with the user's screen-time summary.
 *
 * Runs as a CoroutineWorker so database reads happen off the main thread.
 * Uses SharedPreferences to prevent duplicate notifications on the same day
 * (e.g. if WorkManager re-evaluates after a reboot).
 *
 * # Notification channel
 *
 * Android 8+ requires a NotificationChannel. We create one on every worker
 * run (idempotent — creating an existing channel is a no-op). The channel
 * is dedicated to daily report notifications so users can independently
 * control its importance/sound in system settings.
 *
 * # Duplicate prevention
 *
 * SharedPreferences key "last_notified_date" stores "yyyy-MM-dd". Before
 * posting, the worker compares today's date string. If they match, the
 * worker returns without posting.
 */
class DailyReportNotificationWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // ── Permission check (Android 13+) ──
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "POST_NOTIFICATIONS permission not granted, skipping notification")
                return Result.success()
            }
        }

        // ── Duplicate prevention ──
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val todayDateStr = todayDateString()

        if (prefs.getString(KEY_LAST_NOTIFIED_DATE, null) == todayDateStr) {
            Log.d(TAG, "Already notified today ($todayDateStr), skipping")
            return Result.success()
        }

        // ── Gather data ──
        val db = AppDatabase.getInstance(context)
        val repository = ReportRepository(db.sessionDao(), db.dailySummaryDao())
        val mpiCalculator = MpiScoreCalculator(
            db.sessionDao(), db.dailySummaryDao(), db.distractingAppConfigDao()
        )

        val todayStart = startOfDay(System.currentTimeMillis())

        // Re-calculate MPI to ensure it's fresh (covers the edge case where
        // the user loaded data but MPI wasn't recalculated yet).
        mpiCalculator.calculateAndStoreToday()

        val report = repository.getDailyReport(System.currentTimeMillis())
        val totalMs = report.totalScreenTimeMs
        val mostUsed = report.mostUsedApp

        // MPI from daily_summary (just calculated above).
        val dailySummary = db.dailySummaryDao().getByDateMillis(todayStart)
        val mpiScore = dailySummary?.mpiScore?.takeIf { it > 0 }

        Log.d(TAG, "Data check: totalMs=$totalMs, mpiScore=$mpiScore")

        // ── Skip if no data at all ──
        if (totalMs <= 0L && mpiScore == null) {
            Log.w(TAG, "No screen time or MPI data for today, skipping notification")
            return Result.success()
        }

        // ── Format notification text ──
        val totalFormatted = formatDuration(totalMs)
        val title = "\uD83C\uDF19 Your daily report is ready"

        val body = buildString {
            append("You used your phone for $totalFormatted today.")
            if (mostUsed != null) {
                val appTime = formatDuration(mostUsed.totalMs)
                append(" ${mostUsed.appName} was your most-used app at $appTime.")
            }
            if (mpiScore != null) {
                append("\nMPI: $mpiScore/100")
            }
        }

        // ── Post notification ──
        createNotificationChannel()
        postNotification(title, body)

        // ── Mark as notified ──
        prefs.edit().putString(KEY_LAST_NOTIFIED_DATE, todayDateStr).apply()
        Log.d(TAG, "Notification posted: $title")

        return Result.success()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Daily Reports",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Daily screen-time summary notifications"
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun postNotification(title: String, body: String) {
        // PendingIntent: open MainActivity when tapped.
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    // ── Helpers ──

    private fun todayDateString(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return fmt.format(Date())
    }

    private fun startOfDay(epochMillis: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = epochMillis }
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun formatDuration(millis: Long): String {
        val totalSec = millis / 1000
        if (totalSec < 60) return "${totalSec}s"
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        return buildString {
            if (hours > 0) append("${hours}h ")
            if (minutes > 0) append("${minutes}m")
        }.trim()
    }

    companion object {
        private const val TAG = "DailyReportNotif"
        const val WORK_NAME = "daily_report_notification"
        const val CHANNEL_ID = "daily_report"
        const val NOTIFICATION_ID = 1001
        const val PREFS_NAME = "udid_notifications"
        const val KEY_LAST_NOTIFIED_DATE = "last_notified_date"
        private const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}
