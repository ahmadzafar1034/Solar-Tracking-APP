package com.heliossentinel.companion.data.repository

import android.content.Context
import com.heliossentinel.companion.data.local.HistoryStore
import com.heliossentinel.companion.data.location.LocationHelper
import com.heliossentinel.companion.data.model.ConnectionState
import com.heliossentinel.companion.data.model.ControlCommand
import com.heliossentinel.companion.data.model.Reading
import com.heliossentinel.companion.data.model.TrackerConfig
import com.heliossentinel.companion.data.model.TrackerMode
import com.heliossentinel.companion.data.model.TrackerStatus
import com.heliossentinel.companion.data.prefs.AppSettings
import com.heliossentinel.companion.data.prefs.SettingsRepository
import com.heliossentinel.companion.data.remote.DiscoveredTracker
import com.heliossentinel.companion.data.remote.TrackerApiService
import com.heliossentinel.companion.data.remote.TrackerDiscovery
import com.heliossentinel.companion.domain.Anomaly
import com.heliossentinel.companion.domain.AnomalyDetector
import com.heliossentinel.companion.domain.GeoLocation
import com.heliossentinel.companion.domain.Severity
import com.heliossentinel.companion.domain.SolarCalculator
import com.heliossentinel.companion.notifications.AlertNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Single source of truth for the tracker.
 *
 * Polls GET /api/status only while it's useful — when the app is on screen,
 * or when background monitoring is switched on — and on every successful
 * poll it logs the reading, runs the anomaly checks and drives alerts.
 */
class TrackerRepository(
    context: Context,
    private val historyStore: HistoryStore,
    private val settingsRepository: SettingsRepository,
    private val notifier: AlertNotifier
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _status = MutableStateFlow(TrackerStatus.EMPTY)
    val status: StateFlow<TrackerStatus> = _status.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Connecting)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _latencyMs = MutableStateFlow<Long?>(null)
    val latencyMs: StateFlow<Long?> = _latencyMs.asStateFlow()

    private val _anomalies = MutableStateFlow<List<Anomaly>>(emptyList())
    val anomalies: StateFlow<List<Anomaly>> = _anomalies.asStateFlow()

    val history: StateFlow<List<Reading>> get() = historyStore.readings
    val settingsFlow: Flow<AppSettings> = settingsRepository.settings

    @Volatile
    private var currentSettings: AppSettings = AppSettings.DEFAULT

    private val api = TrackerApiService { currentSettings.baseUrl }
    private val discovery = TrackerDiscovery(context, api)
    private val locationHelper = LocationHelper(context)

    private val uiVisible = MutableStateFlow(false)
    private val backgroundActive = MutableStateFlow(false)
    private val refreshTrigger = MutableStateFlow(0)

    private data class PollPlan(val settings: AppSettings, val active: Boolean, val refresh: Int)

    init {
        scope.launch { historyStore.load() }
        scope.launch {
            combine(settingsRepository.settings, uiVisible, backgroundActive, refreshTrigger) { s, ui, bg, r ->
                PollPlan(s, ui || bg, r)
            }.collectLatest { plan ->
                // collectLatest cancels the previous loop whenever settings/visibility change.
                currentSettings = plan.settings
                if (plan.active) pollLoop()
            }
        }
    }

    private suspend fun pollLoop() {
        _connectionState.value = ConnectionState.Connecting
        var failures = 0
        while (true) {
            val settings = currentSettings
            val started = System.nanoTime()
            api.getStatus()
                .onSuccess { status ->
                    _latencyMs.value = (System.nanoTime() - started) / 1_000_000
                    failures = 0
                    _status.value = status
                    _connectionState.value = ConnectionState.Connected
                    notifier.onOnline()
                    historyStore.append(Reading.fromStatus(status))

                    val found = AnomalyDetector.detect(
                        status.ldrTopLeft, status.ldrTopRight, status.ldrBottomLeft, status.ldrBottomRight
                    )
                    _anomalies.value = found
                    if (settings.alertsEnabled) notifier.onAnomalies(found.filter { it.severity == Severity.WARNING })
                }
                .onFailure { error ->
                    failures++
                    _latencyMs.value = null
                    val message = friendlyError(error, settings.host)
                    _connectionState.value = ConnectionState.Error(message)
                    if (settings.alertsEnabled) notifier.onOffline(failures, message)
                }
            delay(settings.pollIntervalMs.coerceAtLeast(500L))
        }
    }

    private fun friendlyError(error: Throwable, host: String): String = when (error) {
        is SocketTimeoutException -> "timed out reaching $host"
        is ConnectException -> "can't reach $host — is the phone on the tracker's WiFi?"
        is UnknownHostException -> "unknown host \"$host\""
        else -> error.message?.take(120) ?: "unable to reach tracker"
    }

    // ---- lifecycle ----
    fun setUiVisible(visible: Boolean) { uiVisible.value = visible }
    fun setBackgroundActive(active: Boolean) { backgroundActive.value = active }
    fun refreshNow() { refreshTrigger.value++ }

    // ---- commands ----
    suspend fun sendControl(command: ControlCommand): Result<Unit> =
        api.sendControl(command).map { echoed ->
            if (echoed != null) {
                _status.value = echoed
            }
            Unit
        }

    suspend fun pointAtSun(nowMs: Long = System.currentTimeMillis()): Result<Unit> {
        val s = currentSettings
        val sun = SolarCalculator.sunPosition(nowMs, s.location)
        val target = SolarCalculator.servoAnglesFor(sun, s.panCenterAzimuth, s.minAngle.toDouble(), s.maxAngle.toDouble())
            ?: return Result.failure(IllegalStateException("The sun is below the horizon right now."))
        return sendControl(ControlCommand(TrackerMode.MANUAL, target.pan.toFloat(), target.tilt.toFloat()))
    }

    /** Parks the panel flat, facing straight up — e.g. for wind or rain. */
    suspend fun stow(): Result<Unit> = sendControl(ControlCommand(TrackerMode.MANUAL, 90f, 90f))

    // ---- settings & tracker config ----
    /** Saves locally, then pushes the tracker-side values (threshold, limits) to the ESP32. */
    suspend fun saveSettings(settings: AppSettings): Result<Unit> {
        settingsRepository.update(settings)
        currentSettings = settings
        return api.updateConfig(
            TrackerConfig(settings.ldrThreshold, settings.pollIntervalMs, settings.minAngle, settings.maxAngle)
        )
    }

    /** Reads the tracker's current config and adopts it locally. */
    suspend fun loadConfigFromTracker(): Result<AppSettings> = api.getConfig().map { remote ->
        val merged = settingsRepository.settings.first().copy(
            ldrThreshold = remote.ldrThreshold,
            minAngle = remote.minAngle,
            maxAngle = remote.maxAngle
        )
        settingsRepository.update(merged)
        merged
    }

    fun setBackgroundMonitoring(enabled: Boolean) {
        scope.launch {
            settingsRepository.update(settingsRepository.settings.first().copy(backgroundMonitoring = enabled))
        }
    }

    suspend fun discoverTrackers(): List<DiscoveredTracker> = discovery.discover()

    fun hasLocationPermission(): Boolean = locationHelper.hasPermission()
    suspend fun phoneLocation(): GeoLocation? = locationHelper.currentLocation()

    suspend fun clearHistory() = historyStore.clear()
}
