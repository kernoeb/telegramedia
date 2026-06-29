package app.telegramedia.telegram

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The single seam between the UI and Telegram. The current implementation is a
 * [FakeTelegramService] so the whole app can be built and previewed; it will be
 * replaced by a TDLib-backed implementation in the `:tdlib` module without any
 * change to callers.
 */
interface TelegramService {

    val authState: StateFlow<AuthState>

    /** Kick off the client. Idempotent. */
    fun start()

    // --- Authorization ---------------------------------------------------

    suspend fun setPhoneNumber(phoneNumber: String)

    suspend fun checkCode(code: String)

    suspend fun checkPassword(password: String)

    suspend fun logOut()

    // --- Data ------------------------------------------------------------

    val chats: StateFlow<List<TgChat>>

    /** Load (or refresh) the main chat list. */
    suspend fun loadChats()

    /** Find the playable video/audio files posted in a chat. */
    suspend fun loadChatMedia(chatId: Long): List<MediaItem>

    /** Prepare [streamId] for playback, emitting a playable URL/path (progressive).
     *  [sizeBytes] (known from the media list) lets the stream server answer
     *  instantly without querying TDLib for the size. */
    fun streamFile(streamId: Int, sizeBytes: Long): Flow<FileStreamState>

    /** Download a small file (media thumbnail or chat photo) and return its local path (null if none). */
    suspend fun smallFilePath(fileId: Int): String?

    /** Trim TDLib's on-disk file cache down to a bounded size (fire-and-forget). */
    fun trimCache()
}
