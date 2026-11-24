package com.heliossentinel.companion.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.heliossentinel.companion.data.prefs.AppSettings
import com.heliossentinel.companion.data.repository.TrackerRepository
import com.heliossentinel.companion.ui.components.SectionCard
import com.heliossentinel.companion.ui.components.SimpleViewModelFactory
import java.util.Locale

@Composable
fun SettingsScreen(repository: TrackerRepository) {
    val viewModel: SettingsViewModel = viewModel(
        factory = SimpleViewModelFactory { SettingsViewModel(repository) }
    )
    val settings by viewModel.settings.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val message by viewModel.message.collectAsState()
    val discovered by viewModel.discovered.collectAsState()
    val locationFix by viewModel.locationFix.collectAsState()

    var host by remember { mutableStateOf(settings.host) }
    var port by remember { mutableStateOf(settings.port.toString()) }
    var pollInterval by remember { mutableStateOf(settings.pollIntervalMs.toString()) }
    var threshold by remember { mutableStateOf(settings.ldrThreshold.toString()) }
    var minAngle by remember { mutableStateOf(settings.minAngle.toString()) }
    var maxAngle by remember { mutableStateOf(settings.maxAngle.toString()) }
    var useHttps by remember { mutableStateOf(settings.useHttps) }
    var latitude by remember { mutableStateOf(settings.latitude.toString()) }
    var longitude by remember { mutableStateOf(settings.longitude.toString()) }
    var panCenter by remember { mutableStateOf(settings.panCenterAzimuth.toString()) }
    var alertsEnabled by remember { mutableStateOf(settings.alertsEnabled) }
    var backgroundMonitoring by remember { mutableStateOf(settings.backgroundMonitoring) }

    // Mirror stored settings into the fields whenever they change (first load, discovery, "load from tracker").
    LaunchedEffect(settings) {
        host = settings.host
        port = settings.port.toString()
        pollInterval = settings.pollIntervalMs.toString()
        threshold = settings.ldrThreshold.toString()
        minAngle = settings.minAngle.toString()
        maxAngle = settings.maxAngle.toString()
        useHttps = settings.useHttps
        latitude = settings.latitude.toString()
        longitude = settings.longitude.toString()
        panCenter = settings.panCenterAzimuth.toString()
        alertsEnabled = settings.alertsEnabled
        backgroundMonitoring = settings.backgroundMonitoring
    }

    LaunchedEffect(locationFix) {
        locationFix?.let {
            latitude = String.format(Locale.US, "%.4f", it.latitude)
            longitude = String.format(Locale.US, "%.4f", it.longitude)
            viewModel.showMessage("Location filled in — tap Save to keep it.")
            viewModel.consumeLocationFix()
        }
    }

    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.fetchPhoneLocation()
        else viewModel.showMessage("Location permission denied — you can still type the coordinates in.")
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) viewModel.showMessage("Notifications are blocked, so alerts won't show. You can allow them in Android settings.")
    }

    fun buildSettings(): AppSettings? {
        val d = AppSettings.DEFAULT
        val minA = minAngle.toFloatOrNull()
        val maxA = maxAngle.toFloatOrNull()
        val lat = latitude.toDoubleOrNull()
        val lon = longitude.toDoubleOrNull()
        val center = panCenter.toDoubleOrNull()
        val problem = when {
            host.isBlank() -> "Enter the tracker's IP address or hostname."
            minA == null || maxA == null || minA !in 0f..180f || maxA !in 0f..180f || minA >= maxA ->
                "Angle limits must be between 0 and 180, with min below max."
            lat == null || lat !in -90.0..90.0 -> "Latitude must be between -90 and 90."
            lon == null || lon !in -180.0..180.0 -> "Longitude must be between -180 and 180."
            center == null || center !in 0.0..360.0 -> "Panel direction must be between 0 and 360 degrees."
            else -> null
        }
        if (problem != null) {
            viewModel.showMessage(problem)
            return null
        }
        return AppSettings(
            host = host.trim(),
            port = port.toIntOrNull()?.takeIf { it in 1..65535 } ?: d.port,
            pollIntervalMs = (pollInterval.toLongOrNull() ?: d.pollIntervalMs).coerceIn(500L, 60_000L),
            ldrThreshold = (threshold.toIntOrNull() ?: d.ldrThreshold).coerceIn(0, 4095),
            minAngle = minA!!,
            maxAngle = maxA!!,
            useHttps = useHttps,
            latitude = lat!!,
            longitude = lon!!,
            panCenterAzimuth = center!!,
            alertsEnabled = alertsEnabled,
            backgroundMonitoring = backgroundMonitoring
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { Text(text = "Settings", style = MaterialTheme.typography.headlineMedium) }

        if (busy != null || message != null) {
            item {
                SectionCard(title = busy ?: "Status") {
                    if (busy != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Text("Please wait…")
                        }
                    }
                    message?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { viewModel.clearMessage() }) { Text("OK") }
                    }
                }
            }
        }

        item {
            SectionCard(title = "Tracker connection") {
                OutlinedButton(
                    onClick = { viewModel.discover() },
                    enabled = busy == null,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Find tracker on this network") }

                discovered?.takeIf { it.isNotEmpty() }?.let { list ->
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        Text("Tap one to use it:", style = MaterialTheme.typography.labelLarge)
                        list.forEach { t ->
                            Text(
                                text = "${t.name} — ${t.host}:${t.port} (${t.source})",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.useTracker(t) }
                                    .padding(vertical = 10.dp)
                            )
                            HorizontalDivider()
                        }
                    }
                }

                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("ESP32 IP address or hostname") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit) },
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                SwitchRow("Use HTTPS (only if your firmware serves it)", useHttps) { useHttps = it }
            }
        }

        item {
            SectionCard(title = "Tracking & calibration") {
                OutlinedTextField(
                    value = threshold,
                    onValueChange = { threshold = it.filter(Char::isDigit) },
                    label = { Text("LDR difference threshold") },
                    supportingText = { Text("Bigger = steadier but less precise tracking") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = minAngle, onValueChange = { minAngle = it }, label = { Text("Min angle") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = maxAngle, onValueChange = { maxAngle = it }, label = { Text("Max angle") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(
                    value = pollInterval,
                    onValueChange = { pollInterval = it.filter(Char::isDigit) },
                    label = { Text("App refresh interval (ms)") },
                    supportingText = { Text("500 – 60000. Also sets how often readings are logged.") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                OutlinedButton(
                    onClick = { viewModel.loadFromTracker() },
                    enabled = busy == null,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) { Text("Load current values from tracker") }
            }
        }

        item {
            SectionCard(title = "Location & panel orientation") {
                Text(
                    "Used to calculate where the sun is, the ideal servo angles and the tracking error.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = latitude, onValueChange = { latitude = it }, label = { Text("Latitude") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = longitude, onValueChange = { longitude = it }, label = { Text("Longitude") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedButton(
                    onClick = {
                        if (viewModel.hasLocationPermission()) viewModel.fetchPhoneLocation()
                        else locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                    },
                    enabled = busy == null,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) { Text("Use this phone's location") }
                OutlinedTextField(
                    value = panCenter,
                    onValueChange = { panCenter = it },
                    label = { Text("Panel direction at pan 90° (compass °)") },
                    supportingText = { Text("0 = north, 90 = east, 180 = south, 270 = west") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
        }

        item {
            SectionCard(title = "Alerts & monitoring") {
                SwitchRow("Alerts (tracker offline, sensor problems)", alertsEnabled) { enabled ->
                    alertsEnabled = enabled
                    if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                SwitchRow("Keep monitoring in the background", backgroundMonitoring) { backgroundMonitoring = it }
                Text(
                    "Background monitoring keeps logging and alerting while the app is closed, and shows a " +
                        "notification while it runs. It uses more battery, so turn it on when you're collecting data.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        item {
            Button(
                onClick = { buildSettings()?.let(viewModel::save) },
                enabled = busy == null,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save settings") }
        }

        item {
            Text(
                "Tip: the ESP32's own hotspot is \"HeliosSentinel\" at 192.168.4.1. If it joins your home WiFi " +
                    "instead, use Find tracker or check the Arduino Serial Monitor for its IP address.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
