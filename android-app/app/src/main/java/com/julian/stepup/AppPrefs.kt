package com.julian.stepup

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class StreamConfig(
    val cadence: Int = 104,
    val intervalSec: Int = 4,
    val rideMinSpeedKmh: Float = 4.3f,
    val clearOverlaps: Boolean = true,
    val dailyGoal: Int = 10000,
    val stopAtDailyGoal: Boolean = false
)

data class HistoryEntry(
    val timestamp: String,
    val mode: String,
    val steps: Int,
    val device: String
)

data class PendingChunk(
    val streamId: String,
    val deviceLabel: String,
    val startNs: Long,
    val endNs: Long,
    val steps: Int
)

object AppPrefs {
    private const val PREFS_NAME = "stepup_prefs"
    private const val KEY_CADENCE = "cadence"
    private const val KEY_INTERVAL = "interval_sec"
    private const val KEY_RIDE_SPEED = "ride_min_speed_kmh"
    private const val KEY_CLEAR_OVERLAPS = "clear_overlaps"
    private const val KEY_DAILY_GOAL = "daily_goal"
    private const val KEY_STOP_AT_GOAL = "stop_at_goal"
    private const val KEY_HISTORY = "injection_history"
    private const val KEY_PENDING_CHUNKS = "pending_chunks"

    private val _config = MutableStateFlow(StreamConfig())
    val config = _config.asStateFlow()

    private val _history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val history = _history.asStateFlow()

    private val _pendingChunks = MutableStateFlow<List<PendingChunk>>(emptyList())
    val pendingChunks = _pendingChunks.asStateFlow()

    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            _config.value = StreamConfig(
                cadence = prefs.getInt(KEY_CADENCE, 104).coerceIn(60, 150),
                intervalSec = prefs.getInt(KEY_INTERVAL, 4).coerceIn(2, 15),
                rideMinSpeedKmh = prefs.getFloat(KEY_RIDE_SPEED, 4.3f).coerceIn(1.5f, 20f),
                clearOverlaps = prefs.getBoolean(KEY_CLEAR_OVERLAPS, true),
                dailyGoal = prefs.getInt(KEY_DAILY_GOAL, 10000).coerceIn(1000, 50000),
                stopAtDailyGoal = prefs.getBoolean(KEY_STOP_AT_GOAL, false)
            )
            _history.value = parseHistory(prefs.getString(KEY_HISTORY, "[]") ?: "[]")
            _pendingChunks.value = parsePending(prefs.getString(KEY_PENDING_CHUNKS, "[]") ?: "[]")
            initialized = true
        }
    }

    fun updateConfig(context: Context, transform: (StreamConfig) -> StreamConfig) {
        init(context)
        val updated = transform(_config.value)
        _config.value = updated
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_CADENCE, updated.cadence)
            .putInt(KEY_INTERVAL, updated.intervalSec)
            .putFloat(KEY_RIDE_SPEED, updated.rideMinSpeedKmh)
            .putBoolean(KEY_CLEAR_OVERLAPS, updated.clearOverlaps)
            .putInt(KEY_DAILY_GOAL, updated.dailyGoal)
            .putBoolean(KEY_STOP_AT_GOAL, updated.stopAtDailyGoal)
            .apply()
    }

    fun addHistory(context: Context, mode: String, steps: Int, device: String) {
        if (steps <= 0) return
        init(context)
        val ts = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date())
        val next = (listOf(HistoryEntry(ts, mode, steps, device)) + _history.value).take(100)
        _history.value = next
        saveHistory(context, next)
    }

    fun clearHistory(context: Context) {
        init(context)
        _history.value = emptyList()
        saveHistory(context, emptyList())
    }

    fun enqueueChunk(context: Context, chunk: PendingChunk) {
        init(context)
        val next = (_pendingChunks.value + chunk).takeLast(3000)
        _pendingChunks.value = next
        savePending(context, next)
    }

    fun removePendingChunks(context: Context, count: Int) {
        init(context)
        val next = if (count >= _pendingChunks.value.size) emptyList() else _pendingChunks.value.drop(count)
        _pendingChunks.value = next
        savePending(context, next)
    }

    private fun savePending(context: Context, list: List<PendingChunk>) {
        val arr = JSONArray()
        list.forEach { c ->
            arr.put(JSONObject().apply {
                put("streamId", c.streamId)
                put("deviceLabel", c.deviceLabel)
                put("startNs", c.startNs)
                put("endNs", c.endNs)
                put("steps", c.steps)
            })
        }
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PENDING_CHUNKS, arr.toString())
            .apply()
    }

    private fun parsePending(raw: String): List<PendingChunk> {
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        PendingChunk(
                            streamId = o.optString("streamId", ""),
                            deviceLabel = o.optString("deviceLabel", "Galaxy S23 FE"),
                            startNs = o.optLong("startNs", 0L),
                            endNs = o.optLong("endNs", 0L),
                            steps = o.optInt("steps", 0)
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveHistory(context: Context, list: List<HistoryEntry>) {
        val arr = JSONArray()
        list.forEach { item ->
            arr.put(JSONObject().apply {
                put("timestamp", item.timestamp)
                put("mode", item.mode)
                put("steps", item.steps)
                put("device", item.device)
            })
        }
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HISTORY, arr.toString())
            .apply()
    }

    private fun parseHistory(raw: String): List<HistoryEntry> {
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        HistoryEntry(
                            timestamp = o.optString("timestamp", ""),
                            mode = o.optString("mode", ""),
                            steps = o.optInt("steps", 0),
                            device = o.optString("device", "")
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
