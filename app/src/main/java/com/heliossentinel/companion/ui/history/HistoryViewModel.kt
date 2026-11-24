package com.heliossentinel.companion.ui.history

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heliossentinel.companion.data.model.Reading
import com.heliossentinel.companion.data.prefs.AppSettings
import com.heliossentinel.companion.data.repository.TrackerRepository
import com.heliossentinel.companion.domain.CsvExporter
import com.heliossentinel.companion.domain.HistoryStats
import com.heliossentinel.companion.domain.SolarCalculator
import com.heliossentinel.companion.domain.TrackingAnalytics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class TimeRange(val label: String, val durationMs: Long?) {
    MIN_15("15 min", 15 * 60_000L),
    HOUR_1("1 h", 60 * 60_000L),
    HOUR_6("6 h", 6 * 60 * 60_000L),
    ALL("All", null)
}

data class HistoryUiState(
    val range: TimeRange = TimeRange.HOUR_1,
    val readings: List<Reading> = emptyList(),
    /** Downsampled copy for drawing; [readings] is the full set used for stats/export. */
    val chartReadings: List<Reading> = emptyList(),
    /** Tracking error per chart reading (NaN while the sun was down). */
    val chartErrors: List<Float> = emptyList(),
    val stats: HistoryStats? = null,
    val totalLogged: Int = 0,
    val settings: AppSettings = AppSettings.DEFAULT
)

class HistoryViewModel(private val repository: TrackerRepository) : ViewModel() {

    private val range = MutableStateFlow(TimeRange.HOUR_1)

    val uiState: StateFlow<HistoryUiState> = combine(repository.history, repository.settingsFlow, range) { all, settings, r ->
        val since = r.durationMs?.let { System.currentTimeMillis() - it }
        val filtered = TrackingAnalytics.since(all, since)
        val chart = TrackingAnalytics.downsample(filtered, MAX_CHART_POINTS)
        val errors = chart.map { reading ->
            SolarCalculator.trackingError(
                reading.angleX.toDouble(), reading.angleY.toDouble(), reading.timestampMs,
                settings.location, settings.panCenterAzimuth
            )?.toFloat() ?: Float.NaN
        }
        HistoryUiState(
            range = r,
            readings = filtered,
            chartReadings = chart,
            chartErrors = errors,
            stats = TrackingAnalytics.stats(filtered, settings.location, settings.panCenterAzimuth),
            totalLogged = all.size,
            settings = settings
        )
    }
        .flowOn(Dispatchers.Default) // stats over thousands of readings stay off the main thread
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HistoryUiState())

    private val _exporting = MutableStateFlow(false)
    val exporting: StateFlow<Boolean> = _exporting.asStateFlow()

    fun setRange(newRange: TimeRange) {
        range.value = newRange
    }

    /** Writes the readings in the selected range as CSV to [uri]. Returns the row count. */
    suspend fun exportCsv(resolver: ContentResolver, uri: Uri): Result<Int> {
        _exporting.value = true
        return try {
            val state = uiState.value
            withContext(Dispatchers.IO) {
                runCatching {
                    val csv = CsvExporter.toCsv(state.readings, state.settings.location, state.settings.panCenterAzimuth)
                    val stream = resolver.openOutputStream(uri) ?: error("Couldn't open the file for writing")
                    stream.bufferedWriter().use { it.write(csv) }
                    state.readings.size
                }
            }
        } finally {
            _exporting.value = false
        }
    }

    fun clearHistory() {
        viewModelScope.launch { repository.clearHistory() }
    }

    companion object {
        private const val MAX_CHART_POINTS = 300
    }
}
