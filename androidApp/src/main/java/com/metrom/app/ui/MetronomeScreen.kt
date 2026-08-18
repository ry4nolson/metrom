@file:OptIn(ExperimentalLayoutApi::class)

package com.metrom.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import android.content.res.Configuration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrom.app.MetronomeViewModel
import com.metrom.shared.data.SongPreset
import com.metrom.shared.detect.DetectDebug
import com.metrom.shared.detect.DetectState
import com.metrom.shared.detect.FailReason
import com.metrom.shared.detect.OnsetEnvelope
import com.metrom.shared.domain.BeatAccent
import com.metrom.shared.domain.MetronomeLimits
import com.metrom.shared.domain.MutePattern
import com.metrom.shared.domain.SessionPhase
import com.metrom.shared.domain.Subdivision
import com.metrom.shared.domain.SwingFeel
import com.metrom.shared.domain.TimeSignature
import com.metrom.shared.practice.MetronomeUiState
import com.metrom.app.ui.theme.Ash
import com.metrom.app.ui.theme.Bone
import com.metrom.app.ui.theme.BackgroundBottom
import com.metrom.app.ui.theme.BackgroundTop
import com.metrom.app.ui.theme.Copper
import com.metrom.app.ui.theme.Ember
import com.metrom.app.ui.theme.EmberDeep
import com.metrom.app.ui.theme.EmberSoft
import com.metrom.app.ui.theme.Ink
import com.metrom.app.ui.theme.InkElevated
import com.metrom.app.ui.theme.InkLine
import com.metrom.app.ui.theme.LocalMetromPalette
import com.metrom.app.ui.theme.Mist
import com.metrom.app.ui.theme.PulseAccent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun MetronomeScreen(viewModel: MetronomeViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val detectState by viewModel.detectState.collectAsStateWithLifecycle()
    val detectDebug by viewModel.detectDebug.collectAsStateWithLifecycle()
    val colorTheme by viewModel.theme.collectAsStateWithLifecycle()
    val savedThemes by viewModel.savedThemes.collectAsStateWithLifecycle()
    val customMeters by viewModel.customMeters.collectAsStateWithLifecycle()
    val isLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val horizontalPad = if (isLandscape) 20.dp else 24.dp
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var openMeters by rememberSaveable { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        BackgroundTop,
                        Ink,
                        BackgroundBottom
                    )
                )
            )
    ) {
        Atmosphere(state)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            if (isLandscape) {
                LandscapeBody(
                    state = state,
                    detectState = detectState,
                    detectDebug = detectDebug,
                    viewModel = viewModel,
                    horizontalPad = horizontalPad,
                    onOpenSettings = { showSettings = true },
                    onOpenCustomMeters = {
                        openMeters = true
                        showSettings = true
                    },
                    modifier = Modifier.weight(1f)
                )
            } else {
                PortraitBody(
                    state = state,
                    detectState = detectState,
                    detectDebug = detectDebug,
                    viewModel = viewModel,
                    horizontalPad = horizontalPad,
                    onOpenSettings = { showSettings = true },
                    onOpenCustomMeters = {
                        openMeters = true
                        showSettings = true
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            TransportDock(
                state = state,
                viewModel = viewModel,
                landscape = isLandscape,
                horizontalPad = horizontalPad
            )
        }

        AnimatedVisibility(
            visible = showSettings,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn() + slideInHorizontally { it },
            exit = fadeOut() + slideOutHorizontally { it }
        ) {
            SettingsScreen(
                state = state,
                viewModel = viewModel,
                colorTheme = colorTheme,
                savedThemes = savedThemes,
                customMeters = customMeters,
                openMeters = openMeters,
                onClose = {
                    showSettings = false
                    openMeters = false
                }
            )
        }
    }
}

@Composable
private fun PortraitBody(
    state: MetronomeUiState,
    detectState: DetectState,
    detectDebug: DetectDebug?,
    viewModel: MetronomeViewModel,
    horizontalPad: Dp,
    onOpenSettings: () -> Unit,
    onOpenCustomMeters: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPad)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        TopBar(state, viewModel, onOpenSettings)
        Spacer(modifier = Modifier.height(10.dp))
        BeatRail(
            state = state,
            onCycle = viewModel::cycleBeatAccent,
            onReset = viewModel::resetBeatAccents
        )
        Text(
            text = "tap beats · strong / normal / mute",
            style = MaterialTheme.typography.labelMedium,
            color = Ash,
            modifier = Modifier
                .padding(top = 6.dp)
                .height(16.dp),
            maxLines = 1
        )
        Spacer(modifier = Modifier.height(12.dp))
        PhaseBanner(state)
        TempoHero(state = state, onNudge = viewModel::nudgeBpm)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = secondaryLabel(state),
            style = MaterialTheme.typography.labelMedium,
            color = phaseColor(state.sessionPhase),
            modifier = Modifier.height(18.dp),
            maxLines = 1
        )
        Spacer(modifier = Modifier.height(10.dp))
        SettingsColumn(
            state = state,
            detectState = detectState,
            detectDebug = detectDebug,
            viewModel = viewModel,
            onOpenCustomMeters = onOpenCustomMeters
        )
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun LandscapeBody(
    state: MetronomeUiState,
    detectState: DetectState,
    detectDebug: DetectDebug?,
    viewModel: MetronomeViewModel,
    horizontalPad: Dp,
    onOpenSettings: () -> Unit,
    onOpenCustomMeters: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPad)
            .padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            TopBar(state, viewModel, onOpenSettings)
            Spacer(modifier = Modifier.height(8.dp))
            BeatRail(
                state = state,
                onCycle = viewModel::cycleBeatAccent,
                onReset = viewModel::resetBeatAccents
            )
            Spacer(modifier = Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                TempoHero(
                    state = state,
                    onNudge = viewModel::nudgeBpm,
                    compact = true
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = secondaryLabel(state),
                    style = MaterialTheme.typography.labelMedium,
                    color = phaseColor(state.sessionPhase),
                    modifier = Modifier.height(18.dp),
                    maxLines = 1
                )
            }
            Spacer(modifier = Modifier.weight(1f))
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
        ) {
            SettingsColumn(
                state = state,
                detectState = detectState,
                detectDebug = detectDebug,
                viewModel = viewModel,
                onOpenCustomMeters = onOpenCustomMeters
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SettingsColumn(
    state: MetronomeUiState,
    detectState: DetectState,
    detectDebug: DetectDebug?,
    viewModel: MetronomeViewModel,
    onOpenCustomMeters: () -> Unit
) {
    ListenTempoStrip(
        state = state,
        detectState = detectState,
        viewModel = viewModel
    )
    if (detectDebug != null) {
        Spacer(modifier = Modifier.height(10.dp))
        ListenDebugPanel(
            debug = detectDebug,
            onApplyBpm = viewModel::applyListenBpm,
            onClear = viewModel::clearListenDebug
        )
    }
    Spacer(modifier = Modifier.height(12.dp))
    TempoPresets(state.bpm, onSelect = viewModel::setBpm)
    PracticeStrip(state)
    Spacer(modifier = Modifier.height(18.dp))
    ControlRows(state, viewModel, onOpenCustomMeters)
    Spacer(modifier = Modifier.height(14.dp))
    ExpandablePanel(
        title = "PRACTICE",
        summary = practiceSummary(state),
        initiallyExpanded = state.trainerEnabled || state.mutePattern.silentBars > 0
    ) {
        PracticePanelBody(state, viewModel)
    }
    Spacer(modifier = Modifier.height(12.dp))
    ExpandablePanel(
        title = "SONGS",
        summary = if (state.songs.isEmpty()) "bookmark to save" else "${state.songs.size} saved",
        initiallyExpanded = false
    ) {
        SongsPanelBody(state, viewModel)
    }
}

@Composable
private fun TransportDock(
    state: MetronomeUiState,
    viewModel: MetronomeViewModel,
    landscape: Boolean,
    horizontalPad: Dp
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Ink.copy(alpha = 0.92f),
                        Ink
                    )
                )
            )
            .padding(horizontal = horizontalPad)
            .padding(top = 10.dp, bottom = 10.dp)
    ) {
        if (landscape) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                VolumeRow(
                    state = state,
                    viewModel = viewModel,
                    modifier = Modifier.weight(1f)
                )
                TransportRow(
                    state = state,
                    viewModel = viewModel,
                    compact = true,
                    modifier = Modifier.weight(1f)
                )
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                VolumeRow(state, viewModel)
                Spacer(modifier = Modifier.height(14.dp))
                TransportRow(state, viewModel)
            }
        }
    }
}

