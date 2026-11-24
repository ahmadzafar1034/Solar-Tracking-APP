package com.heliossentinel.companion.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heliossentinel.companion.data.prefs.AppSettings
import com.heliossentinel.companion.data.remote.DiscoveredTracker
import com.heliossentinel.companion.data.repository.TrackerRepository
import com.heliossentinel.companion.domain.GeoLocation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val repository: TrackerRepository) : ViewModel() {

    val settings: StateFlow<AppSettings> = repository.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AppSettings.DEFAULT
    )

    private val _busy = MutableStateFlow<String?>(null)
    /** Non-null while a long operation runs; holds a short label for it. */
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _discovered = MutableStateFlow<List<DiscoveredTracker>?>(null)
    val discovered: StateFlow<List<DiscoveredTracker>?> = _discovered.asStateFlow()

    /** One-shot location result for the screen to copy into its fields. */
    private val _locationFix = MutableStateFlow<GeoLocation?>(null)
    val locationFix: StateFlow<GeoLocation?> = _locationFix.asStateFlow()

    fun save(settings: AppSettings) = work("Saving…") {
        repository.saveSettings(settings).fold(
            onSuccess = { "Saved, and the tracker's threshold and angle limits were updated." },
            onFailure = { "Saved on this phone, but the tracker couldn't be updated (${it.message}). It will use its old values until you save again while connected." }
        )
    }

    fun loadFromTracker() = work("Reading tracker config…") {
        repository.loadConfigFromTracker().fold(
            onSuccess = { "Loaded the threshold and angle limits from the tracker." },
            onFailure = { "Couldn't read the tracker's config: ${it.message}" }
        )
    }

    fun discover() = work("Searching the network…") {
        val found = repository.discoverTrackers()
        _discovered.value = found
        if (found.isEmpty()) {
            "No tracker found. Make sure the phone is on the same WiFi as the ESP32 (or its \"HeliosSentinel\" hotspot)."
        } else null
    }

    fun useTracker(tracker: DiscoveredTracker) {
        viewModelScope.launch {
            repository.saveSettings(settings.value.copy(host = tracker.host, port = tracker.port))
            _discovered.value = null
            _message.value = "Now using ${tracker.name} at ${tracker.host}:${tracker.port}."
        }
    }

    fun fetchPhoneLocation() = work("Getting location…") {
        val fix = repository.phoneLocation()
        if (fix == null) {
            "Couldn't get a location. Check that location is switched on, or type the coordinates in."
        } else {
            _locationFix.value = fix
            null
        }
    }

    fun consumeLocationFix() {
        _locationFix.value = null
    }

    fun hasLocationPermission() = repository.hasLocationPermission()

    fun showMessage(text: String) {
        _message.value = text
    }

    fun clearMessage() {
        _message.value = null
    }

    private fun work(label: String, block: suspend () -> String?) {
        if (_busy.value != null) return
        viewModelScope.launch {
            _busy.value = label
            try {
                block()?.let { _message.value = it }
            } finally {
                _busy.value = null
            }
        }
    }
}
