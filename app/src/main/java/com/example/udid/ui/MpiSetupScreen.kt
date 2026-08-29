package com.example.udid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.udid.data.DistractingAppRepository
import kotlin.math.roundToInt

/**
 * MPI setup screen: lets the user mark apps as distracting and set
 * per-app daily usage limits.
 *
 * Reached via the gear icon in the dashboard header. Navigating back
 * saves all changes (each toggle/slider change writes to Room immediately).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MpiSetupScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val viewModel: MpiSetupViewModel = viewModel(
        key = "mpi_setup",
        factory = MpiSetupViewModel.factory(
            DistractingAppRepository(
                com.example.udid.data.AppDatabase
                    .getInstance(context).distractingAppConfigDao()
            ),
            context
        )
    )

    val uiState by viewModel.uiState.collectAsState()

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        TopAppBar(
            title = {
                Text(
                    text = "MPI Setup",
                    fontWeight = FontWeight.SemiBold
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Text(
                        text = "\u25C0",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        )

        // Subtitle
        Text(
            text = "Mark the apps you find distracting and set a comfortable daily limit for each.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Count badge
        val enabledCount = uiState.apps.count { it.isDistracting }
        Text(
            text = "$enabledCount app${if (enabledCount == 1) "" else "s"} marked as distracting",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        if (uiState.isLoading) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(40.dp), strokeWidth = 4.dp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(
                    uiState.apps,
                    key = { it.packageName }
                ) { entry ->
                    AppConfigCard(
                        entry = entry,
                        onToggle = { viewModel.toggleApp(entry.packageName) },
                        onLimitChange = { newMinutes ->
                            viewModel.updateLimit(entry.packageName, newMinutes)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AppConfigCard(
    entry: AppSetupEntry,
    onToggle: () -> Unit,
    onLimitChange: (Int) -> Unit
) {
    val context = LocalContext.current
    val appIcon: ImageBitmap? = remember(entry.packageName) {
        try {
            context.packageManager.getApplicationInfo(entry.packageName, 0)
                .loadIcon(context.packageManager)
                .toBitmap(96, 96)
                .asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (entry.isDistracting)
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // App row: icon + name + checkbox
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // App icon
                if (appIcon != null) {
                    Image(
                        bitmap = appIcon,
                        contentDescription = null,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                    )
                } else {
                    Text(
                        text = "\uD83D\uDCE6",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier
                            .size(44.dp)
                            .padding(4.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // App name
                Text(
                    text = entry.appName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                // Toggle checkbox
                Checkbox(
                    checked = entry.isDistracting,
                    onCheckedChange = { onToggle() },
                    colors = CheckboxDefaults.colors(
                        checkedColor = MaterialTheme.colorScheme.primary
                    )
                )
            }

            // Limit slider (only visible when enabled)
            if (entry.isDistracting) {
                Spacer(modifier = Modifier.height(8.dp))

                var sliderValue by remember(entry.dailyLimitMinutes) {
                    mutableFloatStateOf(entry.dailyLimitMinutes.toFloat())
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Limit:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(42.dp)
                    )

                    Slider(
                        value = sliderValue,
                        onValueChange = { sliderValue = it },
                        onValueChangeFinished = {
                            onLimitChange(sliderValue.roundToInt())
                        },
                        valueRange = 5f..180f,
                        steps = 34,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary
                        )
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = "${sliderValue.roundToInt()}m",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(42.dp)
                    )
                }
            }
        }
    }
}
