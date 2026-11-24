package com.heliossentinel.companion.data.model

/**
 * Operating mode reported by / sent to the Helios Sentinel ESP32-S3 firmware.
 */
enum class TrackerMode {
    AUTO,
    MANUAL;

    fun wireValue(): String = name.lowercase()

    companion object {
        fun fromWire(value: String?): TrackerMode =
            if (value?.equals("manual", ignoreCase = true) == true) MANUAL else AUTO
    }
}

/**
 * Snapshot of the tracker's current state, as returned by GET /api/status.
 */
data class TrackerStatus(
    val mode: TrackerMode,
    val angleX: Float,
    val angleY: Float,
    val ldrTopLeft: Int,
    val ldrTopRight: Int,
    val ldrBottomLeft: Int,
    val ldrBottomRight: Int,
    val online: Boolean,
    val uptimeMs: Long
) {
    companion object {
        val EMPTY = TrackerStatus(
            mode = TrackerMode.AUTO,
            angleX = 90f,
            angleY = 90f,
            ldrTopLeft = 0,
            ldrTopRight = 0,
            ldrBottomLeft = 0,
            ldrBottomRight = 0,
            online = false,
            uptimeMs = 0L
        )
    }
}

/** Command sent to POST /api/control. */
data class ControlCommand(
    val mode: TrackerMode,
    val angleX: Float? = null,
    val angleY: Float? = null
)

/** Tunable tracker configuration, GET/POST /api/config. */
data class TrackerConfig(
    val ldrThreshold: Int,
    val pollIntervalMs: Long,
    val minAngle: Float,
    val maxAngle: Float
) {
    companion object {
        val DEFAULT = TrackerConfig(
            ldrThreshold = 40,
            pollIntervalMs = 2000L,
            minAngle = 0f,
            maxAngle = 180f
        )
    }
}

/** A single logged reading, stored locally for the history/charts screen. */
data class Reading(
    val timestampMs: Long,
    val angleX: Float,
    val angleY: Float,
    val ldrTopLeft: Int,
    val ldrTopRight: Int,
    val ldrBottomLeft: Int,
    val ldrBottomRight: Int,
    val mode: TrackerMode = TrackerMode.AUTO
) {
    companion object {
        fun fromStatus(status: TrackerStatus, timestampMs: Long = System.currentTimeMillis()) =
            Reading(
                timestampMs = timestampMs,
                angleX = status.angleX,
                angleY = status.angleY,
                ldrTopLeft = status.ldrTopLeft,
                ldrTopRight = status.ldrTopRight,
                ldrBottomLeft = status.ldrBottomLeft,
                ldrBottomRight = status.ldrBottomRight,
                mode = status.mode
            )
    }
}

/** Connection state surfaced to the UI. */
sealed class ConnectionState {
    data object Connecting : ConnectionState()
    data object Connected : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}