@Composable
private fun Atmosphere(state: MetronomeUiState) {
    var swingDeg by remember { mutableFloatStateOf(0f) }
    var hit by remember { mutableFloatStateOf(0f) }
    var apexSign by remember { mutableIntStateOf(1) }
    var epochMs by remember { mutableLongStateOf(0L) }

    val playingRef = rememberUpdatedState(state.isPlaying)
    val bpmRef = rememberUpdatedState(state.bpm)
    val groupRef = rememberUpdatedState(state.groupTempo)
    val accentRef = rememberUpdatedState(state.isAccentBeat)
    val beatAtRef = rememberUpdatedState(state.beatAtMs)
    val beatFlashRef = rememberUpdatedState(state.beatFlash)
    val palette = LocalMetromPalette.current
    val ampDeg = 38f

    // Exact rail-beat period — never blend measured gaps (Handler jitter desynced the swing).
    fun railPeriodMs(): Float {
        var ms = 60_000f / bpmRef.value.coerceIn(30, 300)
        if (groupRef.value) ms /= 3f
        return ms
    }

    LaunchedEffect(state.isPlaying) {
        if (!state.isPlaying) {
            apexSign = 1
            epochMs = 0L
            hit = 0f
            var last = 0L
            while (abs(swingDeg) > 0.3f) {
                withFrameMillis {
                    val now = SystemClock.uptimeMillis()
                    val dt = if (last == 0L) 0.016 else ((now - last).coerceIn(1L, 32L)) / 1000.0
                    last = now
                    swingDeg = (swingDeg * exp(-10.0 * dt)).toFloat()
                    if (abs(swingDeg) < 0.3f) swingDeg = 0f
                }
            }
            swingDeg = 0f
            return@LaunchedEffect
        }

        var lastFlash = 0L
        while (true) {
            withFrameMillis {
                if (!playingRef.value) return@withFrameMillis
                val now = SystemClock.uptimeMillis()
                val flash = beatFlashRef.value
                if (flash != 0L && flash != lastFlash) {
                    lastFlash = flash
                    apexSign *= -1
                    epochMs = beatAtRef.value
                }
                val epoch = epochMs
                if (epoch == 0L) {
                    swingDeg = 0f
                    hit = 0f
                    return@withFrameMillis
                }
                val period = railPeriodMs()
                val age = (now - epoch).toFloat()
                val p = (age / period).coerceIn(0f, 1f)
                swingDeg = apexSign * ampDeg * cos(PI * p).toFloat()
                // Hit envelope peaks at hear-time (age≈0), not when Compose catches up.
                hit = when {
                    age < 0f -> 0f
                    age < 40f -> 1f - age / 40f
                    age < 160f -> (1f - (age - 40f) / 120f) * 0.45f
                    else -> 0f
                }
                if (accentRef.value && age in 0f..50f) {
                    hit = hit.coerceAtLeast(0.85f)
                }
            }
        }
    }

    val breathe by rememberInfiniteTransition(label = "breathe").animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(3600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breatheAnim"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val pivot = Offset(size.width / 2f, size.height * 0.19f)
        val armLen = size.minDimension * 0.42f
        val glowColor = when {
            state.sessionPhase == SessionPhase.SILENT -> palette.ash
            accentRef.value -> palette.pulse
            else -> palette.ember
        }
        val stage = Offset(size.width / 2f, size.height * 0.34f)
        val base = size.minDimension * 0.24f * if (state.isPlaying) breathe else 1f

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    glowColor.copy(alpha = 0.16f * hit + if (state.isPlaying) 0.045f else 0.02f),
                    Color.Transparent
                ),
                center = stage,
                radius = base * (1.75f + hit * 0.55f)
            ),
            radius = base * (1.75f + hit * 0.55f),
            center = stage
        )

        val arcBox = androidx.compose.ui.geometry.Size(armLen * 2f, armLen * 2f)
        val arcOrigin = Offset(pivot.x - armLen, pivot.y - armLen)
        drawArc(
            color = palette.inkLine.copy(alpha = 0.4f),
            startAngle = 90f - 50f,
            sweepAngle = 100f,
            useCenter = false,
            topLeft = arcOrigin,
            size = arcBox,
            style = Stroke(width = 1.15.dp.toPx(), cap = StrokeCap.Round)
        )
        for (tick in listOf(-ampDeg, 0f, ampDeg)) {
            val tickRad = Math.toRadians(tick.toDouble())
            val inner = armLen * 0.92f
            val outer = armLen * 1.02f
            drawLine(
                color = palette.mist.copy(alpha = if (tick == 0f) 0.35f else 0.28f),
                start = Offset(
                    pivot.x + (inner * sin(tickRad)).toFloat(),
                    pivot.y + (inner * cos(tickRad)).toFloat()
                ),
                end = Offset(
                    pivot.x + (outer * sin(tickRad)).toFloat(),
                    pivot.y + (outer * cos(tickRad)).toFloat()
                ),
                strokeWidth = 1.5.dp.toPx(),
                cap = StrokeCap.Round
            )
        }

        val angleRad = Math.toRadians(swingDeg.toDouble())
        val tip = Offset(
            x = pivot.x + (armLen * sin(angleRad)).toFloat(),
            y = pivot.y + (armLen * cos(angleRad)).toFloat()
        )
        val bpm = bpmRef.value.coerceIn(30, 300)
        val weightT = (1f - (bpm - 30f) / 270f).coerceIn(0.22f, 0.82f)
        val weightCenter = Offset(
            x = pivot.x + (armLen * weightT * sin(angleRad)).toFloat(),
            y = pivot.y + (armLen * weightT * cos(angleRad)).toFloat()
        )

        if (state.isPlaying && hit < 0.85f) {
            val ghostDeg = swingDeg - apexSign * 6f * (1f - hit)
            val gRad = Math.toRadians(ghostDeg.toDouble())
            val ghost = Offset(
                pivot.x + (armLen * weightT * sin(gRad)).toFloat(),
                pivot.y + (armLen * weightT * cos(gRad)).toFloat()
            )
            drawCircle(
                color = glowColor.copy(alpha = 0.12f + hit * 0.08f),
                radius = 10.dp.toPx(),
                center = ghost
            )
        }

        drawLine(
            color = palette.mist.copy(alpha = if (state.isPlaying) 0.78f else 0.3f),
            start = pivot,
            end = tip,
            strokeWidth = 2.4.dp.toPx(),
            cap = StrokeCap.Round
        )
        drawCircle(
            color = palette.copper.copy(alpha = 0.85f),
            radius = 2.4.dp.toPx(),
            center = tip
        )

        val w = 13.dp.toPx() + hit * 4.dp.toPx()
        val h = 18.dp.toPx() + hit * 2.dp.toPx()
        rotate(degrees = swingDeg, pivot = weightCenter) {
            val path = Path().apply {
                addRoundRect(
                    RoundRect(
                        left = weightCenter.x - w / 2f,
                        top = weightCenter.y - h / 2f,
                        right = weightCenter.x + w / 2f,
                        bottom = weightCenter.y + h / 2f,
                        radiusX = 3.dp.toPx(),
                        radiusY = 3.dp.toPx()
                    )
                )
            }
            drawPath(
                path = path,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        glowColor.copy(alpha = 0.98f),
                        palette.ember.copy(alpha = 0.9f),
                        palette.emberDeep
                    ),
                    startY = weightCenter.y - h / 2f,
                    endY = weightCenter.y + h / 2f
                )
            )
            drawLine(
                color = palette.bone.copy(alpha = 0.35f),
                start = Offset(weightCenter.x, weightCenter.y - h * 0.28f),
                end = Offset(weightCenter.x, weightCenter.y + h * 0.28f),
                strokeWidth = 1.6.dp.toPx(),
                cap = StrokeCap.Round
            )
        }

        drawCircle(color = palette.inkElevated, radius = 7.dp.toPx(), center = pivot)
        drawCircle(
            color = palette.copper.copy(alpha = 0.95f),
            radius = 4.dp.toPx() + hit * 1.2.dp.toPx(),
            center = pivot
        )
        drawCircle(color = palette.bone.copy(alpha = 0.35f), radius = 1.6.dp.toPx(), center = pivot)

        if (hit > 0.05f) {
            drawCircle(
                color = glowColor.copy(alpha = 0.22f * hit),
                radius = 22.dp.toPx() * hit,
                center = weightCenter
            )
        }
    }
}

