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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.udid.data.PeriodComparison
import com.example.udid.ui.theme.ActiveGreen
import com.example.udid.ui.theme.UsageHigh
import com.example.udid.ui.theme.UsageLow
import com.example.udid.ui.theme.UsageMedium

/**
 * A plain, immutable snapshot of everything the [ShareableReportCard] needs to
 * render as a standalone image. It decouples the card from the live
 * [com.example.udid.data.UsageReport]/ViewModel so the card can be rendered
 * off-screen without any state or repository involvement.
 */
data class ShareReportPayload(
    val appName: String,
    val appIcon: ImageBitmap?,
    val periodLabel: String,
    val periodTypeLabel: String,  // "Daily" / "Weekly" / "Monthly"
    val totalScreenTimeMs: Long,
    val comparison: PeriodComparison?,
    val mostUsedAppName: String?,
    val averageMpi: Int?
)

/**
 * The purpose-built, self-contained report card used for sharing.
 *
 * This is intentionally NOT the interactive report screen: it is a fixed,
 * static, branded layout tuned to look good as an exported PNG — the app icon
 * and name up top, the period label, the big total, the comparison vs the
 * previous period, and the most-used app. It is rendered off-screen into a
 * bitmap (see ReportImageExporter), so it makes no sense as an interactive UI.
 */
@Composable
fun ShareableReportCard(
    payload: ShareReportPayload,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = Color(0xFF0E1626)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top branding row: app icon + name.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 48.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                ) {
                    // Square-ish icon, masked to the circle container.
                    payload.appIcon?.let { icon ->
                        Image(
                            bitmap = icon,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(6.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = payload.appName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "Screen Time Report",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF8A93A6)
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Period label.
            Text(
                text = payload.periodTypeLabel.uppercase(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF9FA8BC)
            )
            Text(
                text = payload.periodLabel,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(40.dp))

            // Big total.
            Text(
                text = "Total Screen Time",
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFF8A93A6)
            )
            Text(
                text = formatCardDuration(payload.totalScreenTimeMs),
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Bold,
                color = ActiveGreen,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(36.dp))

            // Comparison row.
            payload.comparison?.let { comparison ->
                ComparisonBlock(comparison)
            }

            Spacer(modifier = Modifier.weight(1f))

            // Bottom stats row: MPI + Most Used app.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 40.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // MPI score.
                payload.averageMpi?.let { mpi ->
                    val mpiColor = when {
                        mpi >= 80 -> UsageLow
                        mpi >= 50 -> UsageMedium
                        else -> UsageHigh
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(mpiColor)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(horizontalAlignment = Alignment.Start) {
                            Text(
                                text = "MPI",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF8A93A6)
                            )
                            Text(
                                text = "$mpi / 100",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                        }
                    }
                }

                // Most-used app.
                payload.mostUsedAppName?.let { mostName ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(ActiveGreen)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(horizontalAlignment = Alignment.Start) {
                            Text(
                                text = "MOST USED",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF8A93A6)
                            )
                            Text(
                                text = mostName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComparisonBlock(comparison: PeriodComparison) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFF16233B))
            .padding(vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "vs. Previous Period",
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFF8A93A6)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatCardDuration(comparison.previousTotalMs),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(modifier = Modifier.width(18.dp))

            val pct = comparison.percentChange
            if (pct != null) {
                Text( 
                    text = if (pct >= 0) "\u2191" else "\u2193",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (pct >= 0) UsageHigh else UsageLow
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = String.format(java.util.Locale.getDefault(), "%+.0f%%", pct),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (pct >= 0) UsageHigh else UsageLow
                )
            } else {
                Text(
                    text = "Not enough data yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF8A93A6),
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

private fun formatCardDuration(millis: Long): String {
    val totalSec = millis / 1000
    val hours = totalSec / 3600
    val minutes = (totalSec % 3600) / 60
    val secs = totalSec % 60

    return buildString {
        if (hours > 0) append("${hours}h ")
        if (minutes > 0) append("${minutes}m")
        if (hours == 0L && minutes == 0L && secs > 0L) append(" ${secs}s")
    }.trim().ifEmpty { "0m" }
}
