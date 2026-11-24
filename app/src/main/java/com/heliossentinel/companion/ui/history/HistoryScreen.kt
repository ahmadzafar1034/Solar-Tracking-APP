package com.heliossentinel.companion.ui.history

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.heliossentinel.companion.data.repository.TrackerRepository
import com.heliossentinel.companion.domain.HistoryStats
import com.heliossentinel.companion.domain.SensorStats
import com.heliossentinel.companion.ui.components.ChartSeries
import com.heliossentinel.companion.ui.components.MultiLineChart
import com.heliossentinel.companion.ui.components.SectionCard
import com.heliossentinel.companion.ui.components.SimpleViewModelFactory
import com.heliossentinel.companion.ui.theme.DangerRed
import com.heliossentinel.companion.ui.theme.SkyBlue
import com.heliossentinel.companion.ui.theme.SunAmber
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(repository: TrackerRepository) {
    val viewModel: HistoryViewModel = viewModel(
        factory = SimpleViewModelFactory { HistoryViewModel(repository) }
    )
    val ui by viewModel.uiState.collectAsState()
    val exporting by viewModel.exporting.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmClear by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = viewModel.exportCsv(context.contentResolver, uri)
            val text = result.fold(
                onSuccess = { "Exported $it readings." },
                onFailure = { "Export failed: ${it.message}" }
            )
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        }
    }

    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val startLabel = ui.chartReadings.firstOrNull()?.let { timeFormat.format(Date(it.timestampMs)) }
    val endLabel = ui.chartReadings.lastOrNull()?.let { timeFormat.format(Date(it.timestampMs)) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { Text(text = "History", style = MaterialTheme.typography.headlineMedium) }

        item {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TimeRange.entries.forEach { range ->
                    FilterChip(
                        selected = ui.range == range,
                        onClick = { viewModel.setRange(range) },
                        label = { Text(range.label) }
                    )
                }
            }
        }

        if (ui.readings.isEmpty()) {
            item {
                SectionCard(title = "No data in this range") {
                    Text(
                        if (ui.totalLogged == 0) "Readings appear here once the app starts polling the tracker."
                        else "${ui.totalLogged} readings are logged, but none in the last ${ui.range.label}. Try a wider range."
                    )
                }
            }
        } else {
            ui.stats?.let { stats -> item { StatsCard(stats) } }

            item {
                SectionCard(title = "Panel angle") {
                    MultiLineChart(
                        series = listOf(
                            ChartSeries("Pan (X)", SunAmber, ui.chartReadings.map { it.angleX }),
                            ChartSeries("Tilt (Y)", SkyBlue, ui.chartReadings.map { it.angleY })
                        ),
                        yLabelFormatter = { "${it.toInt()}°" },
                        startLabel = startLabel,
                        endLabel = endLabel
                    )
                }
            }

            item {
                SectionCard(title = "Tracking error (degrees off the sun)") {
                    MultiLineChart(
                        series = listOf(ChartSeries("Off target", DangerRed, ui.chartErrors)),
                        yLabelFormatter = { String.format(Locale.US, "%.1f°", it) },
                        startLabel = startLabel,
                        endLabel = endLabel
                    )
                    Text(
                        "Gaps are night-time. Accuracy depends on the location and panel orientation set in Settings.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }

            item {
                SectionCard(title = "Light sensors") {
                    MultiLineChart(
                        series = listOf(
                            ChartSeries("Top-left", Color(0xFFE05B5B), ui.chartReadings.map { it.ldrTopLeft.toFloat() }),
                            ChartSeries("Top-right", Color(0xFF59C28A), ui.chartReadings.map { it.ldrTopRight.toFloat() }),
                            ChartSeries("Bottom-left", Color(0xFF8E7CC3), ui.chartReadings.map { it.ldrBottomLeft.toFloat() }),
                            ChartSeries("Bottom-right", Color(0xFF4FA8D8), ui.chartReadings.map { it.ldrBottomRight.toFloat() })
                        ),
                        startLabel = startLabel,
                        endLabel = endLabel
                    )
                }
            }

            item {
                SectionCard(title = "Export & manage") {
                    Text(
                        "Export the ${ui.readings.size} readings in this range as CSV (with sun position and tracking error " +
                            "columns) for pandas or Excel.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(
                        onClick = {
                            val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                            exportLauncher.launch("helios_sentinel_$stamp.csv")
                        },
                        enabled = !exporting,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    ) { Text(if (exporting) "Exporting…" else "Export CSV") }
                    OutlinedButton(
                        onClick = { confirmClear = true },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    ) { Text("Clear all history") }
                    Text(
                        "${ui.totalLogged} readings stored on this phone (keeps the latest 10,000).",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear all history?") },
            text = { Text("This deletes all ${ui.totalLogged} logged readings from this phone. Export a CSV first if you need them.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearHistory()
                    confirmClear = false
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun StatsCard(stats: HistoryStats) {
    SectionCard(title = "Summary") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StatLine("Readings", "${stats.count} over ${formatDuration(stats.spanMs)}")
            StatLine("Panel movement", String.format(Locale.US, "%.0f° total", stats.totalMovementDeg))
            StatLine("Time in manual mode", "${(stats.manualFraction * 100).toInt()}%")
            StatLine("Average total light", String.format(Locale.US, "%.0f", stats.averageTotalLight))
            stats.averageTrackingErrorDeg?.let { avg ->
                StatLine(
                    "Tracking error",
                    String.format(Locale.US, "%.1f° avg · %.1f° worst", avg, stats.maxTrackingErrorDeg ?: avg)
                )
            }
            Text("Per sensor (min / avg / max)", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            StatLine("Top-left", sensorLine(stats.topLeft))
            StatLine("Top-right", sensorLine(stats.topRight))
            StatLine("Bottom-left", sensorLine(stats.bottomLeft))
            StatLine("Bottom-right", sensorLine(stats.bottomRight))
        }
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun sensorLine(s: SensorStats) = String.format(Locale.US, "%d / %.0f / %d", s.min, s.average, s.max)

private fun formatDuration(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        minutes < 1 -> "${ms / 1000} s"
        minutes < 60 -> "$minutes min"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }
}
