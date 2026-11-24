package com.heliossentinel.companion.domain

import com.heliossentinel.companion.data.model.Reading
import com.heliossentinel.companion.data.model.TrackerMode
import kotlin.math.abs

data class SensorStats(val min: Int, val max: Int, val average: Double)

/** Light imbalance in -1..1. Positive horizontal = brighter on the right; positive vertical = brighter on top. */
data class LightBalance(val horizontal: Double, val vertical: Double, val total: Int)

data class HistoryStats(
    val count: Int,
    val firstMs: Long,
    val lastMs: Long,
    val topLeft: SensorStats,
    val topRight: SensorStats,
    val bottomLeft: SensorStats,
    val bottomRight: SensorStats,
    val averageTotalLight: Double,
    val totalMovementDeg: Double,
    val manualFraction: Double,
    val averageTrackingErrorDeg: Double?,
    val maxTrackingErrorDeg: Double?
) {
    val spanMs: Long get() = lastMs - firstMs
}

object TrackingAnalytics {

    fun lightBalance(tl: Int, tr: Int, bl: Int, br: Int): LightBalance {
        val total = tl + tr + bl + br
        if (total <= 0) return LightBalance(0.0, 0.0, 0)
        val horizontal = ((tr + br) - (tl + bl)).toDouble() / total
        val vertical = ((tl + tr) - (bl + br)).toDouble() / total
        return LightBalance(horizontal, vertical, total)
    }

    fun since(readings: List<Reading>, sinceMs: Long?): List<Reading> =
        if (sinceMs == null) readings else readings.filter { it.timestampMs >= sinceMs }

    /**
     * Keeps at most [maxPoints] evenly spaced items, always including the first
     * and last, so charts stay fast however much history is logged.
     */
    fun <T> downsample(items: List<T>, maxPoints: Int): List<T> {
        require(maxPoints >= 2) { "maxPoints must be at least 2" }
        if (items.size <= maxPoints) return items
        val step = (items.size - 1).toDouble() / (maxPoints - 1)
        return List(maxPoints) { i -> items[(i * step).toInt().coerceAtMost(items.size - 1)] }
    }

    /**
     * Summary statistics for the history screen and the DS2001 report.
     * Tracking error is only computed when a [location] is known, and only
     * over readings taken while the sun was up.
     */
    fun stats(
        readings: List<Reading>,
        location: GeoLocation?,
        panCenterAzimuth: Double
    ): HistoryStats? {
        if (readings.isEmpty()) return null

        fun sensor(select: (Reading) -> Int): SensorStats {
            val values = readings.map(select)
            return SensorStats(values.min(), values.max(), values.average())
        }

        var movement = 0.0
        for (i in 1 until readings.size) {
            movement += abs(readings[i].angleX - readings[i - 1].angleX) +
                abs(readings[i].angleY - readings[i - 1].angleY)
        }

        val errors = if (location == null) emptyList() else readings.mapNotNull {
            SolarCalculator.trackingError(
                it.angleX.toDouble(), it.angleY.toDouble(), it.timestampMs, location, panCenterAzimuth
            )
        }

        return HistoryStats(
            count = readings.size,
            firstMs = readings.first().timestampMs,
            lastMs = readings.last().timestampMs,
            topLeft = sensor { it.ldrTopLeft },
            topRight = sensor { it.ldrTopRight },
            bottomLeft = sensor { it.ldrBottomLeft },
            bottomRight = sensor { it.ldrBottomRight },
            averageTotalLight = readings.map { it.ldrTopLeft + it.ldrTopRight + it.ldrBottomLeft + it.ldrBottomRight }.average(),
            totalMovementDeg = movement,
            manualFraction = readings.count { it.mode == TrackerMode.MANUAL }.toDouble() / readings.size,
            averageTrackingErrorDeg = errors.takeIf { it.isNotEmpty() }?.average(),
            maxTrackingErrorDeg = errors.maxOrNull()
        )
    }
}