@Composable
private fun TopBar(
    state: MetronomeUiState,
    viewModel: MetronomeViewModel,
    onOpenSettings: () -> Unit
) {
    var saving by remember { mutableStateOf(false) }
    var saveName by remember { mutableStateOf("") }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "METROM",
                style = MaterialTheme.typography.headlineMedium,
                color = Bone
            )
            Text(
                text = state.statusLine,
                style = MaterialTheme.typography.labelMedium,
                color = phaseColor(state.sessionPhase)
            )
        }
        IconButton(
            onClick = {
                saveName = SongPreset.autoName(state.bpm, state.timeSignature, state.subdivision)
                saving = true
            }
        ) {
            Icon(Icons.Rounded.BookmarkAdd, contentDescription = "Save song", tint = Copper)
        }
        IconButton(onClick = viewModel::toggleMute) {
            Icon(
                imageVector = if (state.muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                contentDescription = "Mute",
                tint = if (state.muted) Ash else Bone
            )
        }
        IconButton(onClick = onOpenSettings) {
            Icon(
                imageVector = Icons.Rounded.Settings,
                contentDescription = "Settings",
                tint = Bone
            )
        }
    }

    if (saving) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { saving = false },
            title = { Text("Save song", color = Bone) },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = saveName,
                    onValueChange = { saveName = it },
                    singleLine = true,
                    label = { Text("Name") }
                )
            },
            confirmButton = {
                Text(
                    "SAVE",
                    color = EmberSoft,
                    modifier = Modifier
                        .clickable {
                            viewModel.saveCurrentSong(saveName)
                            saving = false
                        }
                        .padding(8.dp)
                )
            },
            dismissButton = {
                Text(
                    "CANCEL",
                    color = Ash,
                    modifier = Modifier
                        .clickable { saving = false }
                        .padding(8.dp)
                )
            },
            containerColor = InkElevated
        )
    }
}


@Composable
private fun PhaseBanner(state: MetronomeUiState) {
    val (title, detail) = phaseBannerCopy(state)
    // Fixed height so count-in / YOUR MOVE never shove the BPM block
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
    ) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.displayMedium.copy(fontSize = 32.sp),
                color = phaseColor(state.sessionPhase),
                textAlign = TextAlign.Center,
                maxLines = 1
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelMedium,
                    color = Ash,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
            }
        }
    }
}

private fun phaseBannerCopy(state: MetronomeUiState): Pair<String?, String?> {
    if (!state.isPlaying) return null to null
    return when (state.sessionPhase) {
        SessionPhase.COUNT_IN -> {
            val remaining = (state.countInBars - state.sessionBar).coerceAtLeast(1)
            remaining.toString() to if (remaining == 1) "LAST BAR · COUNT IN" else "BARS LEFT · COUNT IN"
        }
        SessionPhase.SILENT -> "YOUR MOVE" to "keep the pulse"
        SessionPhase.TRAINER_DONE -> "LOCKED" to ("target " + state.trainerTargetBpm)
        SessionPhase.PLAYING -> {
            if (state.trainerEnabled && state.tapHint?.startsWith("TRAIN →") == true) {
                state.tapHint to "tempo step"
            } else {
                null to null
            }
        }
        else -> null to null
    }
}

