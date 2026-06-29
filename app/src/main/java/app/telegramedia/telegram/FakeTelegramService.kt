package app.telegramedia.telegram

import android.content.Context
import app.telegramedia.stream.HttpStreamServer
import app.telegramedia.stream.StaticFileStreamSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import java.io.File

/**
 * In-memory stand-in for TDLib used during early development.
 *
 * Login script:
 *  - any phone number -> code step
 *  - code "12345" -> Ready, code "00000" -> WaitPassword, anything else -> Error
 *  - password "hunter2" -> Ready
 *  - requestQrLogin() -> WaitQrCode with a fake tg:// link
 */
class FakeTelegramService(private val context: Context) : TelegramService {

    private val _authState = MutableStateFlow<AuthState>(AuthState.Initializing)
    override val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _chats = MutableStateFlow<List<TgChat>>(emptyList())
    override val chats: StateFlow<List<TgChat>> = _chats.asStateFlow()

    private val fileServer = HttpStreamServer()

    override fun start() {
        if (_authState.value is AuthState.Initializing) {
            _authState.value = AuthState.WaitPhoneNumber
        }
    }

    override suspend fun setPhoneNumber(phoneNumber: String) {
        _authState.value = AuthState.Initializing
        delay(600)
        _authState.value = AuthState.WaitCode(phoneNumber)
    }

    override suspend fun checkCode(code: String) {
        _authState.value = AuthState.Initializing
        delay(600)
        _authState.value = when (code.trim()) {
            "12345" -> AuthState.Ready
            "00000" -> AuthState.WaitPassword(hint = "your pet's name")
            else -> AuthState.Error("Invalid code. Try 12345.")
        }
    }

    override suspend fun checkPassword(password: String) {
        _authState.value = AuthState.Initializing
        delay(600)
        _authState.value =
            if (password == "hunter2") AuthState.Ready
            else AuthState.Error("Wrong password. Try hunter2.")
    }

    override suspend fun requestQrLogin() {
        delay(300)
        _authState.value = AuthState.WaitQrCode("tg://login?token=FAKE-DEMO-TOKEN-0123456789")
    }

    override suspend fun logOut() {
        _chats.value = emptyList()
        _authState.value = AuthState.LoggedOut
        delay(300)
        _authState.value = AuthState.WaitPhoneNumber
    }

    override suspend fun loadChats() {
        if (_chats.value.isNotEmpty()) return
        delay(500)
        _chats.value = sampleChats
    }

    override suspend fun loadChatMedia(chatId: Long): List<MediaItem> {
        delay(400)
        // Every demo item streams to the same bundled clip.
        return demoTitles.mapIndexed { i, t ->
            MediaItem(
                messageId = (chatId * 100 + i),
                streamId = 0,
                title = t.first,
                durationMs = t.second,
                sizeBytes = 1_013_566,
                kind = MediaKind.VIDEO,
                mimeType = "video/x-matroska",
                thumbnailFileId = 1,
            )
        }
    }

    override fun streamFile(streamId: Int, sizeBytes: Long): Flow<FileStreamState> = flow {
        // Serve the bundled clip through the same HTTP server real files use.
        val url = fileServer.register("demo", StaticFileStreamSource(copyAsset("demo.mkv")))
        emit(FileStreamState.Ready(url))
    }

    override suspend fun smallFilePath(fileId: Int): String? = copyAsset("demo_thumb.jpg")

    override fun trimCache() { /* no-op in demo mode */ }

    private fun copyAsset(name: String): String {
        val out = File(context.filesDir, name)
        if (!out.exists()) {
            context.assets.open(name).use { input -> out.outputStream().use { input.copyTo(it) } }
        }
        return out.absolutePath
    }
}

private val demoTitles = listOf(
    "Dune: Part Two (2024) — 2160p" to 9_360_000L,
    "Blade Runner 2049 — Remux" to 9_840_000L,
    "Interstellar (2014) — IMAX" to 10_140_000L,
    "Planet Earth III — E04" to 3_300_000L,
)

private val sampleChats: List<TgChat> = listOf(
    TgChat(1, "Movie Vault 4K", ChatKind.CHANNEL, "Dune: Part Two (2024) added", 3, null, hasMedia = true),
    TgChat(2, "Cinephile Lounge", ChatKind.GROUP, "anyone got the Criterion rip?", 0, null, hasMedia = true),
    TgChat(3, "OST & Soundtracks", ChatKind.CHANNEL, "Blade Runner 2049 — full score", 12, null, hasMedia = true),
    TgChat(4, "Saved Messages", ChatKind.PRIVATE, "Interstellar.2014.2160p.mkv", 0, null, hasMedia = true),
    TgChat(5, "Documentary Hub", ChatKind.CHANNEL, "Planet Earth III E04", 1, null, hasMedia = true),
    TgChat(6, "Alex", ChatKind.PRIVATE, "sent you that subtitle file", 0, null, hasMedia = false),
)
