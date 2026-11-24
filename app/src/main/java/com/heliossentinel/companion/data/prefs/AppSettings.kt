package com.heliossentinel.companion.data.prefs

import com.heliossentinel.companion.domain.GeoLocation

/** Everything the app needs to reach, tune and analyse the tracker. */
data class AppSettings(
    val host: String,
    val port: Int,
    val pollIntervalMs: Long,
    val ldrThreshold: Int,
    val minAngle: Float,
    val maxAngle: Float,
    val useHttps: Boolean,
    val latitude: Double,
    val longitude: Double,
    /** Compass direction (degrees from north) the panel faces when pan = 90°. */
    val panCenterAzimuth: Double,
    val alertsEnabled: Boolean,
    val backgroundMonitoring: Boolean
) {
    val baseUrl: String get() = "${if (useHttps) "https" else "http"}://$host:$port"
    val location: GeoLocation get() = GeoLocation(latitude, longitude)

    companion object {
        val DEFAULT = AppSettings(
            host = "192.168.4.1", // default ESP32 SoftAP gateway address
            port = 80,
            pollIntervalMs = 2000L,
            ldrThreshold = 40,
            minAngle = 0f,
            maxAngle = 180f,
            useHttps = false,
            latitude = GeoLocation.ISLAMABAD.latitude,
            longitude = GeoLocation.ISLAMABAD.longitude,
            panCenterAzimuth = 180.0, // due south — right for the northern hemisphere
            alertsEnabled = true,
            backgroundMonitoring = false
        )
    }
}
