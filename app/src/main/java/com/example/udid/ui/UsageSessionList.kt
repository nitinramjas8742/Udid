package com.example.udid.ui

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.udid.ui.theme.ActiveGreen
import com.example.udid.ui.theme.UsageHigh
import com.example.udid.ui.theme.UsageLow
import com.example.udid.ui.theme.UsageMedium
import com.example.udid.usage.AppSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@Composable
fun UsageSessionList(
    sessions: List<AppSession>,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    if (sessions.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "\uD83D\uDCF1",
                    style = MaterialTheme.typography.headlineLarge
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "No usage data yet",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Open some apps and come back",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
        return
    }

    val aggregated = remember(sessions) { aggregateSessions(sessions) }
    val totalSeconds = remember(sessions) { sessions.sumOf { it.durationSec } }
    val activeSessions = remember(sessions) { sessions.filter { it.isActive } }
    val sessionsByPackage = remember(sessions) {
        sessions.groupBy { it.packageName }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            top = 12.dp,
            bottom = 24.dp
        )
    ) {

        item {
            SummaryHeader(
                totalApps = aggregated.size,
                totalSeconds = totalSeconds,
                activeCount = activeSessions.size
            )
        }

        items(
            aggregated,
            key = { it.packageName }
        ) { item ->
            AppUsageCard(
                packageName = item.packageName,
                appName = item.appName,
                totalDurationSec = item.totalDuration,
                lastUsedMs = item.lastUsedAt,
                isActive = item.isActive,
                maxDurationSec = aggregated.maxOfOrNull { it.totalDuration } ?: 1,
                childSessions = sessionsByPackage[item.packageName] ?: emptyList(),
                context = context
            )
        }
    }
}


@Composable
private fun SummaryHeader(
    totalApps: Int,
    totalSeconds: Long,
    activeCount: Int
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Text(
                text = "Last 24 Hours",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SummaryStat(
                    value = formatDuration(totalSeconds),
                    label = "Screen Time"
                )
                SummaryStat(
                    value = "$totalApps",
                    label = "Apps Used"
                )
                if (activeCount > 0) {
                    SummaryStat(
                        value = "$activeCount",
                        label = "Active Now"
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryStat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}


@Composable
private fun AppUsageCard(
    packageName: String,
    appName: String,
    totalDurationSec: Long,
    lastUsedMs: Long,
    isActive: Boolean,
    maxDurationSec: Long,
    childSessions: List<AppSession>,
    context: Context
) {
    var expanded by remember { mutableStateOf(false) }

    val progress = if (maxDurationSec > 0) {
        (totalDurationSec.toFloat() / maxDurationSec).coerceIn(0f, 1f)
    } else 0f

    val barColor = when {
        progress > 0.66f -> UsageHigh
        progress > 0.33f -> UsageMedium
        else -> UsageLow
    }

    val icon = remember(packageName) {
        try {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            if (drawable is BitmapDrawable) {
                drawable.bitmap.asImageBitmap()
            } else {
                val bitmap = android.graphics.Bitmap.createBitmap(
                    drawable.intrinsicWidth.coerceAtLeast(1),
                    drawable.intrinsicHeight.coerceAtLeast(1),
                    android.graphics.Bitmap.Config.ARGB_8888
                )
                val canvas = android.graphics.Canvas(bitmap)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bitmap.asImageBitmap()
            }
        } catch (_: Exception) {
            null
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {

                if (icon != null) {
                    Image(
                        bitmap = icon,
                        contentDescription = appName,
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(10.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = appName.take(1).uppercase(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = appName,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (isActive) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(ActiveGreen)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(1.dp))

                    Text(
                        text = formatTimeAgo(lastUsedMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = formatDuration(totalDurationSec),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "${childSessions.size} session${if (childSessions.size != 1) "s" else ""}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = progress)
                        .fillMaxSize()
                        .clip(RoundedCornerShape(3.dp))
                        .background(barColor)
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    val sorted = remember(childSessions) {
                        childSessions.sortedByDescending { it.startedAt }
                    }

                    sorted.forEach { session ->
                        SessionDetailRow(session)
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionDetailRow(session: AppSession) {
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(
                    if (session.isActive) ActiveGreen
                    else MaterialTheme.colorScheme.outline
                )
        )

        Spacer(modifier = Modifier.width(10.dp))

        Text(
            text = "${timeFormat.format(Date(session.startedAt))} \u2192 ${
                if (session.isActive) "Now"
                else timeFormat.format(Date(session.endedAt))
            }",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )

        Text(
            text = formatDuration(session.durationSec),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (session.isActive) ActiveGreen
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}


private fun aggregateSessions(sessions: List<AppSession>): List<AggregatedApp> {
    val map = mutableMapOf<String, AggregatedApp>()

    for (session in sessions) {
        val existing = map[session.packageName]
        if (existing == null) {
            map[session.packageName] = AggregatedApp(
                packageName = session.packageName,
                appName = session.appName,
                totalDuration = session.durationSec,
                lastUsedAt = session.endedAt,
                isActive = session.isActive
            )
        } else {
            map[session.packageName] = existing.copy(
                totalDuration = existing.totalDuration + session.durationSec,
                lastUsedAt = maxOf(existing.lastUsedAt, session.endedAt),
                isActive = existing.isActive || session.isActive
            )
        }
    }

    return map.values.sortedByDescending { it.totalDuration }
}

private data class AggregatedApp(
    val packageName: String,
    val appName: String,
    val totalDuration: Long,
    val lastUsedAt: Long,
    val isActive: Boolean
)


private fun formatTimeAgo(timestampMs: Long): String {
    val diff = System.currentTimeMillis() - timestampMs
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
    val hours = TimeUnit.MILLISECONDS.toHours(diff)

    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ${minutes % 60}m ago"
        else -> {
            val sdf = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
            sdf.format(Date(timestampMs))
        }
    }
}

private fun formatDuration(seconds: Long): String {
    if (seconds < 60) return "${seconds}s"

    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60

    return buildString {
        if (hours > 0) append("${hours}h ")
        if (minutes > 0) append("${minutes}m ")
        if (secs > 0 && hours == 0L) append("${secs}s")
    }.trim()
}
