package com.heliossentinel.companion.domain

import com.heliossentinel.companion.data.model.Reading
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Turns logged readings into a tidy CSV for pandas / Excel analysis in the
 * DS2001 report. One row per reading, with derived columns (light balance,
 * sun position, tracking error) already computed. Numbers always use '.' as
 * the decimal separator regardless of the phone's language.
 */
object CsvExporter {

    val HEADER = listOf(
        "timestamp", "epoch_ms", "mode", "pan_deg", "tilt_deg",
        "ldr_top_left", "ldr_top_right", "ldr_bottom_left", "ldr_bottom_right", "ldr_total",
        "balance_horizontal", "balance_vertical",
        "sun_azimuth_deg", "sun_elevation_deg", "expected_pan_deg", "expected_tilt_deg", "tracking_error_deg"
    )

    fun toCsv(
        readings: List<Reading>,
        location: GeoLocation?,
        panCenterAzimuth: Double,
        zone: ZoneId = ZoneId.systemDefault()
    ): String {
        val formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(zone)
        val sb = StringBuilder()
        sb.append(HEADER.joinToString(",")).append('\n')

        for (r in readings) {
            val balance = TrackingAnalytics.lightBalance(r.ldrTopLeft, r.ldrTopRight, r.ldrBottomLeft, r.ldrBottomRight)
            val sun = location?.let { SolarCalculator.sunPosition(r.timestampMs, it) }
            val expected = sun?.let { SolarCalculator.servoAnglesFor(it, panCenterAzimuth) }
            val error = if (location != null) {
                SolarCalculator.trackingError(r.angleX.toDouble(), r.angleY.toDouble(), r.timestampMs, location, panCenterAzimuth)
            } else null

            val row = listOf(
                formatter.format(Instant.ofEpochMilli(r.timestampMs)),
                r.timestampMs.toString(),
                r.mode.wireValue(),
                fmt(r.angleX.toDouble()),
                fmt(r.angleY.toDouble()),
                r.ldrTopLeft.toString(),
                r.ldrTopRight.toString(),
                r.ldrBottomLeft.toString(),
                r.ldrBottomRight.toString(),
                balance.total.toString(),
                fmt(balance.horizontal, 4),
                fmt(balance.vertical, 4),
                sun?.let { fmt(it.azimuthDeg) }.orEmpty(),
                sun?.let { fmt(it.elevationDeg) }.orEmpty(),
                expected?.let { fmt(it.pan) }.orEmpty(),
                expected?.let { fmt(it.tilt) }.orEmpty(),
                error?.let { fmt(it) }.orEmpty()
            )
            sb.append(row.joinToString(",")).append('\n')
        }
        return sb.toString()
    }

    private fun fmt(value: Double, decimals: Int = 2): String =
        String.format(Locale.US, "%.${decimals}f", value)
}
