package app.telegramedia.player

import android.view.Surface
import kotlinx.coroutines.flow.StateFlow

enum class TrackType { VIDEO, AUDIO, SUBTITLE }

/** A selectable audio/subtitle/video track reported by the engine. */
data class MediaTrack(
    val id: Int,
    val type: TrackType,
    val title: String?,
    val lang: String?,
    val codec: String?,
) {
    /** Human-friendly label for a track-picker row. */
    val label: String
        get() = buildString {
            append(title ?: lang?.uppercase() ?: "Track $id")
            if (title != null && lang != null) append(" · ${lang.uppercase()}")
            if (codec != null) append("  ($codec)")
        }
}

data class PlaybackState(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = true,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val tracks: List<MediaTrack> = emptyList(),
    val selectedAudioId: Int? = null,
    /** null = subtitles off. */
    val selectedSubtitleId: Int? = null,
    val ended: Boolean = false,
    val error: String? = null,
) {
    val audioTracks get() = tracks.filter { it.type == TrackType.AUDIO }
    val subtitleTracks get() = tracks.filter { it.type == TrackType.SUBTITLE }
}

/**
 * Playback engine abstraction. The app talks only to this; the concrete
 * implementation is [MpvMediaEngine] (libmpv — PGS/SRT/ASS, every codec).
 * Keeping this seam means a libVLC backend could be swapped in without
 * touching the UI.
 */
interface MediaEngine {

    val state: StateFlow<PlaybackState>

    fun attachSurface(surface: Surface, width: Int, height: Int)
    fun detachSurface()

    /** Load and start playing [uri] (local path, file://, or http(s)). */
    fun open(uri: String, startPositionMs: Long = 0)

    fun play()
    fun pause()
    fun togglePlayPause()
    fun seekTo(positionMs: Long)

    /** Select an audio/subtitle track by engine id, or null to disable (subtitles). */
    fun selectTrack(type: TrackType, id: Int?)

    /** Sideload an external subtitle file (e.g. a `.srt` or `.sup` for PGS). */
    fun addExternalSubtitle(uri: String, select: Boolean = true)

    fun setSpeed(speed: Float)

    /** Subtitle size multiplier (1.0 = mpv default). */
    fun setSubtitleScale(scale: Float)

    fun release()
}
