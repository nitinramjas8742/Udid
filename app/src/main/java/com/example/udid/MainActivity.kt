package com.example.udid

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.udid.notification.DailyDataRefreshWorker
import com.example.udid.notification.DailyReportNotificationWorker
import com.example.udid.ui.AboutScreen
import com.example.udid.ui.ActivityLogTab
import com.example.udid.ui.MpiSetupScreen
import com.example.udid.ui.ReportViewModel
import com.example.udid.ui.ReportsView
import com.example.udid.ui.UsageSessionList
import com.example.udid.ui.theme.TimeSlayerTheme
import com.example.udid.usage.AppSession
import com.example.udid.usage.UsageEventReader
import com.example.udid.util.UsageAccessHelper
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    /** Shared state: true when notification permission was denied and the rationale dialog should show. */
    val showPermissionRationale = mutableStateOf(false)

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            showPermissionRationale.value = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scheduleDailyDataRefresh()
        scheduleDailyReportNotification()
        requestPostNotificationPermissionIfNeeded()
        showScreen()
    }

    override fun onResume() {
        super.onResume()
        showScreen()
    }

    /**
     * Schedule a periodic WorkManager job that fires daily at ~11:55 PM.
     *
     * # How exact-time scheduling works with WorkManager
     *
     * WorkManager's PeriodicWorkRequest has a minimum repeat interval of
     * 15 minutes and does NOT guarantee exact clock times — Android may
     * shift the trigger by ~15 min for battery optimization. The standard
     * approach is:
     *
     *  1. Calculate the delay from now to the next 11:55 PM.
     *  2. Create a PeriodicWorkRequest with a 24-hour repeat interval.
     *  3. Set the initial delay so the FIRST run lands at ~11:55 PM.
     *  4. After that, WorkManager repeats every 24h automatically.
     *
     * This gives you "approximately 11:55 PM daily" without needing the
     * deprecated setExact/setExactAndAllowWhileIdle AlarmManager APIs.
     *
     * 11:55 PM is chosen so that the late-night usage window (11 PM – 5 AM)
     * is mostly complete before the notification fires, giving the user an
     * accurate picture of their full day.
     */
    private fun scheduleDailyReportNotification() {
        val workRequest = PeriodicWorkRequestBuilder<DailyReportNotificationWorker>(
            24, TimeUnit.HOURS
        )
            .setInitialDelay(calculateDelayToNext1155Pm(), TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            DailyReportNotificationWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )
    }

    /** Milliseconds from now until the next 11:55 PM local time. */
    private fun calculateDelayToNext1155Pm(): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 55)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        // If 11:55 PM today has already passed, schedule for tomorrow.
        if (target.before(now)) {
            target.add(Calendar.DAY_OF_MONTH, 1)
        }
        return target.timeInMillis - now.timeInMillis
    }

    /**
     * Schedule a periodic WorkManager job that loads today's usage data at ~11:30 PM,
     * 25 minutes before the daily notification fires at ~11:55 PM.
     */
    private fun scheduleDailyDataRefresh() {
        val workRequest = PeriodicWorkRequestBuilder<DailyDataRefreshWorker>(
            24, TimeUnit.HOURS
        )
            .setInitialDelay(calculateDelayToNext1130Pm(), TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            DailyDataRefreshWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )
    }

    /** Milliseconds from now until the next 11:30 PM local time. */
    private fun calculateDelayToNext1130Pm(): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 30)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (target.before(now)) {
            target.add(Calendar.DAY_OF_MONTH, 1)
        }
        return target.timeInMillis - now.timeInMillis
    }

    /**
     * Request POST_NOTIFICATIONS permission on Android 13+ (API 33).
     * On older devices this is a no-op — the permission doesn't exist and
     * notifications work automatically.
     */
    private fun requestPostNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun showScreen() {
        val hasUsageAccess =
            UsageAccessHelper.hasUsageAccess(this)

        setContent {
            TimeSlayerTheme {
                if (hasUsageAccess) {
                    UsageDashboard(context = this)
                } else {
                    PermissionScreen(
                        onGrantAccess = {
                            val intent = Intent(
                                Settings.ACTION_USAGE_ACCESS_SETTINGS
                            )
                            startActivity(intent)
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun UsageDashboard(context: android.content.Context) {

    val activity = context as? MainActivity
    var sessions by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<List<AppSession>>(emptyList()) }
    var isLoading by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var hasLoaded by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var selectedTab by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    var showMpiSetup by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var showAbout by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var isRefreshing by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val tabs = listOf("Activity", "Summary", "Reports")

    // ── Permission rationale dialog ──
    val showPermissionRationale = activity?.showPermissionRationale?.value ?: false
    if (showPermissionRationale) {
        AlertDialog(
            onDismissRequest = { activity?.showPermissionRationale?.value = false },
            title = { Text("Notification permission needed") },
            text = {
                Text(
                    "TimeSlayer needs notification permission to send your daily screen-time report at 11:55 PM. " +
                    "You can enable it later in Settings > Apps > TimeSlayer > Notifications."
                )
            },
            confirmButton = {
                Button(onClick = {
                    activity?.showPermissionRationale?.value = false
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    }
                    context.startActivity(intent)
                }) {
                    Text("Open Settings")
                }
            },
            dismissButton = {
                Button(onClick = { activity?.showPermissionRationale?.value = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    val reportViewModel: ReportViewModel = viewModel(
        key = "report",
        factory = ReportViewModel.factory(
            com.example.udid.data.ReportRepository(
                com.example.udid.data.AppDatabase
                    .getInstance(context).sessionDao(),
                com.example.udid.data.AppDatabase
                    .getInstance(context).dailySummaryDao()
            )
        )
    )

    fun loadSessions(showLoading: Boolean = false, showRefreshIndicator: Boolean = false) {
        if (showLoading) isLoading = true
        if (showRefreshIndicator) isRefreshing = true
        scope.launch {
            val reader = UsageEventReader(context)
            val endTime = System.currentTimeMillis()
            val startTime = endTime - (24 * 60 * 60 * 1000)

            val newSessions = reader.readSessions(startTime, endTime)
            sessions = newSessions

            // Persist today's sessions so monthly reports have history.
            val db = com.example.udid.data.AppDatabase.getInstance(context)
            val sessionRepository = com.example.udid.data.SessionRepository(
                db.sessionDao(),
                db.dailySummaryDao()
            )
            sessionRepository.storeSessions(newSessions)

            // Retention: keep only the last 30 days of raw session rows.
            // Daily summaries survive this purge (long-term history).
            val retentionCutoff = endTime -
                (com.example.udid.data.ReportRepository.RETENTION_DAYS.toLong() * 24 * 60 * 60 * 1000)
            sessionRepository.deleteSessionsOlderThan(retentionCutoff)

            // Calculate today's MPI score from the just-persisted sessions.
            val mpiCalculator = com.example.udid.mpi.MpiScoreCalculator(
                db.sessionDao(),
                db.dailySummaryDao(),
                db.distractingAppConfigDao()
            )
            mpiCalculator.calculateAndStoreToday()

            // Verify reports against the persisted data (logcat: UdidReports).
            verifyReportsOnDevice(db)

            // Freshly persisted data changed; refresh the reports view.
            reportViewModel.refresh()

            isLoading = false
            isRefreshing = false
            hasLoaded = true
        }
    }

    // ── Auto-sync from UsageStatsManager on every app resume ──
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        loadSessions()
    }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
        ) {

            // ── Header ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "TimeSlayer",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Your screen time at a glance",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Info icon
                IconButton(
                    onClick = { showAbout = true },
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            CircleShape
                        )
                ) {
                    Text(
                        text = "\u2139",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.size(8.dp))

                // Settings icon
                IconButton(
                    onClick = { showMpiSetup = true },
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            CircleShape
                        )
                ) {
                    Text(
                        text = "\u2699",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

        Spacer(modifier = Modifier.height(8.dp))

        // ── Main content + overlays as proper layers ──
        Box(modifier = Modifier.weight(1f)) {
            // Main content
            Column(modifier = Modifier.fillMaxSize()) {
                when {
                    !hasLoaded && !isLoading -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "\uD83D\uDD0D",
                                style = MaterialTheme.typography.displayLarge
                            )
                            Spacer(modifier = Modifier.height(20.dp))
                            Text(
                                text = "Ready to track your usage",
                                style = MaterialTheme.typography.titleMedium,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Tap below to scan your app activity from the last 24 hours",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(32.dp))
                            Button(
                                onClick = { loadSessions(showLoading = true) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp),
                                shape = MaterialTheme.shapes.medium
                            ) {
                                Text(
                                    text = "Load Usage Data",
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }
                    isLoading -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(48.dp),
                                strokeWidth = 4.dp
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Scanning app usage...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    else -> {
                        Column(modifier = Modifier.fillMaxSize()) {
                            TabRow(
                                selectedTabIndex = selectedTab,
                                containerColor = MaterialTheme.colorScheme.surface,
                                contentColor = MaterialTheme.colorScheme.primary,
                                indicator = { tabPositions ->
                                    TabRowDefaults.SecondaryIndicator(
                                        modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                },
                                modifier = Modifier.padding(horizontal = 16.dp)
                            ) {
                                tabs.forEachIndexed { index, title ->
                                    Tab(
                                        selected = selectedTab == index,
                                        onClick = {
                                            selectedTab = index
                                            if (index == 2) reportViewModel.refresh()
                                        },
                                        text = {
                                            Text(
                                                text = title,
                                                fontWeight = if (selectedTab == index) FontWeight.SemiBold else FontWeight.Normal
                                            )
                                        },
                                        selectedContentColor = MaterialTheme.colorScheme.primary,
                                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            @OptIn(ExperimentalMaterial3Api::class)
                            PullToRefreshBox(
                                isRefreshing = isRefreshing,
                                onRefresh = { loadSessions(showRefreshIndicator = true) },
                                modifier = Modifier.weight(1f)
                            ) {
                                when (selectedTab) {
                                    0 -> ActivityLogTab(
                                        sessions = sessions,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    1 -> UsageSessionList(
                                        sessions = sessions,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    2 -> ReportsView(
                                        viewModel = reportViewModel,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Overlay: About screen
            if (showAbout) {
                AboutScreen(
                    onBack = { showAbout = false },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Overlay: MPI Setup screen
            if (showMpiSetup) {
                MpiSetupScreen(
                    onBack = { showMpiSetup = false },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}


/**
 * On-device sanity check for the report layer. Prints the daily, weekly and
 * monthly reports computed from the persisted sessions table to logcat under
 * the tag "TimeSlayerReports". Cross-check totals against the Summary tab.
 */
private suspend fun verifyReportsOnDevice(db: com.example.udid.data.AppDatabase) {
    val repository = com.example.udid.data.ReportRepository(
        db.sessionDao(),
        db.dailySummaryDao()
    )
    val now = System.currentTimeMillis()

    val reportTag = "TimeSlayerReports"

    val today = repository.getDailyReport(now)
    android.util.Log.d(reportTag, "=== DAILY ===")
    android.util.Log.d(reportTag, "Total screen time: ${today.totalScreenTimeMs / 1000}s")
    android.util.Log.d(reportTag, "Most used: ${today.mostUsedApp?.appName} (${today.mostUsedApp?.totalMs?.div(1000)}s)")
    today.perAppBreakdown.forEach {
        android.util.Log.d(reportTag, "  ${it.appName}: ${it.durationMs / 1000}s (${it.openCount} opens)")
    }
    logComparison(reportTag, "vs yesterday", today.comparison)

    val weekStart = repository.startOfWeek(now)
    val week = repository.getWeeklyReport(weekStart)
    android.util.Log.d(reportTag, "=== WEEKLY (from $weekStart) ===")
    android.util.Log.d(reportTag, "Total screen time: ${week.totalScreenTimeMs / 1000}s")
    week.perAppBreakdown.forEach {
        android.util.Log.d(reportTag, "  ${it.appName}: ${it.durationMs / 1000}s (${it.openCount} opens)")
    }
    logComparison(reportTag, "vs last week", week.comparison)

    val monthStart = repository.startOfMonth(now)
    val month = repository.getMonthlyReport(monthStart)
    android.util.Log.d(reportTag, "=== MONTHLY (from $monthStart) ===")
    android.util.Log.d(reportTag, "Total screen time: ${month.totalScreenTimeMs / 1000}s")
    month.perAppBreakdown.forEach {
        android.util.Log.d(reportTag, "  ${it.appName}: ${it.durationMs / 1000}s (${it.openCount} opens)")
    }
    logComparison(reportTag, "vs last month", month.comparison)
}

private fun logComparison(tag: String, label: String, comparison: com.example.udid.data.PeriodComparison?) {
    if (comparison == null) {
        android.util.Log.d(tag, "Comparison ($label): not enough prior data")
        return
    }
    val pct = comparison.percentChange
    val pctText = if (pct == null) "n/a" else String.format(java.util.Locale.US, "%.1f%%", pct)
    android.util.Log.d(
        tag,
        "Comparison ($label): prev=${comparison.previousTotalMs / 1000}s change=$pctText"
    )
}


@Composable
fun PermissionScreen(
    onGrantAccess: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "\uD83D\uDEE1\uFE0F",
            style = MaterialTheme.typography.displayLarge
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Permission Required",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "TimeSlayer needs Usage Access to track which apps you use and for how long. Your data stays on your device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = MaterialTheme.typography.bodyMedium.lineHeight
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onGrantAccess,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = MaterialTheme.shapes.medium
        ) {
            Text(
                text = "Grant Usage Access",
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}
