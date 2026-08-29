package com.example.udid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.udid.ui.theme.UsageHigh
import com.example.udid.ui.theme.UsageLow
import com.example.udid.ui.theme.UsageMedium

/**
 * Ultra-compact MPI banner — a single thin row, not a card.
 *
 * Layout:  [dot] 82  ↑+3 vs yesterday  ·  Instagram over limit  ·  Mon 80 · Tue 76 ...
 *
 * When no apps configured: a short prompt text.
 */
@Composable
fun MpiScoreSection(
    todayScore: Int?,
    yesterdayScore: Int?,
    dominantReason: String?,
    trend: List<Pair<String, Int>>,
    hasDistractingApps: Boolean,
    onOpenSetup: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when {
            !hasDistractingApps -> {
                Text(
                    text = "Set up distracting apps for MPI \u2022 Tap \u2699\uFE0F",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    maxLines = 1
                )
            }
            todayScore == null -> {
                Text(
                    text = "Load data to see your MPI score",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    maxLines = 1
                )
            }
            else -> {
                // Score dot + number
                val scoreColor = when {
                    todayScore >= 80 -> UsageLow
                    todayScore >= 50 -> UsageMedium
                    else -> UsageHigh
                }
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(scoreColor)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "$todayScore MPI",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )

                // Change from yesterday
                if (yesterdayScore != null) {
                    val change = todayScore - yesterdayScore
                    if (change != 0) {
                        Spacer(modifier = Modifier.width(6.dp))
                        val arrow = if (change > 0) "\u2191" else "\u2193"
                        val changeColor = if (change > 0) UsageLow else UsageHigh
                        Text(
                            text = "$arrow${kotlin.math.abs(change)}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = changeColor
                        )
                    }
                }

                // Reason (truncated)
                if (dominantReason != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "\u00B7",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.4f)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = dominantReason,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }

                // Mini trend dots
                if (trend.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    trend.takeLast(5).forEach { (dayLabel, score) ->
                        val dotColor = when {
                            score >= 80 -> UsageLow
                            score >= 50 -> UsageMedium
                            else -> UsageHigh
                        }
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 1.5.dp)
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(dotColor)
                        )
                    }
                }
            }
        }
    }
}