@Composable
private fun ExpandablePanel(
    title: String,
    summary: String,
    initiallyExpanded: Boolean,
    content: @Composable () -> Unit
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(InkElevated)
            .border(1.dp, InkLine, RoundedCornerShape(18.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelMedium, color = Copper)
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = Ash)
            }
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = Ash
            )
        }
        if (expanded) {
            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                content()
            }
        }
    }
}

private fun practiceSummary(state: MetronomeUiState): String {
    val parts = mutableListOf<String>()
    parts += if (state.countInBars == 0) "no count-in" else ("count-in " + state.countInBars)
    parts += if (state.mutePattern.silentBars == 0) "mute off" else ("mute " + state.mutePattern.label)
    if (state.trainerEnabled) {
        parts += ("train →" + state.trainerTargetBpm)
    }
    return parts.joinToString(" · ")
}

@Composable
private fun BeatRail(
    state: MetronomeUiState,
    onCycle: (Int) -> Unit,
    onReset: () -> Unit
) {
    val dimmed = state.sessionPhase == SessionPhase.SILENT
    var hit by remember { mutableFloatStateOf(0f) }
    val beatAtRef = rememberUpdatedState(state.beatAtMs)
    val beatFlashRef = rememberUpdatedState(state.beatFlash)
    val playingRef = rememberUpdatedState(state.isPlaying)

    LaunchedEffect(state.isPlaying) {
        if (!state.isPlaying) {
            hit = 0f
            return@LaunchedEffect
        }
        var lastFlash = 0L
        while (true) {
            withFrameMillis {
                if (!playingRef.value) return@withFrameMillis
                val flash = beatFlashRef.value
                if (flash != 0L && flash != lastFlash) lastFlash = flash
                val age = (SystemClock.uptimeMillis() - beatAtRef.value).toFloat()
                hit = when {
                    flash == 0L -> 0f
                    age < 0f -> 0f
                    age < 50f -> 1f - age / 50f
                    else -> 0f
                }
            }
        }
    }

    // Fixed geometry — animating bar height was bouncing the whole scroll column
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = { onReset() })
            },
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.Bottom
    ) {
        repeat(state.timeSignature.beats) { index ->
            val level = state.beatAccents.getOrElse(index) {
                if (index == 0) BeatAccent.STRONG else BeatAccent.NORMAL
            }
            val active = state.isPlaying && state.activeBeat == index
            val barHeight = when (level) {
                BeatAccent.STRONG -> 14.dp
                BeatAccent.NORMAL -> 8.dp
                BeatAccent.MUTE -> 4.dp
            }
            val color = when {
                dimmed && active -> Ash.copy(alpha = 0.65f)
                active && level == BeatAccent.STRONG -> PulseAccent
                active && level == BeatAccent.MUTE -> Ash.copy(alpha = 0.45f)
                active -> Ember
                level == BeatAccent.STRONG -> InkLine.copy(alpha = 0.95f)
                level == BeatAccent.MUTE -> InkLine.copy(alpha = 0.22f)
                else -> InkLine.copy(alpha = 0.5f)
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable { onCycle(index) }
                    .scale(if (active) 1f + hit * 0.1f else 1f)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(barHeight)
                        .clip(RoundedCornerShape(99.dp))
                        .then(
                            if (level == BeatAccent.MUTE) {
                                Modifier.border(1.dp, Ash.copy(alpha = 0.45f), RoundedCornerShape(99.dp))
                            } else {
                                Modifier
                            }
                        )
                        .background(color)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = (index + 1).toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = when {
                        active && level == BeatAccent.STRONG -> PulseAccent
                        active -> EmberSoft
                        level == BeatAccent.STRONG -> Mist
                        level == BeatAccent.MUTE -> Ash.copy(alpha = 0.55f)
                        else -> Ash
                    }
                )
            }
        }
    }
}

@Composable
private fun TempoPresets(currentBpm: Int, onSelect: (Int) -> Unit) {
    val presets = listOf(60, 72, 80, 92, 100, 120, 140, 160)
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        presets.forEach { bpm ->
            ChoiceChip(
                label = bpm.toString(),
                selected = currentBpm == bpm,
                onClick = { onSelect(bpm) }
            )
        }
    }
}

@Composable
private fun PracticeStrip(state: MetronomeUiState) {
    val showMute = state.mutePattern.silentBars > 0
    val showTrainer = state.trainerEnabled
    if (!showMute && !showTrainer) {
        Spacer(modifier = Modifier.height(0.dp))
        return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp)
            .heightIn(min = 40.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (showMute) {
            val play = state.mutePattern.playBars
            val silent = state.mutePattern.silentBars
            val cycle = play + silent
            val practiceBar = if (state.isPlaying && state.sessionBar >= state.countInBars) {
                (state.sessionBar - state.countInBars) % cycle
            } else {
                -1
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                repeat(cycle) { index ->
                    val isSilentSlot = index >= play
                    val isCurrent = index == practiceBar
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(8.dp)
                            .clip(RoundedCornerShape(99.dp))
                            .background(
                                when {
                                    isCurrent && isSilentSlot -> Mist
                                    isCurrent -> Ember
                                    isSilentSlot -> InkLine.copy(alpha = 0.35f)
                                    else -> Ember.copy(alpha = 0.35f)
                                }
                            )
                    )
                }
            }
            Text(
                text = if (state.sessionPhase == SessionPhase.SILENT) "MUTE CYCLE · YOUR BARS"
                else "MUTE CYCLE · ${state.mutePattern.label}",
                style = MaterialTheme.typography.labelMedium,
                color = if (state.sessionPhase == SessionPhase.SILENT) Mist else Ash
            )
        }

        if (showTrainer) {
            val start = state.trainerStartBpm.coerceAtMost(state.trainerTargetBpm)
            val span = (state.trainerTargetBpm - start).coerceAtLeast(1)
            val progress = ((state.bpm - start).toFloat() / span).coerceIn(0f, 1f)
            val practice = (state.sessionBar - state.countInBars).coerceAtLeast(0)
            val every = state.trainerEveryBars.coerceAtLeast(1)
            val until = if (state.isPlaying && state.sessionBar >= state.countInBars) {
                every - (practice % every)
            } else every

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(InkLine)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .height(6.dp)
                        .clip(RoundedCornerShape(99.dp))
                        .background(Ember)
                )
            }
            Text(
                text = "TRAIN $start→${state.trainerTargetBpm} · +${state.trainerStep} in $until",
                style = MaterialTheme.typography.labelMedium,
                color = EmberSoft
            )
        }
    }
}

