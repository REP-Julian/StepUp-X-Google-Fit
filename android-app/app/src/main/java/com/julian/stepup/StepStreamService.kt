package com.julian.stepup

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

data class StreamUiState(
    val isRunning: Boolean = false,
    val isRideMode: Boolean = false,
    val isMoving: Boolean = true,
    val speedKmh: Float = 0f,
    val progress: Int = 0,
    val target: Int = 0,
    val cadence: Double = 0.0
)

object StreamBus {
    private val _state = MutableStateFlow(StreamUiState())
    val state = _state.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs = _logs.asStateFlow()

    fun updateState(newState: StreamUiState) {
        _state.value = newState
    }

    fun log(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        _logs.value = (listOf("[$ts] $msg") + _logs.value).take(50)
    }
}

class StepStreamService : Service() {

    companion object {
        const val ACTION_START = "com.julian.stepup.START_STREAM"
        const val ACTION_START_RIDE = "com.julian.stepup.START_RIDE"
        const val ACTION_TOGGLE_RIDE = "com.julian.stepup.TOGGLE_RIDE"
        const val ACTION_STOP = "com.julian.stepup.STOP_STREAM"
        const val EXTRA_TOKEN = "extra_token"
        const val EXTRA_STREAM_ID = "extra_stream_id"
        const val EXTRA_TARGET = "extra_target"
        const val EXTRA_DEVICE_LABEL = "extra_device_label"
        private const val CHANNEL_ID = "stepup_stream_channel"
        private const val NOTIF_ID = 1001
        private const val MOVE_GRACE_MS = 15_000L
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var streamJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var fusedClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null

    @Volatile private var rideModeActive: Boolean = false
    @Volatile private var lastMovingMs: Long = 0L
    @Volatile private var currentSpeedKmh: Float = 0f
    @Volatile private var smoothedSpeedMs: Float = 0f
    @Volatile private var consecutiveMovingFixes: Int = 0
    @Volatile private var lastLocation: Location? = null
    @Volatile private var lastLocationTimeMs: Long = 0L
    @Volatile private var gpsLockedLogged: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppPrefs.init(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopStreaming("Stream stopped by user.")
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_RIDE -> {
                if (streamJob?.isActive == true) {
                    val nextRide = !rideModeActive
                    rideModeActive = nextRide
                    if (nextRide) {
                        startLocationTracking()
                        StreamBus.log("Switched to Ride-to-Steps (GPS Auto).")
                    } else {
                        stopLocationTracking()
                        StreamBus.log("Switched to Manual Live Walking.")
                    }
                    val st = StreamBus.state.value
                    StreamBus.updateState(st.copy(isRideMode = nextRide, isMoving = !nextRide))
                }
                return START_NOT_STICKY
            }
            ACTION_START, ACTION_START_RIDE -> {
                val rideMode = intent.action == ACTION_START_RIDE
                rideModeActive = rideMode
                val token = intent.getStringExtra(EXTRA_TOKEN) ?: return START_NOT_STICKY
                val streamId = intent.getStringExtra(EXTRA_STREAM_ID) ?: return START_NOT_STICKY
                val target = intent.getIntExtra(EXTRA_TARGET, 5000)
                val deviceLabel = intent.getStringExtra(EXTRA_DEVICE_LABEL) ?: "Galaxy S23 FE"
                startForeground(NOTIF_ID, buildNotification(0, target, 0.0, rideMode, !rideMode, 0f))
                if (rideMode) {
                    startLocationTracking()
                } else {
                    stopLocationTracking()
                }
                startStreaming(token, streamId, target, deviceLabel)
            }
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startLocationTracking() {
        stopLocationTracking()
        lastMovingMs = 0L
        currentSpeedKmh = 0f
        smoothedSpeedMs = 0f
        consecutiveMovingFixes = 0
        lastLocation = null
        lastLocationTimeMs = 0L
        gpsLockedLogged = false

        val client = LocationServices.getFusedLocationProviderClient(this)
        fusedClient = client

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2500L)
            .setMinUpdateIntervalMillis(1500L)
            .setWaitForAccurateLocation(false)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                // Reject noisy cell/wifi jumps (>30m uncertainty)
                val accuracy = if (loc.hasAccuracy()) loc.accuracy else 25f
                if (accuracy > 30f) return

                val nowMs = System.currentTimeMillis()
                if (!gpsLockedLogged) {
                    gpsLockedLogged = true
                    StreamBus.log(String.format(Locale.US, "GPS locked (±%.0fm).", accuracy))
                }

                val prev = lastLocation
                val dtSec = if (lastLocationTimeMs > 0L) ((nowMs - lastLocationTimeMs) / 1000f).coerceAtLeast(1.0f) else 2.5f
                val distM = if (prev != null) loc.distanceTo(prev) else 0f
                lastLocation = loc
                lastLocationTimeMs = nowMs

                // Prefer hardware Doppler speed when reliable; otherwise noise-gated displacement speed
                val hasReliableSpeed = loc.hasSpeed() &&
                    (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !loc.hasSpeedAccuracy() || loc.speedAccuracyMetersPerSecond < 2.5f)

                val rawSpeedMs = when {
                    hasReliableSpeed -> loc.speed
                    prev != null && distM >= maxOf(6.0f, accuracy * 0.65f) -> (distM / dtSec).coerceAtMost(35f)
                    else -> 0f
                }

                smoothedSpeedMs = if (smoothedSpeedMs == 0f) rawSpeedMs else (0.6f * rawSpeedMs + 0.4f * smoothedSpeedMs)
                currentSpeedKmh = smoothedSpeedMs * 3.6f

                val minSpeedMs = AppPrefs.config.value.rideMinSpeedKmh / 3.6f
                val fixMoving = smoothedSpeedMs >= minSpeedMs || (prev != null && distM >= maxOf(8.0f, accuracy * 0.8f))

                if (fixMoving) {
                    consecutiveMovingFixes++
                    // Debounce: require 2 consecutive moving fixes when resuming from stopped to block GPS drift
                    val alreadyActive = (nowMs - lastMovingMs) <= MOVE_GRACE_MS
                    if (alreadyActive || consecutiveMovingFixes >= 2) {
                        val wasPaused = !alreadyActive
                        lastMovingMs = nowMs
                        if (wasPaused) {
                            StreamBus.log(String.format(Locale.US, "Moving (%.1f km/h). Streaming steps.", currentSpeedKmh))
                        }
                    }
                } else {
                    consecutiveMovingFixes = 0
                }
            }
        }
        locationCallback = callback

