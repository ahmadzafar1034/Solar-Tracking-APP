package com.heliossentinel.companion.ui.control

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heliossentinel.companion.data.model.ConnectionState
import com.heliossentinel.companion.data.model.ControlCommand
import com.heliossentinel.companion.data.model.TrackerMode
import com.heliossentinel.companion.data.model.TrackerStatus
import com.heliossentinel.companion.data.prefs.AppSettings
import com.heliossentinel.companion.data.repository.TrackerRepository
import com.heliossentinel.companion.domain.ServoAngles
import com.heliossentinel.companion.domain.SolarCalculator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ControlUiState(
    val status: TrackerStatus = TrackerStatus.EMPTY,
    val connectionState: ConnectionState = ConnectionState.Connecting,
    val settings: AppSettings = AppSettings.DEFAULT,
    val pendingAngleX: Float? = null,
    val pendingAngleY: Float? = null,
    val sunTarget: ServoAngles? = null
) {
    val isConnected: Boolean get() = connectionState is ConnectionState.Connected
}

class ControlViewModel(private val repository: TrackerRepository) : ViewModel() {

    private val _pending = MutableStateFlow(Pair<Float?, Float?>(null, null))
    private val _isSending = MutableStateFlow(false)
    private val _message = MutableStateFlow<String?>(null)

    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()
    /** Last command error (or info), shown until dismissed. */
    val message: StateFlow<String?> = _message.asStateFlow()

    val uiState: StateFlow<ControlUiState> = combine(
        repository.status,
        repository.connectionState,
        repository.settingsFlow,
        _pending
    ) { status, connection, settings, pending ->
        val sun = SolarCalculator.sunPosition(System.currentTimeMillis(), settings.location)
        ControlUiState(
            status = status,
            connectionState = connection,
            settings = settings,
            pendingAngleX = pending.first,
            pendingAngleY = pending.second,
            sunTarget = SolarCalculator.servoAnglesFor(
                sun, settings.panCenterAzimuth, settings.minAngle.toDouble(), settings.maxAngle.toDouble()
            )
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ControlUiState()
    )

    fun updatePendingAngles(angleX: Float, angleY: Float) {
        _pending.value = angleX to angleY
    }

    fun setMode(mode: TrackerMode) {
        _pending.value = null to null
        val current = uiState.value.status
        val command = if (mode == TrackerMode.MANUAL) {
            ControlCommand(mode = mode, angleX = current.angleX, angleY = current.angleY)
        } else {
            ControlCommand(mode = mode)
        }
        launchCommand { repository.sendControl(command) }
    }

    fun applyManualAngles() {
        val pending = _pending.value
        val angleX = pending.first ?: uiState.value.status.angleX
        val angleY = pending.second ?: uiState.value.status.angleY
        launchCommand(onSuccess = { _pending.value = null to null }) {
            repository.sendControl(ControlCommand(mode = TrackerMode.MANUAL, angleX = angleX, angleY = angleY))
        }
    }

    fun pointAtSun() {
        launchCommand(onSuccess = { _pending.value = null to null }) { repository.pointAtSun() }
    }

    fun stow() {
        launchCommand(onSuccess = { _pending.value = null to null }) { repository.stow() }
    }

    fun clearMessage() {
        _message.value = null
    }

    private fun launchCommand(onSuccess: () -> Unit = {}, block: suspend () -> Result<Unit>) {
        if (_isSending.value) return
        viewModelScope.launch {
            _isSending.value = true
            block()
                .onSuccess { onSuccess() }
                .onFailure { _message.value = it.message ?: "Command failed" }
            _isSending.value = false
        }
    }
}
