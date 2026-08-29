package com.example.udid.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.udid.data.AppUsageRow
import com.example.udid.data.MostUsedRow
import com.example.udid.data.PeriodComparison
import com.example.udid.data.UsageReport
import com.example.udid.ui.theme.ActiveGreen
import com.example.udid.ui.theme.UsageHigh
import com.example.udid.ui.theme.UsageLow
import com.example.udid.ui.theme.UsageMedium

/**
 * Shared report body for Daily / Weekly / Monthly.
 *
 * Layout:
 *  - One summary card (screen time + MPI + comparison change)
 *  - Most-used app row
 *  - App-wise breakdown list
 */
@Composable
fun ReportScreen(
    report: UsageReport,
    modifier: Modifier = Modifier
) {
    val openCountMap = remember(report) {
        report.openCountByApp.associate { it.packageName to it.openCount }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 8.dp,
            bottom = 24.dp
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ── Summary card: screen time + MPI + comparison ──
        item {
            SummaryCard(report)
        }

        // ── Most-used app ──
        report.mostUsedApp?.let { most ->
            item("most_used") {
                MostUsedRow(most)
            }
        }

        // ── App breakdown header + list ──
        if (report.perAppBreakdown.isNotEmpty()) {
            item {
                Text(
                    text = "App-wise breakdown",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            items(
                report.perAppBreakdown,
                key = { it.packageName }
            ) { row ->
                AppBreakdownRow(
                    row = row,
                    openCount = openCountMap[row.packageName] ?: row.openCount
                )
            }
        }
    }
}

// ────────────────────────────────────────────────────────────
// Summary card — one card with everything
// ────────────────────────────────────────────────────────────

@Composable
private fun SummaryCard(report: UsageReport) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp)
        ) {
            // ── Big screen time ──
            Text(
                text = "Total Screen Time",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = formatDuration(report.totalScreenTimeMs),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )

            // ── MPI + comparison row ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // MPI chip
                report.averageMpi?.let { mpi ->
                    val mpiColor = when {
                        mpi >= 80 -> UsageLow
                        mpi >= 50 -> UsageMedium
                        else -> UsageHigh
                    }
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(mpiColor)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "MPI $mpi",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                // Comparison chip
                report.comparison?.let { comp ->
                    ComparisonChip(comp)
                }
            }
        }
    }
}

@Composable
private fun ComparisonChip(comp: PeriodComparison) {
    val pct = comp.percentChange
    val chipColor = when {
        pct == null -> MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
        pct < 0 -> UsageLow.copy(alpha = 0.15f)
        else -> UsageHigh.copy(alpha = 0.15f)
    }
    val textColor = when {
        pct == null -> MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
        pct < 0 -> UsageLow
        else -> UsageHigh
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(chipColor)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (pct != null) {
            Text(
                text = if (pct >= 0) "\u2191" else "\u2193",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = textColor
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = String.format(java.util.Locale.getDefault(), "%+.0f%%", pct),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = textColor
            )
        } else {
            Text(
                text = "vs prev",
                style = MaterialTheme.typography.labelMedium,
                color = textColor
            )
        }
    }
}

// ────────────────────────────────────────────────────────────
// Most-used app row
// ────────────────────────────────────────────────────────────

@Composable
private fun MostUsedRow(most: MostUsedRow) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(ActiveGreen)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "MOST USED",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
            Text(
                text = most.appName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = formatDuration(most.totalMs),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

// ────────────────────────────────────────────────────────────
// App breakdown row
// ────────────────────────────────────────────────────────────

@Composable
private fun AppBreakdownRow(
    row: AppUsageRow,
    openCount: Int
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = row.appName,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = "${openCount}x",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = formatDuration(row.durationMs),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

// ────────────────────────────────────────────────────────────
// Duration formatter
// ────────────────────────────────────────────────────────────

private fun formatDuration(millis: Long): String {
    val totalSec = millis / 1000
    if (totalSec < 60) return "${totalSec}s"

    val hours = totalSec / 3600
    val minutes = (totalSec % 3600) / 60
    val secs = totalSec % 60

    return buildString {
        if (hours > 0) append("${hours}h ")
        if (minutes > 0) append("${minutes}m ")
        if (secs > 0 && hours == 0L) append("${secs}s")
    }.trim()
}
