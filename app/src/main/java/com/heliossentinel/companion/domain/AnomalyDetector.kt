package com.heliossentinel.companion.domain

import kotlin.math.abs

enum class AnomalyType { DARK, SATURATED, OUTLIER }
enum class Severity { INFO, WARNING }

data class Anomaly(
    val type: AnomalyType,
    val sensor: String?,
    val severity: Severity,
    val message: String
) {
    /** Stable identity so the same problem isn't notified over and over. */
    val key: String get() = "$type:${sensor.orEmpty()}"
}

/**
 * Heuristic sanity checks on the four LDR readings. A working tracker is
 * *supposed* to see uneven light between sensor pairs while it moves, so
 * these rules only flag patterns that point at wiring or sensor faults.
 */
object AnomalyDetector {

    const val ADC_MAX = 4095          // ESP32-S3 12-bit ADC
    private const val DARK_LEVEL = 15
    private const val SATURATION_MARGIN = 5
    private const val MIN_MEAN_FOR_OUTLIER = 200.0
    private const val OUTLIER_RATIO = 0.6
    private const val OTHERS_AGREE_RATIO = 0.25

    fun detect(tl: Int, tr: Int, bl: Int, br: Int, adcMax: Int = ADC_MAX): List<Anomaly> {
        val sensors = linkedMapOf("Top-left" to tl, "Top-right" to tr, "Bottom-left" to bl, "Bottom-right" to br)
        val anomalies = mutableListOf<Anomaly>()

        if (sensors.values.all { it < DARK_LEVEL }) {
            anomalies += Anomaly(
                AnomalyType.DARK, null, Severity.INFO,
                "All light sensors read near zero — it's dark, or the sensors are disconnected."
            )
            return anomalies
        }

        sensors.forEach { (name, value) ->
            if (value >= adcMax - SATURATION_MARGIN) {
                anomalies += Anomaly(
                    AnomalyType.SATURATED, name, Severity.WARNING,
                    "$name sensor is at the ADC maximum — direct glare, or check its resistor divider."
                )
            }
        }

        val mean = sensors.values.average()
        if (mean >= MIN_MEAN_FOR_OUTLIER) {
            sensors.forEach { (name, value) ->
                val others = sensors.filterKeys { it != name }.values
                val othersMean = others.average()
                if (othersMean <= 0.0) return@forEach
                val othersAgree = others.all { abs(it - othersMean) <= othersMean * OTHERS_AGREE_RATIO }
                val deviation = (value - othersMean) / othersMean
                if (othersAgree && abs(deviation) > OUTLIER_RATIO) {
                    val pct = (abs(deviation) * 100).toInt()
                    val direction = if (deviation < 0) "lower" else "higher"
                    anomalies += Anomaly(
                        AnomalyType.OUTLIER, name, Severity.WARNING,
                        "$name sensor reads $pct% $direction than the other three — it may be shaded, dirty or faulty."
                    )
                }
            }
        }
        return anomalies
    }
}