@Composable
private fun ListenTempoStrip(
    state: MetronomeUiState,
    detectState: DetectState,
    viewModel: MetronomeViewModel
) {
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    var askedOnce by rememberSaveable { mutableStateOf(false) }
    var showRationale by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        askedOnce = true
        if (granted) {
            showRationale = false
            viewModel.startListen()
        } else {
            val permanent = activity != null &&
                !ActivityCompat.shouldShowRequestPermissionRationale(
                    activity,
                    Manifest.permission.RECORD_AUDIO
                )
            showRationale = true
            if (permanent) {
                // Keep rationale visible; Settings chip is offered below.
            }
        }
    }

    fun requestMicOrStart() {
        if (state.isPlaying) return
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            showRationale = false
            viewModel.startListen()
            return
        }
        if (activity != null &&
            askedOnce &&
            !ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                Manifest.permission.RECORD_AUDIO
            )
        ) {
            showRationale = true
            return
        }
        if (activity != null &&
            ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                Manifest.permission.RECORD_AUDIO
            )
        ) {
            showRationale = true
        }
        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    LaunchedEffect(detectState) {
        when (detectState) {
            is DetectState.Failed -> {
                when (detectState.reason) {
                    // Keep NO_CLEAR_BEAT up so the debug panel stays readable.
                    FailReason.NO_CLEAR_BEAT -> Unit
                    FailReason.CANCELLED -> viewModel.resetListen()
                    FailReason.PERMISSION_DENIED -> showRationale = true
                    FailReason.MIC_UNAVAILABLE -> {
                        delay(1800)
                        viewModel.resetListen()
                    }
                }
            }
            else -> Unit
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when (val ds = detectState) {
            DetectState.Idle -> {
                val enabled = !state.isPlaying
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.alpha(if (enabled) 1f else 0.35f)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Hearing,
                        contentDescription = null,
                        tint = if (enabled) Copper else Ash,
                        modifier = Modifier.size(18.dp)
                    )
                    ChoiceChip(
                        label = "Listen",
                        selected = false,
                        onClick = { if (enabled) requestMicOrStart() }
                    )
                    if (!enabled) {
                        Text(
                            text = "stop to listen",
                            style = MaterialTheme.typography.labelMedium,
                            color = Ash
                        )
                    }
                }
            }

            is DetectState.Listening -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier.size(36.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        val inkLine = InkLine
                        val ember = Ember
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val stroke = 3.dp.toPx()
                            drawCircle(
                                color = inkLine,
                                style = Stroke(width = stroke)
                            )
                            drawArc(
                                color = ember,
                                startAngle = -90f,
                                sweepAngle = 360f * ds.progress,
                                useCenter = false,
                                style = Stroke(width = stroke, cap = StrokeCap.Round)
                            )
                        }
                        Text(
                            text = "${(ds.progress * 8f).roundToInt()}s",
                            style = MaterialTheme.typography.labelMedium,
                            color = Mist,
                            fontSize = 10.sp
                        )
                    }
                    Text(
                        text = "listening…",
                        style = MaterialTheme.typography.labelLarge,
                        color = EmberSoft
                    )
                    ChoiceChip(
                        label = "Cancel",
                        selected = false,
                        onClick = viewModel::cancelListen
                    )
                }
            }

            DetectState.Analyzing -> {
                Text(
                    text = "finding the beat…",
                    style = MaterialTheme.typography.labelLarge,
                    color = Copper
                )
            }

            is DetectState.Success -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "pick a tempo",
                        style = MaterialTheme.typography.labelLarge,
                        color = Bone
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ds.options.forEach { bpm ->
                            ChoiceChip(
                                label = bpm.toString(),
                                selected = false,
                                onClick = { viewModel.applyListenBpm(bpm) }
                            )
                        }
                        ChoiceChip(
                            label = "Dismiss",
                            selected = false,
                            onClick = viewModel::resetListen
                        )
                    }
                }
            }

            is DetectState.Failed -> {
                when (ds.reason) {
                    FailReason.NO_CLEAR_BEAT -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "couldn't find a beat",
                                style = MaterialTheme.typography.labelLarge,
                                color = Ash
                            )
                            ChoiceChip(
                                label = "Dismiss",
                                selected = false,
                                onClick = viewModel::resetListen
                            )
                        }
                    }
                    FailReason.MIC_UNAVAILABLE -> {
                        Text(
                            text = "mic unavailable",
                            style = MaterialTheme.typography.labelLarge,
                            color = Ash
                        )
                    }
                    FailReason.PERMISSION_DENIED -> {
                        // Rationale row below.
                    }
                    FailReason.CANCELLED -> Unit
                }
            }
        }

        val failedReason = (detectState as? DetectState.Failed)?.reason
        if (showRationale || failedReason == FailReason.PERMISSION_DENIED) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "mic access needed to listen",
                    style = MaterialTheme.typography.labelMedium,
                    color = Ash
                )
                ChoiceChip(
                    label = "Allow",
                    selected = false,
                    onClick = {
                        val permanent = activity != null &&
                            askedOnce &&
                            !ActivityCompat.shouldShowRequestPermissionRationale(
                                activity,
                                Manifest.permission.RECORD_AUDIO
                            )
                        if (permanent) {
                            val intent = Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null)
                            )
                            context.startActivity(intent)
                        } else {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                )
                if (askedOnce && activity != null &&
                    !ActivityCompat.shouldShowRequestPermissionRationale(
                        activity,
                        Manifest.permission.RECORD_AUDIO
                    )
                ) {
                    ChoiceChip(
                        label = "Settings",
                        selected = false,
                        onClick = {
                            val intent = Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null)
                            )
                            context.startActivity(intent)
                        }
                    )
                }
                ChoiceChip(
                    label = "Dismiss",
                    selected = false,
                    onClick = {
                        showRationale = false
                        viewModel.resetListen()
                    }
                )
            }
        }
    }
}

