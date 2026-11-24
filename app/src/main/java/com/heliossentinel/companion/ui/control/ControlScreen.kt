package com.heliossentinel.companion.ui.control

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.heliossentinel.companion.data.model.TrackerMode
import com.heliossentinel.companion.data.repository.TrackerRepository
import com.heliossentinel.companion.ui.components.ConnectionBanner
import com.heliossentinel.companion.ui.components.SectionCard
import com.heliossentinel.companion.ui.components.SimpleViewModelFactory
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlScreen(repository: TrackerRepository) {
    val viewModel: ControlViewModel = viewModel(
        factory = SimpleViewModelFactory { ControlViewModel(repository) }
    )
    val ui by viewModel.uiState.collectAsState()
    val isSending by viewModel.isSending.collectAsState()
    val message by viewModel.message.collectAsState()

    // Show what the user is dragging; fall back to the tracker's reported angle.
    // (Keying local state on the polled angle made the slider snap back every poll.)
    val angleX = ui.pendingAngleX ?: ui.status.angleX
    val angleY = ui.pendingAngleY ?: ui.status.angleY
    val minA = ui.settings.minAngle
    val maxA = ui.settings.maxAngle
    val angleRange = if (minA < maxA) minA..maxA else 0f..180f
    val manual = ui.status.mode == TrackerMode.MANUAL
    val canCommand = ui.isConnected && !isSending

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { Text(text = "Control", style = MaterialTheme.typography.headlineMedium) }
        item { ConnectionBanner(state = ui.connectionState) }

        message?.let { text ->
            item {
                SectionCard(title = "Command failed") {
                    Text(text = text, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = { viewModel.clearMessage() }, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Dismiss")
                    }
                }
            }
        }

        item {
            SectionCard(title = "Mode") {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = !manual,
                        onClick = { viewModel.setMode(TrackerMode.AUTO) },
                        enabled = canCommand,
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                    ) { Text("Auto-track") }
                    SegmentedButton(
                        selected = manual,
                        onClick = { viewModel.setMode(TrackerMode.MANUAL) },
                        enabled = canCommand,
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                    ) { Text("Manual") }
                }
                Text(
                    text = if (manual) "The tracker holds whatever angles you send it."
                    else "The tracker follows the light sensors on its own.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        item {
            SectionCard(title = "Quick actions") {
                val target = ui.sunTarget
                Button(
                    onClick = { viewModel.pointAtSun() },
                    enabled = canCommand && target != null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.WbSunny, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Point at the sun")
                }
                Text(
                    text = target?.let {
                        String.format(Locale.US, "Aims using the calculated sun position: pan %.0f°, tilt %.0f°.", it.pan, it.tilt)
                    } ?: "The sun is below the horizon, so there's nothing to point at.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )
                OutlinedButton(onClick = { viewModel.stow() }, enabled = canCommand, modifier = Modifier.fillMaxWidth()) {
                    Text("Stow (flat, facing up)")
                }
                Text(
                    "Parks the panel at pan 90°, tilt 90° — useful in wind or rain. Switches to manual mode.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        item {
            SectionCard(title = "Pan / tilt angles") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Pan (X axis): ${angleX.toInt()}°")
                    Slider(
                        value = angleX.coerceIn(angleRange.start, angleRange.endInclusive),
                        onValueChange = { viewModel.updatePendingAngles(it, angleY) },
                        valueRange = angleRange,
                        enabled = manual && ui.isConnected
                    )
                    Text("Tilt (Y axis): ${angleY.toInt()}°")
                    Slider(
                        value = angleY.coerceIn(angleRange.start, angleRange.endInclusive),
                        onValueChange = { viewModel.updatePendingAngles(angleX, it) },
                        valueRange = angleRange,
                        enabled = manual && ui.isConnected
                    )
                    Button(
                        onClick = { viewModel.applyManualAngles() },
                        enabled = manual && canCommand,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isSending) "Sending…" else "Apply angles")
                    }
                    if (!manual) {
                        Text(
                            text = "Switch to Manual to move the panel with the sliders.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        }
    }
}
