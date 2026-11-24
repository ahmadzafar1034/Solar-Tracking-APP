package com.heliossentinel.companion.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "helios_settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val HOST = stringPreferencesKey("host")
        val PORT = intPreferencesKey("port")
        val POLL_INTERVAL_MS = longPreferencesKey("poll_interval_ms")
        val LDR_THRESHOLD = intPreferencesKey("ldr_threshold")
        val MIN_ANGLE = floatPreferencesKey("min_angle")
        val MAX_ANGLE = floatPreferencesKey("max_angle")
        val USE_HTTPS = booleanPreferencesKey("use_https")
        val LATITUDE = doublePreferencesKey("latitude")
        val LONGITUDE = doublePreferencesKey("longitude")
        val PAN_CENTER_AZIMUTH = doublePreferencesKey("pan_center_azimuth")
        val ALERTS_ENABLED = booleanPreferencesKey("alerts_enabled")
        val BACKGROUND_MONITORING = booleanPreferencesKey("background_monitoring")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        val d = AppSettings.DEFAULT
        AppSettings(
            host = prefs[Keys.HOST] ?: d.host,
            port = prefs[Keys.PORT] ?: d.port,
            pollIntervalMs = prefs[Keys.POLL_INTERVAL_MS] ?: d.pollIntervalMs,
            ldrThreshold = prefs[Keys.LDR_THRESHOLD] ?: d.ldrThreshold,
            minAngle = prefs[Keys.MIN_ANGLE] ?: d.minAngle,
            maxAngle = prefs[Keys.MAX_ANGLE] ?: d.maxAngle,
            useHttps = prefs[Keys.USE_HTTPS] ?: d.useHttps,
            latitude = prefs[Keys.LATITUDE] ?: d.latitude,
            longitude = prefs[Keys.LONGITUDE] ?: d.longitude,
            panCenterAzimuth = prefs[Keys.PAN_CENTER_AZIMUTH] ?: d.panCenterAzimuth,
            alertsEnabled = prefs[Keys.ALERTS_ENABLED] ?: d.alertsEnabled,
            backgroundMonitoring = prefs[Keys.BACKGROUND_MONITORING] ?: d.backgroundMonitoring
        )
    }.distinctUntilChanged()

    suspend fun update(settings: AppSettings) {
        context.dataStore.edit { prefs ->
            prefs[Keys.HOST] = settings.host
            prefs[Keys.PORT] = settings.port
            prefs[Keys.POLL_INTERVAL_MS] = settings.pollIntervalMs
            prefs[Keys.LDR_THRESHOLD] = settings.ldrThreshold
            prefs[Keys.MIN_ANGLE] = settings.minAngle
            prefs[Keys.MAX_ANGLE] = settings.maxAngle
            prefs[Keys.USE_HTTPS] = settings.useHttps
            prefs[Keys.LATITUDE] = settings.latitude
            prefs[Keys.LONGITUDE] = settings.longitude
            prefs[Keys.PAN_CENTER_AZIMUTH] = settings.panCenterAzimuth
            prefs[Keys.ALERTS_ENABLED] = settings.alertsEnabled
            prefs[Keys.BACKGROUND_MONITORING] = settings.backgroundMonitoring
        }
    }
}