        try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (e: Exception) {
            StreamBus.log("Fused GPS error: ${e.message}")
        }
    }

    private fun stopLocationTracking() {
        try {
            locationCallback?.let { fusedClient?.removeLocationUpdates(it) }
        } catch (_: Exception) {
        }
        locationCallback = null
        fusedClient = null
    }

    private fun startStreaming(
        initialToken: String,
        streamId: String,
        target: Int,
        deviceLabel: String
    ) {
        streamJob?.cancel()
        acquireWakeLock()

        streamJob = serviceScope.launch {
            var currentToken = initialToken
            val profile = FitnessApiClient.SPOOF_DEVICES.firstOrNull { it.label == deviceLabel }
                ?: FitnessApiClient.SPOOF_DEVICES.first()
            val baseTodaySteps = FitnessApiClient.getTodayStepTotal(currentToken).getOrDefault(0)
            var accumulated = 0
            var chunkIndex = 0
            val startMs = System.currentTimeMillis()
            var lastEndNs = startMs * 1_000_000L
            var wasPausedLog = false

            StreamBus.updateState(
                StreamUiState(
                    isRunning = true,
                    isRideMode = rideModeActive,
                    isMoving = !rideModeActive,
                    speedKmh = 0f,
                    progress = 0,
                    target = target,
                    cadence = 0.0
                )
            )
            StreamBus.log(
                if (rideModeActive) "Ride-to-Steps active ($target cap). Waiting for GPS movement..."
                else "Live walking started: $target steps (${AppPrefs.config.value.intervalSec}s interval)."
            )

            try {
                while (isActive && accumulated < target) {
                    val cfg = AppPrefs.config.value
                    if (cfg.stopAtDailyGoal && (baseTodaySteps + accumulated) >= cfg.dailyGoal) {
                        StreamBus.log("Daily goal (${cfg.dailyGoal}) reached. Auto-stopping.")
                        break
                    }
                    val t0Ns = maxOf(lastEndNs + 1_000_000L, System.currentTimeMillis() * 1_000_000L)
                    delay(cfg.intervalSec * 1000L)
                    val nowMs = System.currentTimeMillis()
                    val isRide = rideModeActive

                    val isMovingNow = !isRide || (nowMs - lastMovingMs <= MOVE_GRACE_MS)
                    if (!isMovingNow) {
                        if (!wasPausedLog) {
                            wasPausedLog = true
                            StreamBus.log("Vehicle stopped. Paused step injection.")
                        }
                        val elapsedMin = (nowMs - startMs) / 60000.0
                        val cadence = if (elapsedMin > 0) accumulated / elapsedMin else 0.0
                        StreamBus.updateState(
                            StreamUiState(true, isRide, false, currentSpeedKmh, accumulated, target, cadence)
                        )
                        updateNotification(accumulated, target, cadence, isRide, false, currentSpeedKmh)
                        continue
                    }
                    wasPausedLog = false

                    val t1Ns = maxOf(t0Ns + 1_000_000L, nowMs * 1_000_000L)
                    lastEndNs = t1Ns
                    val chunk = FitnessApiClient.nextRealtimeChunk(
                        target - accumulated,
                        cfg.intervalSec,
                        cfg.cadence
                    )
                    var result = FitnessApiClient.patchStepChunk(currentToken, streamId, t0Ns, t1Ns, chunk, profile)

                    // Auto-refresh OAuth token if expired (401) during long 15k/20k runs
                    if (result.isFailure && result.exceptionOrNull()?.message?.contains("(401)") == true) {
                        StreamBus.log("Token expired (401). Refreshing silently...")
                        val refreshed = refreshAccessTokenSilently()
                        if (refreshed != null) {
                            currentToken = refreshed
                            result = FitnessApiClient.patchStepChunk(currentToken, streamId, t0Ns, t1Ns, chunk, profile)
                        } else {
                            StreamBus.log("Silent refresh failed. Re-authorize in app.")
                            break
                        }
                    }

                    result.onSuccess {
                        accumulated += chunk
                        chunkIndex++
                        val elapsedMin = (nowMs - startMs) / 60000.0
                        val cadence = if (elapsedMin > 0) accumulated / elapsedMin else cfg.cadence.toDouble()
                        StreamBus.updateState(
                            StreamUiState(true, isRide, true, currentSpeedKmh, accumulated, target, cadence)
                        )
                        updateNotification(accumulated, target, cadence, isRide, true, currentSpeedKmh)

                        // Flush any offline queued chunks now that signal is back
                        val pending = AppPrefs.pendingChunks.value
                        if (pending.isNotEmpty()) {
                            FitnessApiClient.patchStepBatch(currentToken, pending).onSuccess { syncedSteps ->
                                AppPrefs.removePendingChunks(this@StepStreamService, pending.size)
                                StreamBus.log("Signal restored: synced $syncedSteps offline steps.")
                            }
                        }

                        if (chunkIndex % 5 == 0 || accumulated >= target) {
                            FitnessApiClient.upsertWalkingSession(
                                currentToken,
                                startMs,
                                nowMs,
                                AppPrefs.config.value.clearOverlaps
                            )
                        }
                    }.onFailure { e ->
                        // Queue chunk offline so rural dead zones never lose steps
                        AppPrefs.enqueueChunk(
                            this@StepStreamService,
                            PendingChunk(streamId, deviceLabel, t0Ns, t1Ns, chunk)
                        )
                        accumulated += chunk
                        val elapsedMin = (nowMs - startMs) / 60000.0
                        val cadence = if (elapsedMin > 0) accumulated / elapsedMin else cfg.cadence.toDouble()
                        StreamBus.updateState(
                            StreamUiState(true, isRide, true, currentSpeedKmh, accumulated, target, cadence)
                        )
                        updateNotification(accumulated, target, cadence, isRide, true, currentSpeedKmh)
                        StreamBus.log("Weak/offline signal: queued $chunk steps (${e.javaClass.simpleName}).")
                    }
                }
            } finally {
                if (accumulated > 0) {
                    FitnessApiClient.upsertWalkingSession(
                        currentToken,
                        startMs,
                        System.currentTimeMillis(),
                        AppPrefs.config.value.clearOverlaps
                    )
                    AppPrefs.addHistory(
                        this@StepStreamService,
                        if (rideModeActive) "Ride Auto" else "Live Walk",
                        accumulated,
                        deviceLabel
                    )
                }
                if (accumulated >= target) {
                    StreamBus.log("Target reached: $accumulated steps uploaded.")
                }
            }
            stopStreaming(null)
        }
    }

    private suspend fun refreshAccessTokenSilently(): String? = suspendCancellableCoroutine { cont ->
        val requestedScopes = FitnessApiClient.SCOPES.map { Scope(it) }
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(requestedScopes)
            .build()
        Identity.getAuthorizationClient(applicationContext)
            .authorize(request)
            .addOnSuccessListener { result ->
                cont.resume(if (!result.hasResolution()) result.accessToken else null)
            }
            .addOnFailureListener {
                cont.resume(null)
            }
    }

    private fun stopStreaming(reason: String?) {
        streamJob?.cancel()
        streamJob = null
        stopLocationTracking()
        releaseWakeLock()
        val prev = StreamBus.state.value
        StreamBus.updateState(prev.copy(isRunning = false))
        if (reason != null) StreamBus.log(reason)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        releaseWakeLock()
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StepUp::StreamLock").apply {
            acquire(4 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "StepUp Real-Time Stream",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun updateNotification(
        progress: Int,
        target: Int,
        cadence: Double,
        rideMode: Boolean,
        isMoving: Boolean,
        speedKmh: Float
    ) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(progress, target, cadence, rideMode, isMoving, speedKmh))
    }

    private fun buildNotification(
        progress: Int,
        target: Int,
        cadence: Double,
        rideMode: Boolean,
        isMoving: Boolean,
        speedKmh: Float
    ): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, StepStreamService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title = when {
            rideMode && isMoving -> String.format(Locale.US, "StepUp Ride-to-Steps (%.1f km/h)", speedKmh)
            rideMode && !isMoving -> "StepUp Ride-to-Steps (Paused - Stopped)"
            else -> "StepUp Live Walking"
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle(title)
            .setContentText(String.format(Locale.US, "%,d / %,d steps (%.1f/min)", progress, target, cadence))
            .setSmallIcon(R.drawable.ic_step_notif)
            .setOngoing(true)
            .setProgress(target, progress, false)
            .setContentIntent(openIntent)
            .addAction(Notification.Action.Builder(null, "Stop", stopIntent).build())
            .build()
    }

    override fun onDestroy() {
        stopLocationTracking()
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }
}