@Composable
private fun ListenDebugPanel(
    debug: DetectDebug,
    onApplyBpm: (Int) -> Unit,
    onClear: () -> Unit
) {
    val summary = buildString {
        append(if (debug.accepted) "options" else "rejected")
        append(" · conf ${"%.2f".format(debug.confidence)}")
        debug.bpm?.let { append(" · overlay $it") }
        if (debug.octaveDoubled) append(" · ×2")
    }
    ExpandablePanel(
        title = "LISTEN DEBUG",
        summary = summary,
        initiallyExpanded = true
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "waveform + assumed beats",
                style = MaterialTheme.typography.labelMedium,
                color = Ash
            )
            DebugWaveform(
                samples = debug.waveform,
                beatTimesSec = debug.beatTimesSec,
                durationSec = debug.durationSec,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
            )

            Text(
                text = "onset envelope + beats",
                style = MaterialTheme.typography.labelMedium,
                color = Ash
            )
            DebugOnset(
                onset = debug.onset,
                beatTimesSec = debug.beatTimesSec,
                durationSec = debug.durationSec,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
            )

            Text(
                text = "autocorrelation (30–300 BPM)",
                style = MaterialTheme.typography.labelMedium,
                color = Ash
            )
            DebugAcf(
                acf = debug.acf,
                winnerBpm = debug.bpm,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
            )

            Text(
                text = buildString {
                    append("conf ${"%.3f".format(debug.confidence)}")
                    append(if (debug.accepted) " ≥ 0.30 → accept" else " < 0.30 → reject")
                    if (debug.octaveDoubled) append(" · octave doubled")
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (debug.accepted) EmberSoft else Ash
            )

            if (debug.candidates.isNotEmpty()) {
                Text(
                    text = "candidates (tap to try)",
                    style = MaterialTheme.typography.labelMedium,
                    color = Ash
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    debug.candidates.forEach { c ->
                        val label = buildString {
                            append("${c.bpm}")
                            if (c.isWinner) append(" ★")
                            c.promotedFrom?.let { append(" ←$it") }
                            append("  raw ${"%.3f".format(c.rawPeak)}")
                            append("  score ${"%.3f".format(c.score)}")
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (c.isWinner) Ember.copy(alpha = 0.14f)
                                    else InkElevated
                                )
                                .border(
                                    1.dp,
                                    if (c.isWinner) Ember.copy(alpha = 0.55f) else InkLine,
                                    RoundedCornerShape(10.dp)
                                )
                                .clickable { onApplyBpm(c.bpm) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (c.isWinner) EmberSoft else Mist,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "use",
                                style = MaterialTheme.typography.labelMedium,
                                color = Copper
                            )
                        }
                    }
                }
            }

            val debugBpm = debug.bpm
            if (debugBpm != null && debug.octaveDoubled) {
                Text(
                    text = "final after ×2: $debugBpm",
                    style = MaterialTheme.typography.labelMedium,
                    color = Copper
                )
                ChoiceChip(
                    label = "Use $debugBpm",
                    selected = true,
                    onClick = { onApplyBpm(debugBpm) }
                )
            }

            ChoiceChip(
                label = "Clear debug",
                selected = false,
                onClick = onClear
            )
        }
    }
}

@Composable
private fun DebugWaveform(
    samples: FloatArray,
    beatTimesSec: FloatArray,
    durationSec: Float,
    modifier: Modifier = Modifier
) {
    val mist = Mist
    val emberSoft = EmberSoft
    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Ink)
            .border(1.dp, InkLine, RoundedCornerShape(12.dp))
            .padding(4.dp)
    ) {
        if (samples.isEmpty()) return@Canvas
        val midY = size.height / 2f
        var maxAbs = 1e-6f
        for (v in samples) {
            val a = abs(v)
            if (a > maxAbs) maxAbs = a
        }
        val path = Path()
        val n = samples.size
        for (i in 0 until n) {
            val x = i.toFloat() / (n - 1).coerceAtLeast(1) * size.width
            val y = midY - (samples[i] / maxAbs) * midY * 0.9f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = mist, style = Stroke(width = 1.5f))
        drawBeatMarkers(beatTimesSec, durationSec, emberSoft.copy(alpha = 0.85f))
    }
}

@Composable
private fun DebugOnset(
    onset: FloatArray,
    beatTimesSec: FloatArray,
    durationSec: Float,
    modifier: Modifier = Modifier
) {
    val copper = Copper
    val ember = Ember
    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Ink)
            .border(1.dp, InkLine, RoundedCornerShape(12.dp))
            .padding(4.dp)
    ) {
        if (onset.isEmpty()) return@Canvas
        var max = 1e-6f
        for (v in onset) if (v > max) max = v
        val path = Path()
        val n = onset.size
        for (i in 0 until n) {
            val x = i.toFloat() / (n - 1).coerceAtLeast(1) * size.width
            val y = size.height - (onset[i] / max) * size.height * 0.92f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = copper, style = Stroke(width = 1.5f))
        drawBeatMarkers(beatTimesSec, durationSec, ember.copy(alpha = 0.9f))
    }
}

@Composable
private fun DebugAcf(
    acf: FloatArray,
    winnerBpm: Int?,
    modifier: Modifier = Modifier
) {
    val mist = Mist
    val ember = Ember
    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Ink)
            .border(1.dp, InkLine, RoundedCornerShape(12.dp))
            .padding(4.dp)
    ) {
        val lo = DetectDebug.ACF_MIN_LAG
        val hi = DetectDebug.ACF_MAX_LAG
        if (acf.size <= hi) return@Canvas
        var minV = Float.MAX_VALUE
        var maxV = -Float.MAX_VALUE
        for (lag in lo..hi) {
            val v = acf[lag]
            if (v < minV) minV = v
            if (v > maxV) maxV = v
        }
        val span = (maxV - minV).coerceAtLeast(1e-6f)
        val path = Path()
        val count = hi - lo
        for (lag in lo..hi) {
            val x = (lag - lo).toFloat() / count * size.width
            val y = size.height - ((acf[lag] - minV) / span) * size.height * 0.92f
            if (lag == lo) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = mist, style = Stroke(width = 1.5f))
        if (winnerBpm != null && winnerBpm > 0) {
            val lag = (60f * OnsetEnvelope.ENVELOPE_RATE / winnerBpm)
                .roundToInt()
                .coerceIn(lo, hi)
            val x = (lag - lo).toFloat() / count * size.width
            drawLine(
                color = ember,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 2f
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBeatMarkers(
    beatTimesSec: FloatArray,
    durationSec: Float,
    color: Color
) {
    if (durationSec <= 0f || beatTimesSec.isEmpty()) return
    for (t in beatTimesSec) {
        val x = (t / durationSec).coerceIn(0f, 1f) * size.width
        drawLine(
            color = color,
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = 1.2f
        )
    }
}

@Composable
private fun TempoHero(
    state: MetronomeUiState,
    onNudge: (Int) -> Unit,
    compact: Boolean = false
) {
    var dragAccum by remember { mutableFloatStateOf(0f) }
    val beatScale = remember { Animatable(1f) }
    val bpmSize = if (compact) 64.sp else 100.sp
    val bpmBoxHeight = if (compact) 72.dp else 108.dp

    LaunchedEffect(state.beatFlash) {
        if (!state.isPlaying || state.beatFlash == 0L) return@LaunchedEffect
        val accent = state.sessionPhase != SessionPhase.SILENT && state.isAccentBeat
        beatScale.snapTo(if (accent) 1.06f else if (state.sessionPhase == SessionPhase.SILENT) 1.02f else 1.03f)
        beatScale.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 500f))
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        RoundIconButton(onClick = { onNudge(-1) }) {
            Icon(Icons.Filled.Remove, contentDescription = "Slower", tint = Mist)
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .scale(beatScale.value)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = { dragAccum = 0f },
                        onDragCancel = { dragAccum = 0f }
                    ) { change, dragAmount ->
                        change.consume()
                        dragAccum -= dragAmount.y
                        while (dragAccum >= 14f) {
                            onNudge(1)
                            dragAccum -= 14f
                        }
                        while (dragAccum <= -14f) {
                            onNudge(-1)
                            dragAccum += 14f
                        }
                    }
                }
        ) {
            Box(
                modifier = Modifier.height(bpmBoxHeight),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = state.bpm.toString(),
                    style = MaterialTheme.typography.displayLarge.copy(fontSize = bpmSize),
                    color = if (state.sessionPhase == SessionPhase.SILENT) Ash else Bone,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
            }
            Text(
                text = state.tapHint ?: "BPM · drag to change",
                style = MaterialTheme.typography.labelMedium,
                color = if (state.tapHint != null) EmberSoft else Ash,
                modifier = Modifier.height(18.dp),
                maxLines = 1
            )
        }

        RoundIconButton(onClick = { onNudge(1) }) {
            Icon(Icons.Filled.Add, contentDescription = "Faster", tint = Mist)
        }
    }
}

