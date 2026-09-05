package com.example.udid.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
            val db = AppDatabase.getInstance(context)
            val configs = db.distractingAppConfigDao().getAll()
            hasDistractingApps = configs.isNotEmpty()

            val todayResult = mpiCalculator.calculateAndStoreToday()
            mpiTodayScore = todayResult.score
            mpiDominantReason = todayResult.dominantReason

            mpiYesterdayScore = mpiCalculator.getYesterdayScore()

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
            onOpenSetup = { /* Handled by the Setup MPI button in the header */ }
        )

        // ── Tabs ──
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

        // ── Period navigator ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            IconButton(
                onClick = { viewModel.shiftPeriod(-1) },
                modifier = Modifier.size(40.dp)
            ) {
                Text(
                    text = "\u2039",   // ‹ single left-pointing angle quotation
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Text(
                text = viewModel.periodLabel(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(200.dp),
                textAlign = TextAlign.Center
            )

            IconButton(
                onClick = { viewModel.shiftPeriod(1) },
                modifier = Modifier.size(40.dp)
            ) {
                Text(
                    text = "\u203A",   // › single right-pointing angle quotation
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        // ── Report body with crossfade ──
        AnimatedContent(
            targetState = uiState,
            modifier = Modifier.weight(1f),
            transitionSpec = {
                fadeIn() togetherWith fadeOut()
            },
            label = "report_transition"
        ) { state ->
            when {
                state.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(40.dp),
                                strokeWidth = 4.dp
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Loading report...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                state.report != null -> {
                    ReportScreen(
                        report = state.report!!,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                else -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "\uD83D\uDCCA",
                                style = MaterialTheme.typography.headlineLarge
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "No usage data for this period",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Load usage data to see your report here",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // ── Share button (only when report exists) ──
        if (uiState.report != null) {
            Button(
                onClick = { viewModel.shareCurrentReport(context) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .height(48.dp),
                enabled = !uiState.isSharing && !uiState.isLoading,
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
}

private fun formatDayShort(epochMillis: Long): String {
    val fmt = java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault())
    return fmt.format(java.util.Date(epochMillis))
}
