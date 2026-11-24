package com.heliossentinel.companion.data.local

import android.content.Context
import com.heliossentinel.companion.data.model.Reading
import com.heliossentinel.companion.data.model.TrackerMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * On-device reading log for charts, stats and CSV export.
 *
 * Stored as JSON Lines (one reading per line) so each poll is a cheap
 * append instead of rewriting the whole file. The file is compacted back
 * to [MAX_ENTRIES] only when it grows past twice that. At the default
 * 2-second poll rate, [MAX_ENTRIES] covers about 5.5 hours of tracking.
 */
class HistoryStore(context: Context) {

    private val file = File(context.filesDir, "tracker_history.jsonl")
    private val legacyFile = File(context.filesDir, "tracker_history.json") // v1 format, no longer used
    private val mutex = Mutex()
    private var linesOnDisk = 0

    private val _readings = MutableStateFlow<List<Reading>>(emptyList())
    val readings: StateFlow<List<Reading>> = _readings.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching { if (legacyFile.exists()) legacyFile.delete() }
            if (!file.exists()) return@withLock
            val loaded = runCatching {
                file.useLines { lines -> lines.mapNotNull(::decode).toList() }
            }.getOrDefault(emptyList())
            linesOnDisk = loaded.size
            // Merge rather than overwrite, in case a poll landed before loading finished.
            _readings.value = (loaded + _readings.value)
                .distinctBy { it.timestampMs }
                .sortedBy { it.timestampMs }
                .takeLast(MAX_ENTRIES)
        }
    }

    suspend fun append(reading: Reading) = withContext(Dispatchers.IO) {
        mutex.withLock {
            _readings.value = (_readings.value + reading).takeLast(MAX_ENTRIES)
            runCatching {
                file.appendText(encode(reading) + "\n")
                linesOnDisk++
                if (linesOnDisk > MAX_ENTRIES * 2) compact()
            }
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            _readings.value = emptyList()
            linesOnDisk = 0
            runCatching { file.delete() }
        }
    }

    private fun compact() {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.bufferedWriter().use { writer ->
            _readings.value.forEach { writer.write(encode(it)); writer.newLine() }
        }
        if (tmp.renameTo(file)) linesOnDisk = _readings.value.size
    }

    private fun encode(r: Reading): String = JSONObject().apply {
        put("t", r.timestampMs)
        put("ax", r.angleX.toDouble())
        put("ay", r.angleY.toDouble())
        put("tl", r.ldrTopLeft)
        put("tr", r.ldrTopRight)
        put("bl", r.ldrBottomLeft)
        put("br", r.ldrBottomRight)
        put("m", r.mode.wireValue())
    }.toString()

    private fun decode(line: String): Reading? = runCatching {
        if (line.isBlank()) return null
        val o = JSONObject(line)
        Reading(
            timestampMs = o.getLong("t"),
            angleX = o.getDouble("ax").toFloat(),
            angleY = o.getDouble("ay").toFloat(),
            ldrTopLeft = o.getInt("tl"),
            ldrTopRight = o.getInt("tr"),
            ldrBottomLeft = o.getInt("bl"),
            ldrBottomRight = o.getInt("br"),
            mode = TrackerMode.fromWire(o.optString("m", "auto"))
        )
    }.getOrNull()

    companion object {
        const val MAX_ENTRIES = 10_000
    }
}
