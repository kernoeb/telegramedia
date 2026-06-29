package app.telegramedia.player

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.view.WindowManager
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ClosedCaptionOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ScreenLockRotation
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.telegramedia.data.SettingsStore
import app.telegramedia.telegram.FileStreamState
import app.telegramedia.telegram.TelegramService
import kotlinx.coroutines.launch
import app.telegramedia.ui.theme.Ink
import app.telegramedia.ui.theme.TextSecondary
import app.telegramedia.ui.theme.Violet
import app.telegramedia.util.formatHms
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import kotlin.math.roundToInt

private val SPEEDS = listOf(0.5f, 1f, 1.25f, 1.5f, 2f)

@Composable
fun PlayerScreen(
    streamId: Int,
    title: String,
    resumeKey: Long,
    sizeBytes: Long,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val telegram: TelegramService = koinInject()
    val settings: SettingsStore = koinInject()
    val engine = remember { MpvMediaEngine(context) }
    val state by engine.state.collectAsStateWithLifecycle()
    val subtitleScale by settings.subtitleScale.collectAsStateWithLifecycle(initialValue = SettingsStore.DEFAULT_SUBTITLE_SCALE)
    val savedBrightness by settings.playerBrightness.collectAsStateWithLifecycle(initialValue = -1f)

    var stream by remember { mutableStateOf<FileStreamState>(FileStreamState.Downloading(0, 0)) }
    val resolvedPath = (stream as? FileStreamState.Ready)?.localPath

    var opened by remember { mutableStateOf(false) }
    var surfaceReady by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var startPos by remember { mutableStateOf(-1L) } // -1 until the saved position is loaded
    var openRetries by remember { mutableStateOf(0) }
    var giveUp by remember { mutableStateOf(false) }
    var everPlayed by remember { mutableStateOf(false) }

    LaunchedEffect(resumeKey) { startPos = settings.loadPosition(resumeKey) }
    var locked by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Immersive fullscreen + free rotation while in the player; restore on exit.
    val activity = context as? Activity
    DisposableEffect(activity) {
        val window = activity?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            controller?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    LaunchedEffect(locked) {
        activity?.requestedOrientation =
            if (locked) ActivityInfo.SCREEN_ORIENTATION_LOCKED
            else ActivityInfo.SCREEN_ORIENTATION_SENSOR
    }

    LaunchedEffect(streamId) {
        telegram.streamFile(streamId, sizeBytes).collect { stream = it }
    }
    LaunchedEffect(subtitleScale, opened) {
        if (opened) engine.setSubtitleScale(subtitleScale)
    }
    // Open only once both the surface is attached and the source path is resolved,
    // so loadfile is never issued before mpv has a render surface.
    LaunchedEffect(resolvedPath, surfaceReady, startPos) {
        if (resolvedPath != null && surfaceReady && startPos >= 0 && !opened) {
            opened = true
            engine.open(resolvedPath, startPos)
        }
    }

    // Recover from a failed/aborted open (transient stream-server hiccup) instead
    // of spinning forever; give up with an error after a few tries. `ended` with a
    // near-zero position means the load aborted before playing (not a normal end).
    LaunchedEffect(state.ended) {
        // Only a genuine failed OPEN (never started playing) warrants a retry.
        // `ended` also fires transiently via eof-reached while streaming catches up
        // to the download edge — that must NOT trigger this.
        val path = resolvedPath
        if (state.ended && opened && !everPlayed && path != null && state.positionMs < 2000 && !giveUp) {
            if (openRetries < 3) {
                openRetries++
                delay(400)
                engine.open(path, startPos.coerceAtLeast(0))
            } else {
                giveUp = true
            }
        }
    }
    LaunchedEffect(state.isPlaying) {
        if (state.isPlaying) { everPlayed = true; openRetries = 0; giveUp = false }
    }

    // Periodically persist the watch position so the next open resumes here.
    LaunchedEffect(opened) {
        if (!opened) return@LaunchedEffect
        var lastSaved = -1L
        while (true) {
            delay(3000)
            val s = engine.state.value
            val pos = s.positionMs
            val dur = s.durationMs
            when {
                dur > 0 && pos >= dur - 5000 -> // finished → restart next time
                    if (lastSaved != 0L) { settings.savePosition(resumeKey, 0); lastSaved = 0L }
                s.isPlaying && pos > 3000 && pos != lastSaved -> {
                    settings.savePosition(resumeKey, pos); lastSaved = pos
                }
            }
        }
    }

    // Auto-hide controls while playing.
    LaunchedEffect(controlsVisible, state.isPlaying) {
        if (controlsVisible && state.isPlaying) {
            delay(3500)
            controlsVisible = false
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // Persist the final position on exit. The periodic loop only saves while
            // playing, so backing out while paused would otherwise lose progress.
            val s = engine.state.value
            val pos = s.positionMs
            val dur = s.durationMs
            if (pos > 3000 && (dur <= 0 || pos < dur - 5000)) {
                settings.savePositionAsync(resumeKey, pos)
            }
            engine.release()
            telegram.trimCache() // bound the on-disk download cache after watching
        }
    }

    // VLC-style gestures: left half = brightness, right half = volume.
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    var volumeLevel by remember { mutableFloatStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()) }
    var brightness by remember {
        mutableFloatStateOf((activity?.window?.attributes?.screenBrightness ?: -1f).let { if (it in 0f..1f) it else 0.5f })
    }
    var hud by remember { mutableStateOf<PlayerHud?>(null) }
    var seekHud by remember { mutableStateOf<Int?>(null) }
    var seekTapId by remember { mutableStateOf(0) } // bumped per tap to restart the reset timer
    // Accumulates the target across rapid double-taps (mpv's reported position
    // lags, so re-reading it each tap would stall the seek).
    var pendingSeekMs by remember { mutableStateOf<Long?>(null) }

    // Apply the saved brightness when it loads.
    LaunchedEffect(savedBrightness) {
        if (savedBrightness in 0f..1f) {
            brightness = savedBrightness
            activity?.window?.let { it.attributes = it.attributes.apply { screenBrightness = savedBrightness } }
        }
    }
    LaunchedEffect(seekTapId) {
        if (seekTapId > 0) {
            delay(700) // restarts on each tap; hides the HUD after taps stop
            seekHud = null
        }
    }
    // Clear the accumulated seek target only once playback has actually LANDED near
    // it. mpv's reported position lags a backward seek (re-buffering), so clearing on
    // a timer made the next tap read a stale, higher position — rewinds could never
    // reach 0. Holding the target until it converges lets taps keep marching down.
    LaunchedEffect(pendingSeekMs, state.positionMs) {
        val target = pendingSeekMs ?: return@LaunchedEffect
        if (kotlin.math.abs(state.positionMs - target) < 1500) pendingSeekMs = null
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { controlsVisible = !controlsVisible },
                    onDoubleTap = { offset ->
                        val left = offset.x < size.width / 2f
                        val base = pendingSeekMs ?: engine.state.value.positionMs
                        val duration = engine.state.value.durationMs
                        var target = base + if (left) -5000 else 5000
                        target = target.coerceAtLeast(0)
                        if (duration > 0) target = target.coerceAtMost(duration)
                        pendingSeekMs = target
                        engine.seekTo(target)
                        seekHud = if (left) -5 else 5
                        seekTapId++
                    },
                )
            }
            .pointerInput(Unit) {
                var leftSide = false
                detectVerticalDragGestures(
                    onDragStart = { offset -> leftSide = offset.x < size.width / 2f },
                    onDragEnd = {
                        hud = null
                        if (leftSide) scope.launch { settings.setPlayerBrightness(brightness) }
                    },
                    onDragCancel = { hud = null },
                ) { change, dragAmount ->
                    change.consume()
                    val frac = -dragAmount / (size.height * 0.8f)
                    if (leftSide) {
                        brightness = (brightness + frac).coerceIn(0.01f, 1f)
                        activity?.window?.let { w ->
                            w.attributes = w.attributes.apply { screenBrightness = brightness }
                        }
                        hud = PlayerHud(isVolume = false, fraction = brightness)
                    } else {
                        volumeLevel = (volumeLevel + frac * maxVolume).coerceIn(0f, maxVolume.toFloat())
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volumeLevel.roundToInt(), 0)
                        hud = PlayerHud(isVolume = true, fraction = if (maxVolume > 0) volumeLevel / maxVolume else 0f)
                    }
                }
            },
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {}
                        override fun surfaceChanged(holder: SurfaceHolder, f: Int, w: Int, h: Int) {
                            engine.attachSurface(holder.surface, w, h)
                            surfaceReady = true
                        }
                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            engine.detachSurface()
                        }
                    })
                }
            },
        )

        when {
            giveUp -> PreparingOverlay(title, "Couldn't play this file. Go back and try again.", null, onBack)
            stream is FileStreamState.Failed ->
                PreparingOverlay(title, "Couldn't load: ${(stream as FileStreamState.Failed).message}", null, onBack)
            resolvedPath == null -> {
                val s = stream as? FileStreamState.Downloading
                PreparingOverlay(title, "Preparing…", s?.progress?.takeIf { s.totalBytes > 0 }, onBack)
            }
            state.isBuffering -> CircularProgressIndicator(color = Violet, modifier = Modifier.align(Alignment.Center))
        }

        AnimatedVisibility(
            visible = controlsVisible && resolvedPath != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Controls(
                title = title,
                state = state,
                locked = locked,
                onToggleLock = { locked = !locked },
                onBack = onBack,
                onTogglePlay = { engine.togglePlayPause() },
                onSeek = { engine.seekTo(it) },
                onSelectAudio = { engine.selectTrack(TrackType.AUDIO, it) },
                onSelectSubtitle = { engine.selectTrack(TrackType.SUBTITLE, it) },
                onSubtitleScale = { newScale ->
                    engine.setSubtitleScale(newScale)
                    scope.launch { settings.setSubtitleScale(newScale) }
                },
                subtitleScale = subtitleScale,
                onCycleSpeed = {
                    val i = SPEEDS.indices.minByOrNull { kotlin.math.abs(SPEEDS[it] - state.speed) } ?: 0
                    engine.setSpeed(SPEEDS[(i + 1) % SPEEDS.size])
                },
            )
        }

        hud?.let { h ->
            Row(
                Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (h.isVolume) Icons.AutoMirrored.Filled.VolumeUp else Icons.Filled.BrightnessHigh,
                    contentDescription = null,
                    tint = Color.White,
                )
                Spacer(Modifier.width(10.dp))
                Text("${(h.fraction * 100).toInt()}%", color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
        }

        seekHud?.let { delta ->
            Box(
                Modifier
                    .align(if (delta < 0) Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(horizontal = 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    if (delta < 0) "« ${-delta}s" else "${delta}s »",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

private data class PlayerHud(val isVolume: Boolean, val fraction: Float)

@Composable
private fun androidx.compose.foundation.layout.BoxScope.PreparingOverlay(
    title: String,
    message: String,
    progress: Float?,
    onBack: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart).systemBarsPadding().padding(8.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
        }
        Column(
            Modifier.align(Alignment.Center).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (progress != null) {
                CircularProgressIndicator(progress = { progress }, color = Violet)
            } else {
                CircularProgressIndicator(color = Violet)
            }
            Spacer(Modifier.size(16.dp))
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.size(4.dp))
            Text(
                if (progress != null) "$message ${(progress * 100).toInt()}%" else message,
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Controls(
    title: String,
    state: PlaybackState,
    locked: Boolean,
    onToggleLock: () -> Unit,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onSelectAudio: (Int?) -> Unit,
    onSelectSubtitle: (Int?) -> Unit,
    onSubtitleScale: (Float) -> Unit,
    subtitleScale: Float,
    onCycleSpeed: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f)),
    ) {
        // Top bar
        Row(
            Modifier
                .fillMaxWidth()
                .systemBarsPadding()
                .displayCutoutPadding()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text(
                title,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp).weight(1f),
            )
            IconButton(onClick = onToggleLock) {
                Icon(
                    if (locked) Icons.Filled.ScreenLockRotation else Icons.Outlined.ScreenRotation,
                    contentDescription = if (locked) "Unlock rotation" else "Lock rotation",
                    tint = if (locked) Violet else Color.White,
                )
            }
        }

        // Center play/pause — hidden while buffering (the spinner occupies the centre).
        if (!state.isBuffering) {
            IconButton(
                onClick = onTogglePlay,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(72.dp)
                    .background(Color.Black.copy(alpha = 0.4f), CircleShape),
            ) {
                Icon(
                    if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = "Play/pause",
                    tint = Color.White,
                    modifier = Modifier.size(40.dp),
                )
            }
        }

        // Bottom controls
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .systemBarsPadding()
                .displayCutoutPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatHms(state.positionMs), color = Color.White, style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = state.positionMs.toFloat(),
                    onValueChange = { onSeek(it.toLong()) },
                    valueRange = 0f..(state.durationMs.coerceAtLeast(1)).toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = Violet,
                        activeTrackColor = Violet,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                    ),
                    thumb = {
                        Box(
                            Modifier
                                .size(13.dp)
                                .clip(CircleShape)
                                .background(Violet),
                        )
                    },
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                )
                Text(formatHms(state.durationMs), color = Color.White, style = MaterialTheme.typography.labelMedium)
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Speed
                IconButton(onClick = onCycleSpeed) {
                    Icon(Icons.Filled.Speed, contentDescription = "Speed", tint = Color.White)
                }
                Text("${state.speed}x", color = Color.White, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(8.dp))
                // Audio track picker
                TrackMenu(
                    icon = { Icon(Icons.Outlined.Audiotrack, contentDescription = "Audio", tint = Color.White) },
                    tracks = state.audioTracks,
                    selectedId = state.selectedAudioId,
                    allowOff = false,
                    onSelect = onSelectAudio,
                )
                // Subtitle track picker
                TrackMenu(
                    icon = {
                        Icon(
                            if (state.selectedSubtitleId != null) Icons.Filled.ClosedCaption else Icons.Filled.ClosedCaptionOff,
                            contentDescription = "Subtitles",
                            tint = Color.White,
                        )
                    },
                    tracks = state.subtitleTracks,
                    selectedId = state.selectedSubtitleId,
                    allowOff = true,
                    onSelect = onSelectSubtitle,
                    subtitleScale = subtitleScale,
                    onSubtitleScale = onSubtitleScale,
                )
            }
        }
    }
}

