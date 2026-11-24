package com.heliossentinel.companion.data.remote

import com.heliossentinel.companion.data.model.ControlCommand
import com.heliossentinel.companion.data.model.TrackerConfig
import com.heliossentinel.companion.data.model.TrackerMode
import com.heliossentinel.companion.data.model.TrackerStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Thin HTTP client for the Helios Sentinel ESP32-S3 REST API.
 *
 * Matching firmware endpoints (see /firmware/HeliosSentinelFirmware):
 *   GET  /api/status  -> current sensor/servo snapshot
 *   POST /api/control -> switch mode / set manual angles
 *   GET  /api/config  -> read tunable thresholds
 *   POST /api/config  -> update tunable thresholds
 */
class TrackerApiService(private val baseUrlProvider: () -> String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /** @param baseUrl override, used by discovery to probe a candidate address. */
    suspend fun getStatus(baseUrl: String? = null): Result<TrackerStatus> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("${baseUrl ?: baseUrlProvider()}/api/status")
                .get()
                .build()
            val body = execute(request)
            parseStatus(JSONObject(body))
        }
    }

    /**
     * Returns the tracker's updated status when the firmware echoes it back
     * (the reference firmware does), or null if it replied with something else.
     */
    suspend fun sendControl(command: ControlCommand): Result<TrackerStatus?> = withContext(Dispatchers.IO) {
        runCatching {
            val json = JSONObject().apply {
                put("mode", command.mode.wireValue())
                command.angleX?.let { put("angleX", it.toDouble()) }
                command.angleY?.let { put("angleY", it.toDouble()) }
            }
            val request = Request.Builder()
                .url("${baseUrlProvider()}/api/control")
                .post(json.toString().toRequestBody(jsonMediaType))
                .build()
            val body = execute(request)
            runCatching { JSONObject(body) }.getOrNull()
                ?.takeIf { it.has("angleX") && it.has("angleY") }
                ?.let(::parseStatus)
        }
    }

    suspend fun getConfig(): Result<TrackerConfig> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("${baseUrlProvider()}/api/config")
                .get()
                .build()
            val body = execute(request)
            val obj = JSONObject(body)
            TrackerConfig(
                ldrThreshold = obj.optInt("ldrThreshold", TrackerConfig.DEFAULT.ldrThreshold),
                pollIntervalMs = obj.optLong("pollIntervalMs", TrackerConfig.DEFAULT.pollIntervalMs),
                minAngle = obj.optDouble("minAngle", TrackerConfig.DEFAULT.minAngle.toDouble()).toFloat(),
                maxAngle = obj.optDouble("maxAngle", TrackerConfig.DEFAULT.maxAngle.toDouble()).toFloat()
            )
        }
    }

    suspend fun updateConfig(config: TrackerConfig): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val json = JSONObject().apply {
                put("ldrThreshold", config.ldrThreshold)
                put("pollIntervalMs", config.pollIntervalMs)
                put("minAngle", config.minAngle.toDouble())
                put("maxAngle", config.maxAngle.toDouble())
            }
            val request = Request.Builder()
                .url("${baseUrlProvider()}/api/config")
                .post(json.toString().toRequestBody(jsonMediaType))
                .build()
            execute(request)
            Unit
        }
    }

    private fun execute(request: Request): String {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}: ${body.take(200)}")
            }
            return body
        }
    }

    private fun parseStatus(obj: JSONObject): TrackerStatus = TrackerStatus(
        mode = TrackerMode.fromWire(obj.optString("mode", "auto")),
        angleX = obj.optDouble("angleX", 90.0).toFloat(),
        angleY = obj.optDouble("angleY", 90.0).toFloat(),
        ldrTopLeft = obj.optInt("ldrTopLeft", 0),
        ldrTopRight = obj.optInt("ldrTopRight", 0),
        ldrBottomLeft = obj.optInt("ldrBottomLeft", 0),
        ldrBottomRight = obj.optInt("ldrBottomRight", 0),
        online = true,
        uptimeMs = obj.optLong("uptimeMs", 0L)
    )
}
