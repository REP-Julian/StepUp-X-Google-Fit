package com.julian.stepup

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

private val BgCanvas = Color(0xFF07080C)
private val BgSurface1 = Color(0xFF0E1017)
private val BgSurface2 = Color(0xFF151822)
private val BorderSubtle = Color(0x14FFFFFF)
private val TextPrimary = Color(0xFFF8FAFC)
private val TextSecondary = Color(0xFF94A3B8)
private val AccentSky = Color(0xFF38BDF8)
private val StatusSuccess = Color(0xFF10B981)
private val StatusDanger = Color(0xFFF43F5E)

private val STEP_PRESETS = listOf(
    "1k" to 1000,
    "2k" to 2000,
    "5k" to 5000,
    "10k" to 10000,
    "15k" to 15000,
    "20k" to 20000
)

private val REMOVE_PRESETS = listOf(
    "100" to 100,
    "500" to 500,
    "1k" to 1000,
    "2k" to 2000,
    "5k" to 5000
)

private enum class AppTab(val title: String) {
    CONTROL("Control"),
    HISTORY("History"),
    SETTINGS("Settings")
}

class MainActivity : ComponentActivity() {

    private var accessTokenState by mutableStateOf<String?>(null)
    private var streamIdState by mutableStateOf<String?>(null)
    private var pendingRideStart: (() -> Unit)? = null

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pendingRideStart?.invoke()
        } else {
            StreamBus.log("Location permission denied. Needed for Ride-to-Steps.")
        }
        pendingRideStart = null
    }

    private val authLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            try {
                val authResult = Identity.getAuthorizationClient(this)
                    .getAuthorizationResultFromIntent(result.data)
                val token = authResult.accessToken
                if (token != null) {
                    accessTokenState = token
                    StreamBus.log("Authorized with Google Fitness.")
                } else {
                    StreamBus.log("Auth returned null token.")
                }
            } catch (e: Exception) {
                StreamBus.log("Auth failed: ${e.message}")
            }
        } else {
            StreamBus.log("Authorization cancelled.")
        }
    }

    private fun requestFitnessAuthorization(silentOnly: Boolean = false) {
        val requestedScopes = FitnessApiClient.SCOPES.map { Scope(it) }
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(requestedScopes)
            .build()

        Identity.getAuthorizationClient(this)
            .authorize(request)
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    if (!silentOnly) {
                        val pendingIntent = result.pendingIntent
                        if (pendingIntent != null) {
                            val intentSenderRequest = IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                            authLauncher.launch(intentSenderRequest)
                        }
                    }
                } else {
                    val token = result.accessToken
                    if (token != null) {
                        accessTokenState = token
                        StreamBus.log("Authorized (cached consent).")
                    } else if (!silentOnly) {
                        StreamBus.log("No access token returned.")
                    }
                }
            }
            .addOnFailureListener { e ->
                if (!silentOnly) StreamBus.log("Auth error: ${e.message}")
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppPrefs.init(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        requestFitnessAuthorization(silentOnly = true)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = BgCanvas,
                    surface = BgSurface1,
                    primary = AccentSky,
                    onBackground = TextPrimary,
                    onSurface = TextPrimary
                )
            ) {
                StepUpScreen(
                    accessToken = accessTokenState,
                    streamId = streamIdState,
                    onAuthorize = { requestFitnessAuthorization() },
                    onStreamResolved = { streamIdState = it },
                    onEnsureLocationAndRun = { action ->
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                            action()
                        } else {
                            pendingRideStart = action
                            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        }
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StepUpScreen(
    accessToken: String?,
    streamId: String?,
    onAuthorize: () -> Unit,
    onStreamResolved: (String) -> Unit,
    onEnsureLocationAndRun: (() -> Unit) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val streamState by StreamBus.state.collectAsState()
    val logs by StreamBus.logs.collectAsState()
    val config by AppPrefs.config.collectAsState()
    val history by AppPrefs.history.collectAsState()
    val pendingChunks by AppPrefs.pendingChunks.collectAsState()

    var currentTab by remember { mutableStateOf(AppTab.CONTROL) }
    var customStepsText by remember { mutableStateOf("5000") }
    var removeStepsText by remember { mutableStateOf("500") }
    var selectedDevice by remember { mutableStateOf(FitnessApiClient.SPOOF_DEVICES.first()) }
    var isBusy by remember { mutableStateOf(false) }
    var todaySteps by remember { mutableStateOf<Int?>(null) }
    var isRefreshingSteps by remember { mutableStateOf(false) }

    fun refreshTodaySteps() {
        val token = accessToken ?: return
        scope.launch {
            isRefreshingSteps = true
            FitnessApiClient.getTodayStepTotal(token)
                .onSuccess { todaySteps = it }
                .onFailure { StreamBus.log("Today steps error: ${it.message}") }
            isRefreshingSteps = false
        }
    }

    LaunchedEffect(accessToken, selectedDevice) {
        if (accessToken != null) {
            val queued = AppPrefs.pendingChunks.value
            if (queued.isNotEmpty()) {
                FitnessApiClient.patchStepBatch(accessToken, queued)
                    .onSuccess { synced ->
                        AppPrefs.removePendingChunks(context, queued.size)
                        StreamBus.log("Auto-synced $synced offline steps on launch.")
                    }
            }
            refreshTodaySteps()
            StreamBus.log("Resolving spoofed stream (${selectedDevice.label})...")
            FitnessApiClient.getOrCreateStream(accessToken, selectedDevice)
                .onSuccess {
                    onStreamResolved(it)
                    StreamBus.log("Stream ready: ${it.takeLast(36)}")
                }
                .onFailure { StreamBus.log("Stream error: ${it.message}") }
        }
    }

    LaunchedEffect(streamState.isRunning) {
        if (!streamState.isRunning && accessToken != null) {
            refreshTodaySteps()
        }
    }

    suspend fun ensureStream(): String? {
        if (accessToken == null) return null
        if (streamId != null) return streamId
        val res = FitnessApiClient.getOrCreateStream(accessToken, selectedDevice)
        res.onSuccess { onStreamResolved(it) }
        res.onFailure { StreamBus.log("Stream error: ${it.message}") }
        return res.getOrNull()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgCanvas)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Header with Live Active Status
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "STEP UP",
                    color = TextPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp
                )
                Text(
                    "Google Health Cloud Injector",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            }

            val (badgeText, badgeColor) = when {
                streamState.isRunning && streamState.isRideMode && streamState.isMoving -> "RIDE LIVE" to StatusSuccess
                streamState.isRunning && streamState.isRideMode && !streamState.isMoving -> "RIDE PAUSED" to AccentSky
                streamState.isRunning -> "WALKING LIVE" to StatusSuccess
                accessToken != null -> "AUTHORIZED" to StatusSuccess
                else -> "LOCKED" to StatusDanger
            }
            Surface(
                color = badgeColor.copy(alpha = 0.16f),
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier.border(1.dp, badgeColor.copy(alpha = 0.4f), RoundedCornerShape(999.dp))
            ) {
                Text(
                    text = badgeText,
                    color = badgeColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        // Top Navigation Tabs
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(BgSurface1)
                .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            AppTab.entries.forEach { tab ->
                val active = currentTab == tab
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (active) BgSurface2 else Color.Transparent)
                        .clickable { currentTab = tab }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = tab.title,
                        color = if (active) AccentSky else TextSecondary,
                        fontSize = 13.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }

        // Tab Body
        when (currentTab) {
            AppTab.CONTROL -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Today's Live Cloud Steps & Daily Goal Card
                    Card(
                        colors = CardDefaults.cardColors(containerColor = BgSurface1),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, BorderSubtle, RoundedCornerShape(14.dp))
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("TODAY'S GOOGLE FIT STEPS", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                                    val displaySteps = (todaySteps ?: 0) + if (streamState.isRunning) streamState.progress else 0
                                    val pct = ((displaySteps.toFloat() / config.dailyGoal.coerceAtLeast(1)) * 100f).toInt().coerceAtMost(999)
                                    Text(
                                        text = if (todaySteps != null) String.format(Locale.US, "%,d / %,d (%d%%)", displaySteps, config.dailyGoal, pct) else "-- / %,d".format(config.dailyGoal),
                                        color = if (displaySteps >= config.dailyGoal) StatusSuccess else TextPrimary,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                OutlinedButton(
                                    enabled = accessToken != null && !isRefreshingSteps,
                                    onClick = { refreshTodaySteps() },
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(if (isRefreshingSteps) "..." else "Refresh", fontSize = 12.sp, color = AccentSky)
                                }
                            }
                            val progressFraction = (((todaySteps ?: 0) + if (streamState.isRunning) streamState.progress else 0).toFloat() / config.dailyGoal.coerceAtLeast(1)).coerceIn(0f, 1f)
                            LinearProgressIndicator(
                                progress = { progressFraction },
                                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                                color = if (progressFraction >= 1f) StatusSuccess else AccentSky,
                                trackColor = BgSurface2
                            )
                            if (pendingChunks.isNotEmpty()) {
                                val queuedSteps = pendingChunks.sumOf { it.steps }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "Offline queue: $queuedSteps steps waiting",
                                        color = AccentSky,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    TextButton(
                                        enabled = accessToken != null && !isBusy,
                                        onClick = {
                                            val token = accessToken ?: return@TextButton
                                            scope.launch {
                                                isBusy = true
                                                val snapshot = pendingChunks
                                                FitnessApiClient.patchStepBatch(token, snapshot)
                                                    .onSuccess { synced ->
                                                        AppPrefs.removePendingChunks(context, snapshot.size)
                                                        StreamBus.log("Synced $synced offline steps.")
                                                        refreshTodaySteps()
                                                    }
                                                    .onFailure { StreamBus.log("Sync retry failed: ${it.message}") }
                                                isBusy = false
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Text("Sync Now", fontSize = 12.sp, color = StatusSuccess)
                                    }
                                }
                            }
                        }
                    }

                    // Auth Card
                    Card(
                        colors = CardDefaults.cardColors(containerColor = BgSurface1),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, BorderSubtle, RoundedCornerShape(14.dp))
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("ACCOUNT & STREAM", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                            Text(
                                text = streamId ?: "Connect Google Account to sync steps.",
                                color = TextPrimary,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 18.sp
                            )
                            Button(
                                onClick = onAuthorize,
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AccentSky, contentColor = BgCanvas),
                                modifier = Modifier.fillMaxWidth().height(46.dp)
                            ) {
                                Text(
                                    if (accessToken == null) "Authorize Google Fitness" else "Refresh Authorization",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }

                    // Controls Card
                    Card(
                        colors = CardDefaults.cardColors(containerColor = BgSurface1),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, BorderSubtle, RoundedCornerShape(14.dp))
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("SPOOF DEVICE", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    FitnessApiClient.SPOOF_DEVICES.forEach { device ->
                                        SpaciousPill(
                                            label = device.label,
                                            selected = selectedDevice == device,
                                            enabled = !streamState.isRunning && !isBusy,
                                            activeColor = StatusSuccess,
                                            onClick = { selectedDevice = device }
                                        )
                                    }
                                }
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("QUICK PRESETS", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    val selectedSteps = customStepsText.toIntOrNull()
                                    STEP_PRESETS.forEach { (label, value) ->
                                        SpaciousPill(
                                            label = label,
                                            selected = selectedSteps == value,
                                            enabled = !streamState.isRunning,
                                            activeColor = AccentSky,
                                            mono = true,
                                            onClick = { customStepsText = value.toString() }
                                        )
                                    }
                                }
                            }

                            OutlinedTextField(
                                value = customStepsText,
                                onValueChange = { input -> customStepsText = input.filter { it.isDigit() } },
                                label = { Text("Step Count") },
                                singleLine = true,
                                shape = RoundedCornerShape(10.dp),
                                enabled = !streamState.isRunning,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth()
                            )

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    enabled = accessToken != null && !isBusy && !streamState.isRunning,
                                    onClick = {
                                        scope.launch {
                                            isBusy = true
                                            val sid = ensureStream()
                                            if (sid != null && accessToken != null) {
                                                StreamBus.log("Injecting 500 steps...")
                                                FitnessApiClient.injectInstant(accessToken, sid, 500, config.clearOverlaps, selectedDevice)
                                                    .onSuccess { mins ->
                                                        StreamBus.log("Uploaded 500 steps ($mins min walk).")
                                                        AppPrefs.addHistory(context, "Instant Test", 500, selectedDevice.label)
                                                        refreshTodaySteps()
                                                    }
                                                    .onFailure { StreamBus.log("Error: ${it.message}") }
                                            }
                                            isBusy = false
                                        }
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = StatusSuccess, contentColor = BgCanvas),
                                    modifier = Modifier.weight(1f).height(46.dp)
                                ) {
                                    Text("+500 Test", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                }

                                Button(
                                    enabled = accessToken != null && !isBusy && !streamState.isRunning,
                                    onClick = {
                                        val steps = customStepsText.toIntOrNull() ?: 0
                                        if (steps <= 0) {
                                            StreamBus.log("Enter step count > 0")
                                            return@Button
                                        }
                                        scope.launch {
                                            isBusy = true
                                            val sid = ensureStream()
                                            if (sid != null && accessToken != null) {
                                                StreamBus.log("Injecting $steps steps...")
                                                FitnessApiClient.injectInstant(accessToken, sid, steps, config.clearOverlaps, selectedDevice)
                                                    .onSuccess { mins ->
                                                        StreamBus.log("Uploaded $steps steps ($mins min walk).")
                                                        AppPrefs.addHistory(context, "Instant", steps, selectedDevice.label)
                                                        refreshTodaySteps()
                                                    }
                                                    .onFailure { StreamBus.log("Error: ${it.message}") }
                                            }
                                            isBusy = false
                                        }
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = AccentSky, contentColor = BgCanvas),
                                    modifier = Modifier.weight(1f).height(46.dp)
                                ) {
                                    Text("Inject Instant", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                }
                            }

                            if (!streamState.isRunning) {
                                OutlinedButton(
                                    enabled = accessToken != null && !isBusy,
                                    shape = RoundedCornerShape(10.dp),
                                    onClick = {
                                        val target = customStepsText.toIntOrNull() ?: 5000
                                        if (target <= 0) {
                                            StreamBus.log("Enter step count > 0")
                                            return@OutlinedButton
                                        }
                                        scope.launch {
                                            isBusy = true
                                            val sid = ensureStream()
                                            isBusy = false
                                            if (sid != null && accessToken != null) {
                                                val intent = Intent(context, StepStreamService::class.java).apply {
                                                    action = StepStreamService.ACTION_START
                                                    putExtra(StepStreamService.EXTRA_TOKEN, accessToken)
                                                    putExtra(StepStreamService.EXTRA_STREAM_ID, sid)
                                                    putExtra(StepStreamService.EXTRA_TARGET, target)
                                                    putExtra(StepStreamService.EXTRA_DEVICE_LABEL, selectedDevice.label)
                                                }
                                                ContextCompat.startForegroundService(context, intent)
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().height(46.dp)
                                ) {
                                    Text("Start Real-Time Walking", fontSize = 14.sp)
                                }

                                OutlinedButton(
                                    enabled = accessToken != null && !isBusy,
                                    shape = RoundedCornerShape(10.dp),
                                    onClick = {
                                        val target = customStepsText.toIntOrNull() ?: 5000
                                        if (target <= 0) {
                                            StreamBus.log("Enter step count > 0")
                                            return@OutlinedButton
                                        }
                                        onEnsureLocationAndRun {
                                            scope.launch {
                                                isBusy = true
                                                val sid = ensureStream()
                                                isBusy = false
                                                if (sid != null && accessToken != null) {
                                                    val intent = Intent(context, StepStreamService::class.java).apply {
                                                        action = StepStreamService.ACTION_START_RIDE
                                                        putExtra(StepStreamService.EXTRA_TOKEN, accessToken)
                                                        putExtra(StepStreamService.EXTRA_STREAM_ID, sid)
                                                        putExtra(StepStreamService.EXTRA_TARGET, target)
                                                        putExtra(StepStreamService.EXTRA_DEVICE_LABEL, selectedDevice.label)
                                                    }
                                                    ContextCompat.startForegroundService(context, intent)
                                                }
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().height(46.dp)
                                ) {
                                    Text("Start Ride-to-Steps (GPS Auto)", fontSize = 14.sp, color = StatusSuccess)
                                }
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (streamState.isRideMode) {
                                        Text(
                                            text = if (streamState.isMoving) {
                                                String.format(Locale.US, "RIDE AUTO: MOVING (%.1f km/h)", streamState.speedKmh)
                                            } else {
                                                "RIDE AUTO: PAUSED (VEHICLE STOPPED)"
                                            },
                                            color = if (streamState.isMoving) StatusSuccess else AccentSky,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                    LinearProgressIndicator(
                                        progress = {
                                            if (streamState.target > 0) streamState.progress.toFloat() / streamState.target else 0f
                                        },
                                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                                        color = StatusSuccess,
                                        trackColor = BgSurface2
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            "${streamState.progress} / ${streamState.target} steps",
                                            color = TextPrimary,
                                            fontSize = 13.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Text(
                                            String.format(Locale.US, "%.1f steps/min", streamState.cadence),
                                            color = AccentSky,
                                            fontSize = 13.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            val toggle = {
                                                val intent = Intent(context, StepStreamService::class.java).apply {
                                                    action = StepStreamService.ACTION_TOGGLE_RIDE
                                                }
                                                context.startService(intent)
                                            }
                                            if (!streamState.isRideMode) {
                                                onEnsureLocationAndRun { toggle() }
                                            } else {
                                                toggle()
                                            }
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth().height(46.dp)
                                    ) {
                                        Text(
                                            text = if (streamState.isRideMode) "Ride-to-Steps: ON (Tap to Switch to Walk)" else "Ride-to-Steps: OFF (Tap for Transport)",
                                            color = if (streamState.isRideMode) StatusSuccess else AccentSky,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                    Button(
                                        onClick = {
                                            val intent = Intent(context, StepStreamService::class.java).apply {
                                                action = StepStreamService.ACTION_STOP
                                            }
                                            context.startService(intent)
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = StatusDanger, contentColor = TextPrimary),
                                        modifier = Modifier.fillMaxWidth().height(46.dp)
                                    ) {
                                        Text("Stop Stream", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    }
                                }
                            }
                        }
                    }

                    // Activity Logs
                    Card(
                        colors = CardDefaults.cardColors(containerColor = BgSurface1),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                            .border(1.dp, BorderSubtle, RoundedCornerShape(14.dp))
                    ) {
                        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("TELEMETRY LOG", color = AccentSky, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                                Text("${logs.size} events", color = TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(BgCanvas)
                                    .padding(12.dp)
                            ) {
                                if (logs.isEmpty()) {
                                    Text(
                                        "Waiting for activity...",
                                        color = TextSecondary,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                } else {
                                    LazyColumn(
                                        modifier = Modifier.fillMaxSize(),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        items(logs) { line ->
                                            Text(
                                                text = line,
                                                color = TextPrimary,
                                                fontSize = 12.sp,
                                                fontFamily = FontFamily.Monospace,
                                                lineHeight = 17.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            AppTab.HISTORY -> {
                val totalSteps = history.sumOf { it.steps }
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Remove / Reset Today's Steps Card
                    Card(
                        colors = CardDefaults.cardColors(containerColor = BgSurface1),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, BorderSubtle, RoundedCornerShape(14.dp))
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("REMOVE / RESET TODAY'S STEPS", color = StatusDanger, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)

                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val selectedRemove = removeStepsText.toIntOrNull()
                                REMOVE_PRESETS.forEach { (label, value) ->
                                    SpaciousPill(
                                        label = "-$label",
                                        selected = selectedRemove == value,
                                        enabled = !isBusy && !streamState.isRunning,
                                        activeColor = StatusDanger,
                                        mono = true,
                                        onClick = { removeStepsText = value.toString() }
                                    )
                                }
                            }

                            OutlinedTextField(
                                value = removeStepsText,
                                onValueChange = { input -> removeStepsText = input.filter { it.isDigit() } },
                                label = { Text("Steps to Remove") },
                                singleLine = true,
                                shape = RoundedCornerShape(10.dp),
                                enabled = !isBusy && !streamState.isRunning,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth()
                            )

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    enabled = accessToken != null && !isBusy && !streamState.isRunning,
                                    onClick = {
                                        val count = removeStepsText.toIntOrNull() ?: 0
                                        if (count <= 0) {
                                            StreamBus.log("Enter steps > 0 to remove.")
                                            return@Button
                                        }
                                        scope.launch {
                                            isBusy = true
                                            val sid = ensureStream()
                                            if (sid != null && accessToken != null) {
                                                StreamBus.log("Removing up to $count steps today...")
                                                FitnessApiClient.removeStepsToday(accessToken, sid, count, selectedDevice)
                                                    .onSuccess { removed ->
                                                        StreamBus.log("Removed $removed steps from today's stream.")
                                                        if (removed > 0) {
                                                            AppPrefs.addHistory(context, "Removed", -removed, selectedDevice.label)
                                                        }
                                                        refreshTodaySteps()
                                                    }
                                                    .onFailure { StreamBus.log("Remove error: ${it.message}") }
                                            }
                                            isBusy = false
                                        }
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = StatusDanger, contentColor = TextPrimary),
                                    modifier = Modifier.weight(1f).height(44.dp)
                                ) {
                                    Text("Remove Steps", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                }

                                OutlinedButton(
                                    enabled = accessToken != null && !isBusy && !streamState.isRunning,
                                    onClick = {
                                        scope.launch {
                                            isBusy = true
                                            val sid = ensureStream()
                                            if (sid != null && accessToken != null) {
                                                StreamBus.log("Resetting all today's steps on stream...")
                                                FitnessApiClient.removeStepsToday(accessToken, sid, null, selectedDevice)
                                                    .onSuccess {
                                                        StreamBus.log("Reset all today's steps on ${selectedDevice.label}.")
                                                        refreshTodaySteps()
                                                    }
                                                    .onFailure { StreamBus.log("Reset error: ${it.message}") }
                                            }
                                            isBusy = false
                                        }
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.weight(1f).height(44.dp)
                                ) {
                                    Text("Reset All Today", color = StatusDanger, fontSize = 13.sp)
                                }
                            }
                        }
                    }

                    Card(
                        colors = CardDefaults.cardColors(containerColor = BgSurface1),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, BorderSubtle, RoundedCornerShape(14.dp))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("NET RECORDED", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                                Text(
                                    String.format(Locale.US, "%,d steps", totalSteps),
                                    color = StatusSuccess,
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text("${history.size} recorded events", color = TextSecondary, fontSize = 12.sp)
                            }
                            if (history.isNotEmpty()) {
                                OutlinedButton(
                                    onClick = { AppPrefs.clearHistory(context) },
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("Clear", color = StatusDanger, fontSize = 13.sp)
                                }
                            }
                        }
                    }

                    if (history.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(14.dp))
                                .background(BgSurface1)
                                .border(1.dp, BorderSubtle, RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("No injections recorded yet.", color = TextSecondary, fontSize = 14.sp)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(history) { item ->
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = BgSurface1),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(item.mode, color = if (item.steps < 0) StatusDanger else AccentSky, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                            Text("${item.timestamp}  •  ${item.device}", color = TextSecondary, fontSize = 12.sp)
                                        }
                                        Text(
                                            text = if (item.steps >= 0) String.format(Locale.US, "+%,d", item.steps) else String.format(Locale.US, "%,d", item.steps),
                                            color = if (item.steps < 0) StatusDanger else StatusSuccess,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            AppTab.SETTINGS -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = BgSurface1),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, BorderSubtle, RoundedCornerShape(14.dp))
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text("REAL-TIME STREAM CONFIG", color = AccentSky, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)

                            // Cadence Slider
                            Column {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Walking Cadence", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    Text("${config.cadence} steps/min", color = AccentSky, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                                }
                                Slider(
                                    value = config.cadence.toFloat(),
                                    onValueChange = { v ->
                                        AppPrefs.updateConfig(context) { it.copy(cadence = v.roundToInt()) }
                                    },
                                    valueRange = 70f..140f
                                )
                            }

                            // Interval Slider
                            Column {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Upload Interval", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    Text("${config.intervalSec}s", color = AccentSky, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                                }
                                Slider(
                                    value = config.intervalSec.toFloat(),
                                    onValueChange = { v ->
                                        AppPrefs.updateConfig(context) { it.copy(intervalSec = v.roundToInt()) }
                                    },
                                    valueRange = 2f..15f
                                )
                            }

                            // Ride Speed Threshold Slider
                            Column {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Ride-to-Steps Min Speed", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    Text(
                                        String.format(Locale.US, "%.1f km/h", config.rideMinSpeedKmh),
                                        color = StatusSuccess,
                                        fontSize = 14.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                Slider(
                                    value = config.rideMinSpeedKmh,
                                    onValueChange = { v ->
                                        val snapped = (v * 10f).roundToInt() / 10f
                                        AppPrefs.updateConfig(context) { it.copy(rideMinSpeedKmh = snapped) }
                                    },
                                    valueRange = 2.0f..15.0f
                                )
                            }

                            // Auto-clear overlaps toggle
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                    Text("Auto-Clear Overlapping Sessions", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    Text("Deletes conflicting In-Vehicle sessions during sync", color = TextSecondary, fontSize = 12.sp)
                                }
                                Switch(
                                    checked = config.clearOverlaps,
                                    onCheckedChange = { checked ->
                                        AppPrefs.updateConfig(context) { it.copy(clearOverlaps = checked) }
                                    }
                                )
                            }

                            // Daily Goal Slider
                            Column {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Daily Step Goal", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    Text(
                                        String.format(Locale.US, "%,d steps", config.dailyGoal),
                                        color = AccentSky,
                                        fontSize = 14.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                Slider(
                                    value = config.dailyGoal.toFloat(),
                                    onValueChange = { v ->
                                        val snapped = ((v / 1000f).roundToInt() * 1000).coerceIn(2000, 30000)
                                        AppPrefs.updateConfig(context) { it.copy(dailyGoal = snapped) }
                                    },
                                    valueRange = 2000f..30000f
                                )
                            }

                            // Auto-stop at daily goal toggle
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                    Text("Auto-Stop at Daily Goal", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    Text("Stops live stream once today's Google Fit goal is reached", color = TextSecondary, fontSize = 12.sp)
                                }
                                Switch(
                                    checked = config.stopAtDailyGoal,
                                    onCheckedChange = { checked ->
                                        AppPrefs.updateConfig(context) { it.copy(stopAtDailyGoal = checked) }
                                    }
                                )
                            }

                            Text(
                                "All settings update active streams in real-time.",
                                color = StatusSuccess,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpaciousPill(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    activeColor: Color,
    mono: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        color = if (selected) activeColor.copy(alpha = 0.2f) else BgSurface2,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .border(
                width = 1.dp,
                color = if (selected) activeColor.copy(alpha = 0.65f) else BorderSubtle,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Text(
            text = label,
            color = if (selected) activeColor else TextSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
        )
    }
}
