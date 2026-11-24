package com.heliossentinel.companion.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.heliossentinel.companion.data.model.TrackerMode
import com.heliossentinel.companion.data.repository.TrackerRepository
import com.heliossentinel.companion.domain.AnomalyDetector
import com.heliossentinel.companion.domain.Severity
import com.heliossentinel.companion.ui.components.ConnectionBanner
import com.heliossentinel.companion.ui.components.LightGrid
import com.heliossentinel.companion.ui.components.MetricTile
import com.heliossentinel.companion.ui.components.SectionCard
import com.heliossentinel.companion.ui.components.SimpleViewModelFactory
import com.heliossentinel.companion.ui.components.SkyCompass
import com.heliossentinel.companion.ui.theme.DangerRed
import com.heliossentinel.companion.ui.theme.SuccessGreen
import com.heliossentinel.companion.ui.theme.SunAmber
import java.util.Locale
import kotlin.math.abs

@Composable
fun DashboardScreen(repository: TrackerRepository) {
    val viewModel: DashboardViewModel = viewModel(
        factory = SimpleViewModelFactory { DashboardViewModel(repository) }
    )
    val ui by viewModel.uiState.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Helios Sentinel", style = MaterialTheme.typography.headlineMedium)
                IconButton(onClick = { viewModel.refresh() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ConnectionBanner(state = ui.connectionState)
                ui.latencyMs?.let {
                    Text(
                        "Response time: $it ms · ${ui.settings.host}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
        }

        val warnings = ui.anomalies.filter { it.severity == Severity.WARNING }
        if (ui.isConnected && ui.anomalies.isNotEmpty()) {
            item {
                SectionCard(title = if (warnings.isNotEmpty()) "Sensor warnings" else "Sensor notes") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ui.anomalies.forEach { a ->
                            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(
                                    Icons.Filled.Warning, contentDescription = null,
                                    tint = if (a.severity == Severity.WARNING) DangerRed else SunAmber
                                )
                                Text(a.message, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }

        item {
            SectionCard(title = "Tracking") {
                Text(
                    text = if (ui.status.mode == TrackerMode.AUTO) "Automatic" else "Manual override",
                    style = MaterialTheme.typography.titleLarge
                )
                Text("Tracker uptime: ${formatUptime(ui.status.uptimeMs)}", style = MaterialTheme.typography.bodyMedium)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MetricTile("Pan (X)", "${ui.status.angleX.toInt()}°", Modifier.weight(1f))
                    MetricTile("Tilt (Y)", "${ui.status.angleY.toInt()}°", Modifier.weight(1f))
                    val error = ui.trackingErrorDeg
                    MetricTile(
                        label = "Off target",
                        value = error?.let { String.format(Locale.US, "%.1f°", it) } ?: "—",
                        modifier = Modifier.weight(1f),
                        accent = when {
                            error == null -> MaterialTheme.colorScheme.primary
                            error < 5 -> SuccessGreen
                            error < 15 -> SunAmber
                            else -> DangerRed
                        }
                    )
                }
            }
        }

        item {
            SectionCard(title = "Sun position") {
                val sun = ui.sun
                if (sun == null || !sun.isAboveHorizon) {
                    Text("The sun is below the horizon right now.", style = MaterialTheme.typography.bodyLarge)
                } else {
                    Text(
                        String.format(
                            Locale.US, "Azimuth %.1f° · Elevation %.1f°", sun.azimuthDeg, sun.elevationDeg
                        ),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    ui.sunTarget?.let {
                        Text(
                            String.format(Locale.US, "Ideal servo angles: pan %.0f°, tilt %.0f°", it.pan, it.tilt),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                SkyCompass(
                    sun = sun,
                    panel = ui.panelDirection,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                )
                Text(
                    String.format(
                        Locale.US, "For %.4f, %.4f · panel faces %.0f° at pan 90°",
                        ui.settings.latitude, ui.settings.longitude, ui.settings.panCenterAzimuth
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        item {
            SectionCard(title = "Light sensors (LDR)") {
                LightGrid(
                    tl = ui.status.ldrTopLeft,
                    tr = ui.status.ldrTopRight,
                    bl = ui.status.ldrBottomLeft,
                    br = ui.status.ldrBottomRight,
                    adcMax = AnomalyDetector.ADC_MAX
                )
                Text(
                    text = describeBalance(ui.balance.horizontal, ui.balance.vertical, ui.balance.total),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

private fun describeBalance(horizontal: Double, vertical: Double, total: Int): String {
    if (total == 0) return "No light detected."
    val parts = buildList {
        if (abs(horizontal) >= 0.02) add("${(abs(horizontal) * 100).toInt()}% brighter on the ${if (horizontal > 0) "right" else "left"}")
        if (abs(vertical) >= 0.02) add("${(abs(vertical) * 100).toInt()}% brighter on the ${if (vertical > 0) "top" else "bottom"}")
    }
    return if (parts.isEmpty()) "Light is balanced: the panel is facing the brightest direction." else parts.joinToString(", ").replaceFirstChar { it.uppercase() } + "."
}

private fun formatUptime(uptimeMs: Long): String {
    val totalSeconds = uptimeMs / 1000
    return String.format(Locale.US, "%02d:%02d:%02d", totalSeconds / 3600, (totalSeconds % 3600) / 60, totalSeconds % 60)
}
