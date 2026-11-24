package com.heliossentinel.companion.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heliossentinel.companion.data.model.ConnectionState
import com.heliossentinel.companion.data.model.TrackerStatus
import com.heliossentinel.companion.data.prefs.AppSettings
import com.heliossentinel.companion.data.repository.TrackerRepository
import com.heliossentinel.companion.domain.Anomaly
import com.heliossentinel.companion.domain.LightBalance
import com.heliossentinel.companion.domain.ServoAngles
import com.heliossentinel.companion.domain.SolarCalculator
import com.heliossentinel.companion.domain.SunPosition
import com.heliossentinel.companion.domain.TrackingAnalytics
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

data class DashboardUiState(
    val status: TrackerStatus = TrackerStatus.EMPTY,
    val connectionState: ConnectionState = ConnectionState.Connecting,
    val latencyMs: Long? = null,
    val anomalies: List<Anomaly> = emptyList(),
    val settings: AppSettings = AppSettings.DEFAULT,
    val sun: SunPosition? = null,
    val sunTarget: ServoAngles? = null,
    val panelDirection: SunPosition? = null,
    val trackingErrorDeg: Double? = null,
    val balance: LightBalance = LightBalance(0.0, 0.0, 0)
) {
    val isConnected: Boolean get() = connectionState is ConnectionState.Connected
}

class DashboardViewModel(private val repository: TrackerRepository) : ViewModel() {

    /** Re-evaluates the sun position every 30 s even if nothing else changes. */
    private val clock = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(30_000L)
        }
    }

    private val live = combine(
        repository.status,
        repository.connectionState,
        repository.latencyMs,
        repository.anomalies
    ) { status, connection, latency, anomalies -> Live(status, connection, latency, anomalies) }

    private data class Live(
        val status: TrackerStatus,
        val connection: ConnectionState,
        val latencyMs: Long?,
        val anomalies: List<Anomaly>
    )

    val uiState: StateFlow<DashboardUiState> = combine(live, repository.settingsFlow, clock) { l, settings, clockMs ->
        // Use the newer of the ticker and "now" so a fresh poll also refreshes the sun.
        val now = maxOf(clockMs, System.currentTimeMillis())
        val sun = SolarCalculator.sunPosition(now, settings.location)
        val connected = l.connection is ConnectionState.Connected
        val panel = if (connected) {
            SolarCalculator.panelDirection(l.status.angleX.toDouble(), l.status.angleY.toDouble(), settings.panCenterAzimuth)
        } else null
        DashboardUiState(
            status = l.status,
            connectionState = l.connection,
            latencyMs = l.latencyMs,
            anomalies = l.anomalies,
            settings = settings,
            sun = sun,
            sunTarget = SolarCalculator.servoAnglesFor(
                sun, settings.panCenterAzimuth, settings.minAngle.toDouble(), settings.maxAngle.toDouble()
            ),
            panelDirection = panel,
            trackingErrorDeg = if (panel != null && sun.isAboveHorizon) SolarCalculator.angularSeparation(panel, sun) else null,
            balance = TrackingAnalytics.lightBalance(
                l.status.ldrTopLeft, l.status.ldrTopRight, l.status.ldrBottomLeft, l.status.ldrBottomRight
            )
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = DashboardUiState()
    )

    fun refresh() = repository.refreshNow()
}
