package com.example.udid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.udid.data.AppDatabase
import com.example.udid.mpi.MpiScoreCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Top-level reports area: the MPI score card, Daily/Weekly/Monthly sub-tabs,
 * a prev/next period navigator, and the shared [ReportScreen] body with a
 * loading state.
 */
@Composable
fun ReportsView(
    viewModel: ReportViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val period by viewModel.period.collectAsState()
    val reference by viewModel.referenceMillis.collectAsState()

    // ── MPI state ──
    val context = LocalContext.current
    val mpiCalculator = remember {
        val db = AppDatabase.getInstance(context)
        MpiScoreCalculator(db.sessionDao(), db.dailySummaryDao(), db.distractingAppConfigDao())
    }

    var mpiTodayScore by remember { mutableStateOf<Int?>(null) }
    var mpiYesterdayScore by remember { mutableStateOf<Int?>(null) }
    var mpiDominantReason by remember { mutableStateOf<String?>(null) }
    var mpiTrend by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    var hasDistractingApps by remember { mutableStateOf(false) }

    // Fetch MPI data whenever the Reports tab is shown or sessions change.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            // Check if any distracting apps are configured.
            val db = AppDatabase.getInstance(context)
            val configs = db.distractingAppConfigDao().getAll()
            hasDistractingApps = configs.isNotEmpty()

            // Calculate today's MPI (stores in DB) and use the returned result.
            val todayResult = mpiCalculator.calculateAndStoreToday()
            mpiTodayScore = todayResult.score
            mpiDominantReason = todayResult.dominantReason

            // Get yesterday's score for comparison.
            mpiYesterdayScore = mpiCalculator.getYesterdayScore()

            // Get 7-day trend.
            val scores = mpiCalculator.getRecentScores(7)
            mpiTrend = scores.map { entry ->
                val dayLabel = formatDayShort(entry.dateMillis)
                dayLabel to entry.mpiScore
            }
        }
    }

    val tabs = listOf("Daily", "Weekly", "Monthly")
    val selectedIndex = when (period) {
        ReportPeriod.DAILY -> 0
        ReportPeriod.WEEKLY -> 1
        ReportPeriod.MONTHLY -> 2
    }

    Column(modifier = modifier.fillMaxSize()) {

        // ── MPI inline banner ──
        MpiScoreSection(
            todayScore = mpiTodayScore,
            yesterdayScore = mpiYesterdayScore,
            dominantReason = mpiDominantReason,
            trend = mpiTrend,
            hasDistractingApps = hasDistractingApps,
            onOpenSetup = { /* Handled by the gear icon in the header */ }
        )

        TabRow(
            selectedTabIndex = selectedIndex,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedIndex]),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedIndex == index,
                    onClick = {
                        viewModel.setPeriod(
                            when (index) {
                                0 -> ReportPeriod.DAILY
                                1 -> ReportPeriod.WEEKLY
                                else -> ReportPeriod.MONTHLY
                            }
                        )
                    },
                    text = {
                        Text(
                            text = title,
                            fontWeight = if (selectedIndex == index) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            TextButton(onClick = { viewModel.shiftPeriod(-1) }) {
                Text(text = "\u25C0", style = MaterialTheme.typography.titleMedium)
            }

            Text(
                text = viewModel.periodLabel(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(200.dp),
                textAlign = TextAlign.Center
            )

            TextButton(onClick = { viewModel.shiftPeriod(1) }) {
                Text(text = "\u25B6", style = MaterialTheme.typography.titleMedium)
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            when {
                uiState.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(40.dp),
                                strokeWidth = 4.dp
                            )
                            Spacer(modifier = Modifier.padding(top = 12.dp))
                            Text(
                                text = "Loading report...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                uiState.report != null -> {
                    ReportScreen(
                        report = uiState.report!!,
                        modifier = Modifier.weight(1f)
                    )
                }
                else -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No usage data for this period.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Button(
            onClick = { viewModel.shareCurrentReport(context) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .height(48.dp),
            enabled = uiState.report != null && !uiState.isSharing && !uiState.isLoading,
            shape = MaterialTheme.shapes.medium
        ) {
            if (uiState.isSharing) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(20.dp)
                        .padding(end = 8.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
            Text(
                text = if (uiState.isSharing) "Generating image..." else "Share Report",
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

private fun startOfDay(epochMillis: Long): Long {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = epochMillis }
    cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
    cal.set(java.util.Calendar.MINUTE, 0)
    cal.set(java.util.Calendar.SECOND, 0)
    cal.set(java.util.Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

private fun formatDayShort(epochMillis: Long): String {
    val fmt = java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault())
    return fmt.format(java.util.Date(epochMillis))
}

private const val DAY_MILLIS = 24L * 60 * 60 * 1000