@Composable
private fun ControlRows(
    state: MetronomeUiState,
    viewModel: MetronomeViewModel,
    onOpenCustomMeters: () -> Unit
) {
    val customMeters by viewModel.customMeters.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        LabeledChipRow(label = "METER") {
            TimeSignature.COMMON.forEach { sig ->
                ChoiceChip(
                    label = sig.label,
                    selected = state.timeSignature == sig,
                    onClick = { viewModel.setTimeSignature(sig) }
                )
            }
            customMeters.forEach { sig ->
                ChoiceChip(
                    label = sig.label,
                    selected = state.timeSignature == sig,
                    onClick = { viewModel.setTimeSignature(sig) }
                )
            }
            ChoiceChip(
                label = "Custom",
                selected = false,
                onClick = onOpenCustomMeters
            )
            if (state.accentsCustomized) {
                ChoiceChip(
                    label = "Reset accents",
                    selected = false,
                    onClick = viewModel::resetBeatAccents
                )
            }
        }
        LabeledChipRow(label = "GRID") {
            Subdivision.entries.forEach { sub ->
                ChoiceChip(
                    label = sub.label,
                    selected = state.subdivision == sub,
                    onClick = { viewModel.setSubdivision(sub) }
                )
            }
        }
        if (state.subdivision == Subdivision.EIGHTH || state.subdivision == Subdivision.SIXTEENTH) {
            LabeledChipRow(label = "SWING") {
                SwingFeel.entries.forEach { feel ->
                    ChoiceChip(
                        label = feel.label,
                        selected = state.swing == feel,
                        onClick = { viewModel.setSwing(feel) }
                    )
                }
            }
        }
        if (state.timeSignature.isCompound) {
            LabeledChipRow(label = "BPM MEANS") {
                ChoiceChip(
                    label = "Each pulse",
                    selected = !state.groupTempo,
                    onClick = {
                        if (state.groupTempo) viewModel.toggleGroupTempo()
                    }
                )
                ChoiceChip(
                    label = "Dotted beat",
                    selected = state.groupTempo,
                    onClick = {
                        if (!state.groupTempo) viewModel.toggleGroupTempo()
                    }
                )
            }
        }
    }
}

@Composable
private fun PracticePanelBody(state: MetronomeUiState, viewModel: MetronomeViewModel) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        LabeledChipRow(label = "COUNT IN") {
            listOf(0, 1, 2, 4).forEach { bars ->
                ChoiceChip(
                    label = if (bars == 0) "Off" else "${bars} bar",
                    selected = state.countInBars == bars,
                    onClick = { viewModel.setCountInBars(bars) }
                )
            }
        }

        LabeledChipRow(label = "MUTE BARS") {
            MutePattern.OPTIONS.forEach { pattern ->
                ChoiceChip(
                    label = pattern.label,
                    selected = state.mutePattern == pattern,
                    onClick = { viewModel.setMutePattern(pattern) }
                )
            }
        }

        LabeledChipRow(label = "TRAINER") {
            ChoiceChip(
                label = if (state.trainerEnabled) "On" else "Off",
                selected = state.trainerEnabled,
                onClick = viewModel::toggleTrainer
            )
            if (state.trainerEnabled) {
                ChoiceChip(
                    label = "→${state.trainerTargetBpm}",
                    selected = false,
                    onClick = viewModel::cycleTrainerTarget
                )
                ChoiceChip(
                    label = "±${state.trainerStep}",
                    selected = false,
                    onClick = {
                        viewModel.setTrainerStep(if (state.trainerStep >= 5) 1 else state.trainerStep + 1)
                    }
                )
                ChoiceChip(
                    label = "each ${state.trainerEveryBars}",
                    selected = false,
                    onClick = {
                        val next = when (state.trainerEveryBars) {
                            2 -> 4
                            4 -> 8
                            else -> 2
                        }
                        viewModel.setTrainerEveryBars(next)
                    }
                )
                ChoiceChip(
                    label = if (state.trainerAutoStop) "stop" else "hold",
                    selected = state.trainerAutoStop,
                    onClick = viewModel::toggleTrainerAutoStop
                )
            }
        }
    }
}

@Composable
private fun SongsPanelBody(state: MetronomeUiState, viewModel: MetronomeViewModel) {
    var renaming by remember { mutableStateOf<SongPreset?>(null) }
    var renameText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (state.activeSongId != null) {
            ChoiceChip(
                label = "Update active",
                selected = false,
                onClick = viewModel::updateActiveSong
            )
        }
        if (state.songs.isEmpty()) {
            Text(
                "Bookmark tempo, meter, swing, and practice settings. Long-press to rename.",
                style = MaterialTheme.typography.bodyMedium,
                color = Ash
            )
        } else {
            state.songs.forEach { song ->
                SongRow(
                    song = song,
                    selected = state.activeSongId == song.id,
                    onLoad = { viewModel.loadSong(song) },
                    onDelete = { viewModel.deleteSong(song) },
                    onRename = {
                        renaming = song
                        renameText = song.name
                    }
                )
            }
        }
    }

    renaming?.let { song ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renaming = null },
            title = {
                Text("Rename song", color = Bone)
            },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text("Name") }
                )
            },
            confirmButton = {
                Text(
                    "SAVE",
                    color = EmberSoft,
                    modifier = Modifier
                        .clickable {
                            viewModel.renameSong(song, renameText)
                            renaming = null
                        }
                        .padding(8.dp)
                )
            },
            dismissButton = {
                Text(
                    "CANCEL",
                    color = Ash,
                    modifier = Modifier
                        .clickable { renaming = null }
                        .padding(8.dp)
                )
            },
            containerColor = InkElevated
        )
    }
}

