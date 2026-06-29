package app.telegramedia.telegram

enum class MediaKind { VIDEO, AUDIO }

/** A playable media file found in a chat (a video or audio message). */
data class MediaItem(
    val messageId: Long,
    /** TDLib file id used to stream/download (0 in demo mode). */
    val streamId: Int,
    val title: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val kind: MediaKind,
    val mimeType: String?,
    /** TDLib file id of a thumbnail/cover, or null. */
    val thumbnailFileId: Int? = null,
)

/** Progress of preparing a file for playback (TDLib progressive download). */
sealed interface FileStreamState {
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : FileStreamState {
        val progress: Float
            get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    }

    /** The file is playable at [localPath] (fully downloaded for now; true streaming comes later). */
    data class Ready(val localPath: String) : FileStreamState

    data class Failed(val message: String) : FileStreamState
}
