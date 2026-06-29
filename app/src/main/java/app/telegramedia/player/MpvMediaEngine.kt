package app.telegramedia.player

import android.content.Context
import android.view.Surface
import dev.jdtech.mpv.MPVLib
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * [MediaEngine] backed by libmpv via [MPVLib]. Renders to an Android [Surface]
 * with `--vo=gpu`; reports playback/track state through property observation.
 */
class MpvMediaEngine(context: Context) : MediaEngine, MPVLib.EventObserver {

    private val mpv = requireNotNull(MPVLib.create(context)) { "MPVLib.create returned null" }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    init {
        mpv.setOptionString("config", "no")
        mpv.setOptionString("idle", "yes")            // keep core alive with no file
        mpv.setOptionString("force-window", "no")
        mpv.setOptionString("vo", "gpu")
        mpv.setOptionString("gpu-context", "android")
        mpv.setOptionString("opengl-es", "yes")
        mpv.setOptionString("hwdec", "auto-safe")     // mediacodec where available, sw fallback
        mpv.setOptionString("ao", "audiotrack")
        mpv.setOptionString("save-position-on-quit", "no")
        // Subtitles: size relative to the VIDEO, not the window, so it stays
        // consistent between portrait and landscape (window-relative made it
        // oversized in portrait). Render ASS/PGS/SRT.
        mpv.setOptionString("sub-scale-with-window", "no")
        mpv.setOptionString("sub-ass-override", "scale")
        mpv.setOptionString("sub-scale", "0.8") // default smaller; overridden from settings
        // Let libass use Android's real fonts (Roboto has a true italic face) instead
        // of faking italics by shearing the fallback font.
        mpv.setOptionString("sub-fonts-dir", "/system/fonts")
        mpv.setOptionString("sub-font", "Roboto")

        mpv.init()

        mpv.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        mpv.observeProperty("paused-for-cache", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        mpv.observeProperty("eof-reached", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        mpv.observeProperty("speed", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("track-list/count", MPVLib.MpvFormat.MPV_FORMAT_INT64)
        mpv.addObserver(this)
    }

    // --- MediaEngine -----------------------------------------------------

    override fun attachSurface(surface: Surface, width: Int, height: Int) {
        mpv.attachSurface(surface)
        mpv.setPropertyString("android-surface-size", "${width}x$height")
        // Re-enable the video output: on resume the surface is recreated, and
        // without this the vo stays torn down (black video, audio only).
        mpv.setPropertyString("vo", "gpu")
    }

    override fun detachSurface() {
        // Release the vo before the surface goes away, so it can be cleanly
        // re-created when the surface comes back.
        mpv.setPropertyString("vo", "null")
        mpv.detachSurface()
    }

    override fun open(uri: String, startPositionMs: Long) {
        _state.update { it.copy(isBuffering = true, ended = false, error = null) }
        // Resume position: set the load-time "start" option before loadfile.
        // (Passing it as a loadfile arg collides with the `index` positional.)
        mpv.setOptionString("start", if (startPositionMs > 0) "${startPositionMs / 1000.0}" else "none")
        mpv.command(arrayOf("loadfile", uri))
    }

    override fun play() = mpv.setPropertyBoolean("pause", false)
    override fun pause() = mpv.setPropertyBoolean("pause", true)
    override fun togglePlayPause() = mpv.setPropertyBoolean("pause", _state.value.isPlaying)

    override fun seekTo(positionMs: Long) {
        mpv.command(arrayOf("seek", (positionMs / 1000.0).toString(), "absolute"))
    }

    override fun selectTrack(type: TrackType, id: Int?) {
        val prop = when (type) {
            TrackType.AUDIO -> "aid"
            TrackType.SUBTITLE -> "sid"
            TrackType.VIDEO -> "vid"
        }
        mpv.setPropertyString(prop, id?.toString() ?: "no")
    }

    override fun addExternalSubtitle(uri: String, select: Boolean) {
        mpv.command(arrayOf("sub-add", uri, if (select) "select" else "auto"))
    }

    override fun setSpeed(speed: Float) = mpv.setPropertyDouble("speed", speed.toDouble())

    override fun setSubtitleScale(scale: Float) = mpv.setPropertyDouble("sub-scale", scale.toDouble())

    override fun release() {
        runCatching { mpv.removeObserver(this) }
        scope.cancel()
        // Destroy synchronously: a backgrounded destroy can still be tearing down
        // native state when the next PlayerScreen creates a new mpv instance,
        // leaving the second player dead (black, no playback).
        runCatching { mpv.setPropertyString("vo", "null") }
        runCatching { mpv.detachSurface() }
        runCatching { mpv.destroy() }
    }

    // --- MPVLib.EventObserver -------------------------------------------

    override fun eventProperty(property: String) {}

    override fun eventProperty(property: String, value: Long) {
        if (property == "track-list/count") reloadTracks()
    }

    override fun eventProperty(property: String, value: Double) {
        when (property) {
            "time-pos" -> _state.update { it.copy(positionMs = (value * 1000).toLong()) }
            "duration" -> _state.update { it.copy(durationMs = (value * 1000).toLong()) }
            "speed" -> _state.update { it.copy(speed = value.toFloat()) }
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        when (property) {
            "pause" -> _state.update { it.copy(isPlaying = !value) }
            "paused-for-cache" -> _state.update { it.copy(isBuffering = value) }
            "eof-reached" -> if (value) _state.update { it.copy(ended = true, isPlaying = false) }
        }
    }

    override fun eventProperty(property: String, value: String) {}

    override fun event(eventId: Int) {
        when (eventId) {
            MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
                _state.update { it.copy(isBuffering = false) }
                reloadTracks()
            }
            MPVLib.MpvEvent.MPV_EVENT_END_FILE ->
                _state.update { it.copy(ended = true, isPlaying = false) }
        }
    }

    /** Read the current track-list and selection off the mpv core. */
    private fun reloadTracks() = scope.launch {
        val count = mpv.getPropertyInt("track-list/count") ?: 0
        val tracks = ArrayList<MediaTrack>(count)
        var selAudio: Int? = null
        var selSub: Int? = null
        for (i in 0 until count) {
            val typeStr = mpv.getPropertyString("track-list/$i/type") ?: continue
            val type = when (typeStr) {
                "audio" -> TrackType.AUDIO
                "sub" -> TrackType.SUBTITLE
                "video" -> TrackType.VIDEO
                else -> continue
            }
            val id = mpv.getPropertyInt("track-list/$i/id") ?: continue
            val selected = mpv.getPropertyBoolean("track-list/$i/selected") ?: false
            tracks += MediaTrack(
                id = id,
                type = type,
                title = mpv.getPropertyString("track-list/$i/title"),
                lang = mpv.getPropertyString("track-list/$i/lang"),
                codec = mpv.getPropertyString("track-list/$i/codec"),
            )
            if (selected && type == TrackType.AUDIO) selAudio = id
            if (selected && type == TrackType.SUBTITLE) selSub = id
        }
        _state.update {
            it.copy(tracks = tracks, selectedAudioId = selAudio, selectedSubtitleId = selSub)
        }
    }
}