@Composable
private fun SongRow(
    song: SongPreset,
    selected: Boolean,
    onLoad: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Ember.copy(alpha = 0.14f) else Color.Transparent)
            .border(1.dp, if (selected) Ember.copy(alpha = 0.55f) else InkLine, RoundedCornerShape(12.dp))
            .pointerInput(song.id) {
                detectTapGestures(
                    onTap = { onLoad() },
                    onLongPress = { onRename() }
                )
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                song.name,
                style = MaterialTheme.typography.titleLarge,
                color = Bone,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                buildString {
                    append("${song.bpm} · ${song.timeSignature.label} · ${song.subdivision.label}")
                    if (song.swing != SwingFeel.OFF) append(" · ${song.swing.label}")
                    if (song.groupTempo) append(" · dotted")
                    if (song.mutePattern.silentBars > 0) append(" · mute ${song.mutePattern.label}")
                    if (song.countInBars > 0) append(" · in ${song.countInBars}")
                },
                style = MaterialTheme.typography.labelMedium,
                color = Ash,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Close, contentDescription = "Delete", tint = Ash)
        }
    }
}

@Composable
internal fun LabeledChipRow(label: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = Ash,
            modifier = Modifier.padding(bottom = 8.dp, start = 2.dp)
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            content()
        }
    }
}

@Composable
internal fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (selected) Ember.copy(alpha = 0.18f) else InkElevated)
            .border(
                width = 1.dp,
                color = if (selected) Ember.copy(alpha = 0.7f) else InkLine,
                shape = shape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) EmberSoft else Mist
        )
    }
}

@Composable
private fun TransportRow(
    state: MetronomeUiState,
    viewModel: MetronomeViewModel,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val chipWidth = if (compact) 56.dp else 64.dp
    val playSize = if (compact) 64.dp else 84.dp
    val playIcon = if (compact) 32.dp else 40.dp

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TransportChip(
            label = "TAP",
            icon = { Icon(Icons.Rounded.TouchApp, contentDescription = null, tint = Copper) },
            onClick = viewModel::tapTempo,
            modifier = Modifier.weight(1f)
        )

        TransportChip(
            label = "−5",
            onClick = { viewModel.nudgeBpm(-5) },
            onLongPress = { viewModel.setBpm(60) },
            modifier = Modifier.width(chipWidth)
        )

        val playScale = remember { Animatable(1f) }
        LaunchedEffect(state.isPlaying) {
            playScale.animateTo(0.92f, tween(80))
            playScale.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 400f))
        }

        Box(
            modifier = Modifier
                .size(playSize)
                .scale(playScale.value)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(EmberSoft, Ember, EmberDeep)
                    )
                )
                .clickable(onClick = viewModel::togglePlay),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (state.isPlaying) "Stop" else "Start",
                tint = Ink,
                modifier = Modifier.size(playIcon)
            )
        }

        TransportChip(
            label = "+5",
            onClick = { viewModel.nudgeBpm(5) },
            onLongPress = { viewModel.setBpm(120) },
            modifier = Modifier.width(chipWidth)
        )
    }
}

@Composable
private fun TransportChip(
    label: String,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null
) {
    Box(
        modifier = modifier
            .height(64.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(InkElevated)
            .border(1.dp, InkLine, RoundedCornerShape(18.dp))
            .pointerInput(onClick, onLongPress) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { onLongPress?.invoke() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                icon()
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(label, style = MaterialTheme.typography.titleLarge, color = Bone)
        }
    }
}

@Composable
internal fun VolumeRow(
    state: MetronomeUiState,
    viewModel: MetronomeViewModel,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.VolumeUp,
            contentDescription = null,
            tint = Ash,
            modifier = Modifier.size(18.dp)
        )
        Slider(
            value = state.volume,
            onValueChange = viewModel::setVolume,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
            colors = SliderDefaults.colors(
                thumbColor = Ember,
                activeTrackColor = Ember,
                inactiveTrackColor = InkLine
            )
        )
        Text(
            text = "${(state.volume * 100).roundToInt()}%",
            style = MaterialTheme.typography.labelMedium,
            color = Ash,
            modifier = Modifier.width(40.dp),
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun RoundIconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    var pressed by remember { mutableStateOf(false) }

    LaunchedEffect(pressed) {
        if (!pressed) return@LaunchedEffect
        onClick()
        delay(380)
        while (pressed) {
            onClick()
            delay(70)
        }
    }

    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(InkElevated)
            .border(1.dp, InkLine, CircleShape)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        try {
                            awaitRelease()
                        } finally {
                            pressed = false
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

private fun secondaryLabel(state: MetronomeUiState): String {
    // tapHint already shows under the BPM — don't repeat it here
    if (state.trainerEnabled && state.isPlaying) {
        val sign = if (state.trainerTargetBpm < state.bpm) "−" else "+"
        return "TRAIN ${state.trainerStartBpm}→${state.trainerTargetBpm} · $sign${state.trainerStep}/${state.trainerEveryBars}"
    }
    val parts = mutableListOf(tempoLabel(state.bpm))
    if (state.groupTempo) parts += "dotted"
    if (state.swing != SwingFeel.OFF) parts += "swing ${state.swing.label.lowercase()}"
    return parts.joinToString(" · ")
}

@Composable
private fun phaseColor(phase: SessionPhase): Color = when (phase) {
    SessionPhase.IDLE -> Ash
    SessionPhase.COUNT_IN -> Copper
    SessionPhase.PLAYING -> EmberSoft
    SessionPhase.SILENT -> Mist
    SessionPhase.TRAINER_DONE -> PulseAccent
}

private fun tempoLabel(bpm: Int): String = when (bpm) {
    in MetronomeLimits.MIN_BPM until 60 -> "LARGO"
    in 60 until 76 -> "ADAGIO"
    in 76 until 108 -> "ANDANTE"
    in 108 until 120 -> "MODERATO"
    in 120 until 168 -> "ALLEGRO"
    in 168 until 200 -> "PRESTO"
    else -> "PRESTISSIMO"
}
