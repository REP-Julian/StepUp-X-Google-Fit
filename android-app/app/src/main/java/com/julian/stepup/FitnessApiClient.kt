package com.julian.stepup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

data class SpoofDeviceProfile(
    val label: String,
    val streamName: String,
    val deviceType: String,
    val manufacturer: String,
    val model: String,
    val uid: String
)

object FitnessApiClient {
    private const val BASE_URL = "https://www.googleapis.com/fitness/v1/users/me"

    val SPOOF_DEVICES = listOf(
        SpoofDeviceProfile("Galaxy S23 FE", "HumanMotionSteps", "phone", "samsung", "Galaxy S23 FE", "d457f782"),
        SpoofDeviceProfile("itel ISW-011", "ItelISW011Steps", "watch", "itel", "ISW-011", "isw011_92a4"),
        SpoofDeviceProfile("Pixel Watch 3", "PixelWatchSteps", "watch", "Google", "Pixel Watch 3", "pw3_8841a"),
        SpoofDeviceProfile("Galaxy Watch 6", "GalaxyWatchSteps", "watch", "samsung", "Galaxy Watch6", "gw6_4419b")
    )

    val SCOPES = listOf(
        "https://www.googleapis.com/auth/fitness.activity.write",
        "https://www.googleapis.com/auth/fitness.activity.read",
        "https://www.googleapis.com/auth/fitness.body.write",
        "https://www.googleapis.com/auth/fitness.location.write"
    )

    private fun gaussianStep(mean: Double, stdDev: Double): Int {
        // Box-Muller transform for natural human step cadence
        val u1 = Random.nextDouble().coerceAtLeast(1e-7)
        val u2 = Random.nextDouble()
        val z = kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
        return (mean + z * stdDev).toInt()
    }