@Composable
private fun TrackMenu(
    icon: @Composable () -> Unit,
    tracks: List<MediaTrack>,
    selectedId: Int?,
    allowOff: Boolean,
    onSelect: (Int?) -> Unit,
    subtitleScale: Float? = null,
    onSubtitleScale: ((Float) -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) { icon() }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (subtitleScale != null && onSubtitleScale != null) {
                Row(
                    modifier = Modifier.width(240.dp).padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Subtitle size", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    IconButton(
                        onClick = { onSubtitleScale((subtitleScale - 0.1f).coerceAtLeast(0.4f)) },
                        modifier = Modifier.size(32.dp),
                    ) { Icon(Icons.Filled.Remove, contentDescription = "Smaller subtitles") }
                    Text(
                        "${(subtitleScale * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(44.dp),
                    )
                    IconButton(
                        onClick = { onSubtitleScale((subtitleScale + 0.1f).coerceAtMost(2.0f)) },
                        modifier = Modifier.size(32.dp),
                    ) { Icon(Icons.Filled.Add, contentDescription = "Larger subtitles") }
                }
                HorizontalDivider()
            }
            if (allowOff) {
                DropdownMenuItem(
                    text = { Text(if (selectedId == null) "✓ Off" else "Off") },
                    onClick = { onSelect(null); expanded = false },
                )
            }
            tracks.forEach { track ->
                DropdownMenuItem(
                    text = { Text(if (track.id == selectedId) "✓ ${track.label}" else track.label) },
                    onClick = { onSelect(track.id); expanded = false },
                )
            }
            if (tracks.isEmpty() && !allowOff) {
                DropdownMenuItem(text = { Text("No tracks") }, onClick = { expanded = false }, enabled = false)
            }
        }
    }
}