    private fun request(
        method: String,
        urlString: String,
        accessToken: String,
        body: String? = null
    ): Pair<Int, String> {
        val backoffs = longArrayOf(500L, 1500L)
        var lastException: Exception? = null

        for (attempt in 0..backoffs.size) {
            var conn: HttpURLConnection? = null
            try {
                val url = URL(urlString)
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    setRequestProperty("Authorization", "Bearer $accessToken")
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    connectTimeout = 20000
                    readTimeout = 25000
                    if (body != null) {
                        doOutput = true
                    }
                }

                if (body != null) {
                    OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                        writer.write(body)
                        writer.flush()
                    }
                }

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val responseText = if (stream != null) {
                    BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
                } else {
                    ""
                }
                conn.disconnect()

                // Retry on rate limit (429) or transient 5xx server errors
                if ((code == 429 || code in 500..599) && attempt < backoffs.size) {
                    Thread.sleep(backoffs[attempt])
                    continue
                }
                return code to responseText
            } catch (e: Exception) {
                conn?.disconnect()
                lastException = e
                if (attempt < backoffs.size) {
                    try {
                        Thread.sleep(backoffs[attempt])
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }
        throw lastException ?: Exception("Network request failed")
    }

    suspend fun getOrCreateStream(
        accessToken: String,
        profile: SpoofDeviceProfile = SPOOF_DEVICES.first()
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val (code, resp) = request("GET", "$BASE_URL/dataSources", accessToken)
            if (code !in 200..299) {
                return@withContext Result.failure(Exception("List dataSources failed ($code): $resp"))
            }

            val json = JSONObject(resp)
            val sources = json.optJSONArray("dataSource") ?: JSONArray()

            // Reuse matching spoofed device stream if already registered on this account
            for (i in 0 until sources.length()) {
                val ds = sources.getJSONObject(i)
                val sid = ds.optString("dataStreamId", "")
                val dt = ds.optJSONObject("dataType")?.optString("name", "") ?: ""
                if (dt == "com.google.step_count.delta" &&
                    (sid.contains(profile.streamName) || sid.contains(profile.model))
                ) {
                    return@withContext Result.success(sid)
                }
            }

            // Create spoofed device stream
            val createBody = JSONObject().apply {
                put("dataStreamName", profile.streamName)
                put("type", "raw")
                put("application", JSONObject().put("name", "com.google.android.apps.fitness"))
                put("dataType", JSONObject().apply {
                    put("name", "com.google.step_count.delta")
                    put("field", JSONArray().put(JSONObject().apply {
                        put("name", "steps")
                        put("format", "integer")
                    }))
                })
                put("device", JSONObject().apply {
                    put("uid", profile.uid)
                    put("type", profile.deviceType)
                    put("version", "1.0")
                    put("model", profile.model)
                    put("manufacturer", profile.manufacturer)
                })
            }

            val (cCode, cResp) = request("POST", "$BASE_URL/dataSources", accessToken, createBody.toString())
            if (cCode !in 200..299) {
                return@withContext Result.failure(Exception("Create dataSource failed ($cCode): $cResp"))
            }
            val createdId = JSONObject(cResp).getString("dataStreamId")
            Result.success(createdId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private val auxCache = ConcurrentHashMap<String, Pair<String, String>>()

    private fun todayMidnightMs(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    suspend fun getTodayStepTotal(accessToken: String): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val startMs = todayMidnightMs()
            val endMs = System.currentTimeMillis()
            val body = JSONObject().apply {
                put("aggregateBy", JSONArray().put(JSONObject().put("dataTypeName", "com.google.step_count.delta")))
                put("bucketByTime", JSONObject().put("durationMillis", max(60_000L, endMs - startMs + 60_000L)))
                put("startTimeMillis", startMs)
                put("endTimeMillis", endMs)
            }
            val (code, resp) = request("POST", "$BASE_URL/dataset:aggregate", accessToken, body.toString())
            if (code !in 200..299) {
                return@withContext Result.failure(Exception("Aggregate failed ($code): $resp"))
            }
            var total = 0
            val buckets = JSONObject(resp).optJSONArray("bucket") ?: JSONArray()
            for (i in 0 until buckets.length()) {
                val datasets = buckets.getJSONObject(i).optJSONArray("dataset") ?: continue
                for (j in 0 until datasets.length()) {
                    val points = datasets.getJSONObject(j).optJSONArray("point") ?: continue
                    for (k in 0 until points.length()) {
                        val vals = points.getJSONObject(k).optJSONArray("value") ?: continue
                        for (v in 0 until vals.length()) {
                            total += vals.getJSONObject(v).optInt("intVal", 0)
                        }
                    }
                }
            }
            Result.success(total)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun ensureAuxStreamSync(
        accessToken: String,
        sources: JSONArray,
        dataTypeName: String,
        fieldName: String,
        streamName: String,
        profile: SpoofDeviceProfile
    ): String? {
        for (i in 0 until sources.length()) {
            val ds = sources.getJSONObject(i)
            val dt = ds.optJSONObject("dataType")?.optString("name", "") ?: ""
            val sid = ds.optString("dataStreamId", "")
            if (dt == dataTypeName && (sid.contains(streamName) || sid.contains(profile.model))) {
                return sid
            }
        }
        val createBody = JSONObject().apply {
            put("dataStreamName", streamName)
            put("type", "raw")
            put("application", JSONObject().put("name", "com.google.android.apps.fitness"))
            put("dataType", JSONObject().apply {
                put("name", dataTypeName)
                put("field", JSONArray().put(JSONObject().apply {
                    put("name", fieldName)
                    put("format", "floatPoint")
                }))
            })
            put("device", JSONObject().apply {
                put("uid", profile.uid)
                put("type", profile.deviceType)
                put("version", "1.0")
                put("model", profile.model)
                put("manufacturer", profile.manufacturer)
            })
        }
        val (code, resp) = request("POST", "$BASE_URL/dataSources", accessToken, createBody.toString())
        if (code !in 200..299) return null
        return JSONObject(resp).optString("dataStreamId", "").ifEmpty { null }
    }

    private fun getAuxStreamsSync(accessToken: String, stepStreamId: String, profile: SpoofDeviceProfile): Pair<String, String>? {
        auxCache[stepStreamId]?.let { return it }
        val (code, resp) = request("GET", "$BASE_URL/dataSources", accessToken)
        if (code !in 200..299) return null
        val sources = JSONObject(resp).optJSONArray("dataSource") ?: JSONArray()
        val distId = ensureAuxStreamSync(
            accessToken, sources, "com.google.distance.delta", "distance", "${profile.streamName}Dist", profile
        ) ?: return null
        val calId = ensureAuxStreamSync(
            accessToken, sources, "com.google.calories.expended", "calories", "${profile.streamName}Cal", profile
        ) ?: return null
        val pair = distId to calId
        auxCache[stepStreamId] = pair
        return pair
    }

    private fun patchFloatChunkSync(
        accessToken: String,
        streamId: String,
        dataTypeName: String,
        startNs: Long,
        endNs: Long,
        value: Double
    ) {
        val encodedStream = URLEncoder.encode(streamId, "UTF-8")
        val datasetId = "$startNs-$endNs"
        val body = JSONObject().apply {
            put("dataSourceId", streamId)
            put("minStartTimeNs", startNs)
            put("maxEndTimeNs", endNs)
            put("point", JSONArray().put(JSONObject().apply {
                put("dataTypeName", dataTypeName)
                put("startTimeNanos", startNs)
                put("endTimeNanos", endNs)
                put("value", JSONArray().put(JSONObject().put("fpVal", value)))
            }))
        }
        request("PATCH", "$BASE_URL/dataSources/$encodedStream/datasets/$datasetId", accessToken, body.toString())
    }

    suspend fun patchStepChunk(
        accessToken: String,
        streamId: String,
        startNs: Long,
        endNs: Long,
        steps: Int,
        profile: SpoofDeviceProfile = SPOOF_DEVICES.first()
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val encodedStream = URLEncoder.encode(streamId, "UTF-8")
            val datasetId = "$startNs-$endNs"
            val body = JSONObject().apply {
                put("dataSourceId", streamId)
                put("minStartTimeNs", startNs)
                put("maxEndTimeNs", endNs)
                put("point", JSONArray().put(JSONObject().apply {
                    put("dataTypeName", "com.google.step_count.delta")
                    put("startTimeNanos", startNs)
                    put("endTimeNanos", endNs)
                    put("value", JSONArray().put(JSONObject().put("intVal", steps)))
                }))
            }
            val (code, resp) = request(
                "PATCH",
                "$BASE_URL/dataSources/$encodedStream/datasets/$datasetId",
                accessToken,
                body.toString()
            )
            if (code !in 200..299) {
                return@withContext Result.failure(Exception("Patch failed ($code): $resp"))
            }
            // Pair distance (0.75m/step) & calories (0.04 kcal/step)
            getAuxStreamsSync(accessToken, streamId, profile)?.let { (distId, calId) ->
                patchFloatChunkSync(accessToken, distId, "com.google.distance.delta", startNs, endNs, steps * 0.75)
                patchFloatChunkSync(accessToken, calId, "com.google.calories.expended", startNs, endNs, steps * 0.04)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun removeStepsToday(
        accessToken: String,
        streamId: String,
        stepsToRemove: Int?,
        profile: SpoofDeviceProfile = SPOOF_DEVICES.first()
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val midnightNs = todayMidnightMs() * 1_000_000L
            val nowNs = System.currentTimeMillis() * 1_000_000L
            val encodedStream = URLEncoder.encode(streamId, "UTF-8")

            if (stepsToRemove == null) {
                val (dCode, dResp) = request(
                    "DELETE",
                    "$BASE_URL/dataSources/$encodedStream/datasets/$midnightNs-$nowNs",
                    accessToken
                )
                if (dCode !in 200..299 && dCode != 404) {
                    return@withContext Result.failure(Exception("Delete failed ($dCode): $dResp"))
                }
                getAuxStreamsSync(accessToken, streamId, profile)?.let { (distId, calId) ->
                    request("DELETE", "$BASE_URL/dataSources/${URLEncoder.encode(distId, "UTF-8")}/datasets/$midnightNs-$nowNs", accessToken)
                    request("DELETE", "$BASE_URL/dataSources/${URLEncoder.encode(calId, "UTF-8")}/datasets/$midnightNs-$nowNs", accessToken)
                }
                clearOverlappingSessions(accessToken, todayMidnightMs(), System.currentTimeMillis(), "")
                return@withContext Result.success(-1)
            }

            val (gCode, gResp) = request(
                "GET",
                "$BASE_URL/dataSources/$encodedStream/datasets/$midnightNs-$nowNs",
                accessToken
            )
            if (gCode !in 200..299) {
                return@withContext Result.failure(Exception("Read dataset failed ($gCode): $gResp"))
            }

            data class StepPoint(val startNs: Long, val endNs: Long, val steps: Int)
            val pointsArr = JSONObject(gResp).optJSONArray("point") ?: JSONArray()
            val pts = mutableListOf<StepPoint>()
            for (i in 0 until pointsArr.length()) {
                val p = pointsArr.getJSONObject(i)
                val s = p.optLong("startTimeNanos", 0L)
                val e = p.optLong("endTimeNanos", 0L)
                val v = p.optJSONArray("value")?.optJSONObject(0)?.optInt("intVal", 0) ?: 0
                if (v > 0 && e > s) pts.add(StepPoint(s, e, v))
            }

            pts.sortByDescending { it.endNs }
            var remaining: Int = stepsToRemove ?: 0
            var removed = 0
            val aux = getAuxStreamsSync(accessToken, streamId, profile)

            for (pt in pts) {
                if (remaining <= 0) break
                val dsId = "${pt.startNs}-${pt.endNs}"
                request("DELETE", "$BASE_URL/dataSources/$encodedStream/datasets/$dsId", accessToken)
                aux?.let { (distId, calId) ->
                    request("DELETE", "$BASE_URL/dataSources/${URLEncoder.encode(distId, "UTF-8")}/datasets/$dsId", accessToken)
                    request("DELETE", "$BASE_URL/dataSources/${URLEncoder.encode(calId, "UTF-8")}/datasets/$dsId", accessToken)
                }

                if (pt.steps <= remaining) {
                    removed += pt.steps
                    remaining -= pt.steps
                } else {
                    val keepSteps = pt.steps - remaining
                    removed += remaining
                    remaining = 0
                    val patchBody = JSONObject().apply {
                        put("dataSourceId", streamId)
                        put("minStartTimeNs", pt.startNs)
                        put("maxEndTimeNs", pt.endNs)
                        put("point", JSONArray().put(JSONObject().apply {
                            put("dataTypeName", "com.google.step_count.delta")
                            put("startTimeNanos", pt.startNs)
                            put("endTimeNanos", pt.endNs)
                            put("value", JSONArray().put(JSONObject().put("intVal", keepSteps)))
                        }))
                    }
                    request("PATCH", "$BASE_URL/dataSources/$encodedStream/datasets/$dsId", accessToken, patchBody.toString())
                    aux?.let { (distId, calId) ->
                        patchFloatChunkSync(accessToken, distId, "com.google.distance.delta", pt.startNs, pt.endNs, keepSteps * 0.75)
                        patchFloatChunkSync(accessToken, calId, "com.google.calories.expended", pt.startNs, pt.endNs, keepSteps * 0.04)
                    }
                }
            }
            Result.success(removed)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun injectInstant(
        accessToken: String,
        streamId: String,
        targetSteps: Int,
        clearOverlaps: Boolean = true,
        profile: SpoofDeviceProfile = SPOOF_DEVICES.first()
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val nowSec = System.currentTimeMillis() / 1000.0
            val mins = max(3, targetSteps / 102)
            var currSec = nowSec - (mins * 60)
            var remaining = targetSteps
            val points = JSONArray()

            var minNs = Long.MAX_VALUE
            var maxNs = Long.MIN_VALUE

            while (remaining > 0) {
                val chunk = max(1, min(remaining, max(10, gaussianStep(102.0, 10.0))))
                val pStartNs = (currSec * 1_000_000_000.0).toLong()
                val pEndNs = ((currSec + 60.0) * 1_000_000_000.0).toLong()
                if (pStartNs < minNs) minNs = pStartNs
                if (pEndNs > maxNs) maxNs = pEndNs

                points.put(JSONObject().apply {
                    put("dataTypeName", "com.google.step_count.delta")
                    put("startTimeNanos", pStartNs)
                    put("endTimeNanos", pEndNs)
                    put("value", JSONArray().put(JSONObject().put("intVal", chunk)))
                })

                currSec += 60.0
                remaining -= chunk
            }

            val encodedStream = URLEncoder.encode(streamId, "UTF-8")
            val datasetId = "$minNs-$maxNs"
            val body = JSONObject().apply {
                put("dataSourceId", streamId)
                put("minStartTimeNs", minNs)
                put("maxEndTimeNs", maxNs)
                put("point", points)
            }

            val (code, resp) = request(
                "PATCH",
                "$BASE_URL/dataSources/$encodedStream/datasets/$datasetId",
                accessToken,
                body.toString()
            )
            if (code !in 200..299) {
                return@withContext Result.failure(Exception("Dataset patch failed ($code): $resp"))
            }

            // Pair proportional distance & calories
            getAuxStreamsSync(accessToken, streamId, profile)?.let { (distId, calId) ->
                patchFloatChunkSync(accessToken, distId, "com.google.distance.delta", minNs, maxNs, targetSteps * 0.75)
                patchFloatChunkSync(accessToken, calId, "com.google.calories.expended", minNs, maxNs, targetSteps * 0.04)
            }

            // Create matching walking session so Google Fit & StepUp update immediately
            val sMs = minNs / 1_000_000L
            val eMs = maxNs / 1_000_000L
            upsertWalkingSession(accessToken, sMs, eMs, clearOverlaps)

            Result.success(mins)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun toIsoUtc(ms: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(ms))
    }

    private fun clearOverlappingSessions(
        accessToken: String,
        startMs: Long,
        endMs: Long,
        keepSessionId: String
    ) {
        val startIso = URLEncoder.encode(toIsoUtc(startMs), "UTF-8")
        val endIso = URLEncoder.encode(toIsoUtc(endMs), "UTF-8")
        val (code, resp) = request(
            "GET",
            "$BASE_URL/sessions?startTime=$startIso&endTime=$endIso&includeDeleted=false",
            accessToken
        )
        if (code !in 200..299) return
        val sessions = JSONObject(resp).optJSONArray("session") ?: return
        for (i in 0 until sessions.length()) {
            val s = sessions.getJSONObject(i)
            val id = s.optString("id", "")
            if (id.isNotEmpty() && id != keepSessionId) {
                val encodedId = URLEncoder.encode(id, "UTF-8")
                request("DELETE", "$BASE_URL/sessions/$encodedId", accessToken)
            }
        }
    }

    suspend fun upsertWalkingSession(
        accessToken: String,
        startMs: Long,
        endMs: Long,
        clearOverlaps: Boolean = true
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val safeEndMs = max(startMs + 1000L, endMs)
            val sid = "walk_$startMs"
            if (clearOverlaps) {
                clearOverlappingSessions(accessToken, startMs, safeEndMs, sid)
            }
            val sessionBody = JSONObject().apply {
                put("id", sid)
                put("name", "Outdoor Walk")
                put("description", "Natural human walking pace")
                put("startTimeMillis", startMs)
                put("endTimeMillis", safeEndMs)
                put("activityType", 7)
                put("application", JSONObject().put("name", "StepUpHumanAssistant"))
            }
            val (code, resp) = request("PUT", "$BASE_URL/sessions/$sid", accessToken, sessionBody.toString())
            if (code !in 200..299) {
                return@withContext Result.failure(Exception("Session update failed ($code): $resp"))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun patchStepBatch(
        accessToken: String,
        chunks: List<PendingChunk>
    ): Result<Int> = withContext(Dispatchers.IO) {
        if (chunks.isEmpty()) return@withContext Result.success(0)
        try {
            var totalSynced = 0
            for ((sid, group) in chunks.groupBy { it.streamId }) {
                val sorted = group.sortedBy { it.startNs }
                val minNs = sorted.first().startNs
                val maxNs = sorted.last().endNs
                val points = JSONArray()
                var groupSteps = 0

                sorted.forEach { c ->
                    groupSteps += c.steps
                    points.put(JSONObject().apply {
                        put("dataTypeName", "com.google.step_count.delta")
                        put("startTimeNanos", c.startNs)
                        put("endTimeNanos", c.endNs)
                        put("value", JSONArray().put(JSONObject().put("intVal", c.steps)))
                    })
                }

                val encodedStream = URLEncoder.encode(sid, "UTF-8")
                val body = JSONObject().apply {
                    put("dataSourceId", sid)
                    put("minStartTimeNs", minNs)
                    put("maxEndTimeNs", maxNs)
                    put("point", points)
                }

                val (code, resp) = request(
                    "PATCH",
                    "$BASE_URL/dataSources/$encodedStream/datasets/$minNs-$maxNs",
                    accessToken,
                    body.toString()
                )
                if (code !in 200..299) {
                    return@withContext Result.failure(Exception("Batch patch failed ($code): $resp"))
                }

                val profile = SPOOF_DEVICES.firstOrNull { it.label == sorted.first().deviceLabel } ?: SPOOF_DEVICES.first()
                getAuxStreamsSync(accessToken, sid, profile)?.let { (distId, calId) ->
                    patchFloatChunkSync(accessToken, distId, "com.google.distance.delta", minNs, maxNs, groupSteps * 0.75)
                    patchFloatChunkSync(accessToken, calId, "com.google.calories.expended", minNs, maxNs, groupSteps * 0.04)
                }
                totalSynced += groupSteps
            }
            Result.success(totalSynced)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun nextRealtimeChunk(remaining: Int, intervalSec: Int = 4, cadenceMean: Int = 104): Int {
        val raw = ((gaussianStep(cadenceMean.toDouble(), 10.0) / 60.0) * intervalSec).toInt()
        return max(1, min(remaining, max(2, raw)))
    }
}
