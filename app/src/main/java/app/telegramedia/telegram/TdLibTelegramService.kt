package app.telegramedia.telegram

import android.content.Context
import android.os.Build
import android.util.Log
import app.telegramedia.data.SettingsStore
import app.telegramedia.tdlib.TdlibClient
import app.telegramedia.tdlib.TdlibException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import app.telegramedia.stream.HttpStreamServer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.io.File

/**
 * Real [TelegramService] backed by TDLib. Drives the TDLib authorization state
 * machine and maps `TdApi.AuthorizationState*` onto our [AuthState].
 */
class TdLibTelegramService(
    private val context: Context,
    private val apiId: Int,
    private val apiHash: String,
    private val settings: SettingsStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : TelegramService {

    private var client = TdlibClient()
    private var collectorJob: Job? = null
    private val fileServer = HttpStreamServer()

    private val _authState = MutableStateFlow<AuthState>(AuthState.Initializing)
    override val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _chats = MutableStateFlow<List<TgChat>>(emptyList())
    override val chats: StateFlow<List<TgChat>> = _chats.asStateFlow()

    private var pendingPhone: String? = null
    private var started = false
    private var intentionalLogout = false
    private var lastRecreateAtMs = 0L
    // The initial WaitTdlibParameters is delivered by BOTH the update collector and
    // the reconcile query, so guard against sending parameters twice (TDLib rejects
    // the second as "Unexpected setTdlibParameters").
    private val parametersSent = java.util.concurrent.atomic.AtomicBoolean(false)

    init {
        TdlibClient.setLogVerbosity(1)
        subscribeUpdates()
    }

    private fun subscribeUpdates() {
        collectorJob = scope.launch {
            client.updates.collect { update ->
                if (update is TdApi.UpdateAuthorizationState) {
                    onAuthorizationState(update.authorizationState)
                }
            }
        }
    }

    override fun start() {
        if (started) return
        started = true
        reconcileAuthState()
    }

    /** Reconcile the auth state via a query, in case the initial update was emitted
     *  before the collector subscribed (replay window). */
    private fun reconcileAuthState() {
        scope.launch {
            runCatching { client.send(TdApi.GetAuthorizationState()) }
                .getOrNull()?.let { onAuthorizationState(it) }
        }
    }

    /** After LogOut, TDLib reaches Closed and the native client is dead; spin up a
     *  fresh one so the user can log back in without restarting the app. */
    private fun recreateClient() {
        val now = System.currentTimeMillis()
        if (now - lastRecreateAtMs < 5_000) {
            Log.w(TAG, "recreateClient skipped (too soon — possible loop)")
            return
        }
        lastRecreateAtMs = now
        Log.w(TAG, "recreateClient (intentionalLogout=$intentionalLogout)")
        intentionalLogout = false
        collectorJob?.cancel()
        pendingPhone = null
        parametersSent.set(false) // the fresh client negotiates parameters again
        smallFileCache.clear()
        // Drop stream sources bound to the dead client; otherwise they keep polling
        // (and pin) the old native client after a logout/relogin.
        fileServer.clearSources()
        client = TdlibClient()
        subscribeUpdates()
        reconcileAuthState()
    }

    // --- Authorization --------------------------------------------------

    private suspend fun onAuthorizationState(state: TdApi.AuthorizationState) {
        Log.i(TAG, "authState=${state.javaClass.simpleName} (intentionalLogout=$intentionalLogout)")
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters ->
                if (parametersSent.compareAndSet(false, true)) sendParameters()
            is TdApi.AuthorizationStateWaitPhoneNumber -> _authState.value = AuthState.WaitPhoneNumber
            is TdApi.AuthorizationStateWaitCode ->
                // In-memory `pendingPhone` is null after a cold start that resumes
                // directly into WaitCode; fall back to the persisted number.
                _authState.value = AuthState.WaitCode(pendingPhone ?: settings.loadPendingPhone().orEmpty())
            is TdApi.AuthorizationStateWaitPassword ->
                _authState.value = AuthState.WaitPassword(state.passwordHint.ifBlank { null })
            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation ->
                _authState.value = AuthState.WaitQrCode(state.link)
            is TdApi.AuthorizationStateReady -> {
                _authState.value = AuthState.Ready
                trimCache() // clean up any large accumulated download cache on startup
            }
            is TdApi.AuthorizationStateClosed -> {
                _authState.value = AuthState.LoggedOut
                recreateClient()
            }
            is TdApi.AuthorizationStateWaitRegistration ->
                _authState.value = AuthState.Error("This number isn't registered. Sign up in the official Telegram app first.")
            is TdApi.AuthorizationStateWaitEmailAddress,
            is TdApi.AuthorizationStateWaitEmailCode ->
                _authState.value = AuthState.Error("Email-based login isn't supported yet.")
            else -> Unit // LoggingOut, Closing — transient
        }
    }

    private suspend fun sendParameters() {
        val params = TdApi.SetTdlibParameters().apply {
            databaseDirectory = File(context.filesDir, "tdlib").absolutePath
            useMessageDatabase = true
            useSecretChats = false
            apiId = this@TdLibTelegramService.apiId
            apiHash = this@TdLibTelegramService.apiHash
            systemLanguageCode = "en"
            deviceModel = Build.MODEL ?: "Android"
            systemVersion = Build.VERSION.RELEASE ?: "Android"
            applicationVersion = "0.1.0"
        }
        runCatching { client.send(params) }
            .onFailure {
                // Allow a genuine failure to be retried when TDLib re-emits the state.
                parametersSent.set(false)
                _authState.value = AuthState.Error(it.message ?: "Failed to initialize TDLib")
            }
    }

    override suspend fun setPhoneNumber(phoneNumber: String) {
        pendingPhone = phoneNumber
        runCatching { settings.setPendingPhone(phoneNumber) }
        guarded {
            client.send(TdApi.SetAuthenticationPhoneNumber(phoneNumber, TdApi.PhoneNumberAuthenticationSettings()))
        }
    }

    override suspend fun checkCode(code: String) = guarded {
        client.send(TdApi.CheckAuthenticationCode(code))
    }

    override suspend fun checkPassword(password: String) = guarded {
        client.send(TdApi.CheckAuthenticationPassword(password))
    }

    override suspend fun requestQrLogin() = guarded {
        client.send(TdApi.RequestQrCodeAuthentication(LongArray(0)))
    }

    override suspend fun logOut() {
        intentionalLogout = true
        _chats.value = emptyList()
        runCatching { settings.clearPendingPhone() }
        guarded { client.send(TdApi.LogOut()) }
    }

    /** Runs a TDLib auth call, surfacing its error into [authState] instead of throwing. */
    private suspend inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: TdlibException) {
            _authState.value = AuthState.Error(e.message)
        } catch (e: Exception) {
            _authState.value = AuthState.Error(e.message ?: "Telegram error")
        }
    }

    // --- Data -----------------------------------------------------------

    override suspend fun loadChats() {
        // Ask TDLib to populate the in-memory main list (404 = nothing more to load).
        runCatching { client.send(TdApi.LoadChats(TdApi.ChatListMain(), 100)) }
        val chatsResult = runCatching { client.send(TdApi.GetChats(TdApi.ChatListMain(), 200)) }
            .getOrNull() ?: return
        // Fetch the chats concurrently: GetChat is a per-id round-trip and doing
        // them serially blocks on up to 200 sequential latencies before any media loads.
        val mapped = coroutineScope {
            chatsResult.chatIds.map { id ->
                async { runCatching { client.send(TdApi.GetChat(id)) }.getOrNull()?.let(::mapChat) }
            }.awaitAll().filterNotNull()
        }
        _chats.value = mapped
    }

    override suspend fun loadChatMedia(chatId: Long): List<MediaItem> {
        val items = LinkedHashMap<Long, MediaItem>()
        val filters = listOf(TdApi.SearchMessagesFilterVideo(), TdApi.SearchMessagesFilterAudio())
        for (filter in filters) {
            val found = runCatching {
                client.send(TdApi.SearchChatMessages(chatId, null, "", null, 0, 0, 100, filter))
            }.getOrNull() ?: continue
            for (message in found.messages) {
                message.toMediaItem()?.let { items[it.messageId] = it }
            }
        }
        return items.values.sortedBy { it.messageId } // channel/posting order (oldest first)
    }

    private fun TdApi.Message.toMediaItem(): MediaItem? = when (val c = content) {
        is TdApi.MessageVideo -> MediaItem(
            messageId = id,
            streamId = c.video.video.id,
            title = c.video.fileName.ifBlank { c.caption.text.ifBlank { "Video" } },
            durationMs = c.video.duration * 1000L,
            sizeBytes = c.video.video.size,
            kind = MediaKind.VIDEO,
            mimeType = c.video.mimeType,
            thumbnailFileId = c.video.thumbnail?.file?.id,
        )
        is TdApi.MessageAudio -> MediaItem(
            messageId = id,
            streamId = c.audio.audio.id,
            title = listOf(c.audio.performer, c.audio.title).filter { it.isNotBlank() }
                .joinToString(" — ").ifBlank { c.audio.fileName.ifBlank { "Audio" } },
            durationMs = c.audio.duration * 1000L,
            sizeBytes = c.audio.audio.size,
            kind = MediaKind.AUDIO,
            mimeType = c.audio.mimeType,
            thumbnailFileId = c.audio.albumCoverThumbnail?.file?.id,
        )
        else -> null
    }

    override fun streamFile(streamId: Int, sizeBytes: Long): Flow<FileStreamState> = flow {
        if (streamId == 0) {
            emit(FileStreamState.Failed("No file"))
            return@flow
        }
        // Progressive: serve the file over localhost and let mpv stream/seek it.
        // Use a random route token (not the raw file id, which is small and sequential)
        // so another app on the device can't guess the URL and read what's streaming.
        val key = java.util.UUID.randomUUID().toString()
        val url = fileServer.register(key, TdlibStreamSource(client, streamId, sizeBytes))
        emit(FileStreamState.Ready(url))
    }

    private val smallFileCache = java.util.concurrent.ConcurrentHashMap<Int, String>()

    override suspend fun smallFilePath(fileId: Int): String? {
        if (fileId == 0) return null
        smallFileCache[fileId]?.let { return it }
        val file = runCatching {
            client.send(TdApi.DownloadFile(fileId, 1, 0, 0, true)) // synchronous: small file
        }.getOrNull() ?: return null
        return file.local.path.ifEmpty { null }?.also { smallFileCache[fileId] = it }
    }

    override fun trimCache() {
        scope.launch {
            runCatching {
                client.send(
                    TdApi.OptimizeStorage(
                        /* size */ CACHE_CAP_BYTES,
                        /* ttl */ -1,
                        /* count */ -1,
                        /* immunityDelay */ IMMUNITY_DELAY_SECS, // protect just-watched files so the active movie isn't trimmed mid-session
                        /* fileTypes */ emptyArray<TdApi.FileType>(),
                        /* chatIds */ LongArray(0),
                        /* excludeChatIds */ LongArray(0),
                        /* returnDeletedFileStatistics */ false,
                        /* chatLimit */ -1,
                    ),
                )
            }
        }
    }

    private companion object {
        const val CACHE_CAP_BYTES = 1_500_000_000L // ~1.5 GB
        const val IMMUNITY_DELAY_SECS = 3600 // 1h: files accessed within this window are kept regardless of the cap
        const val TAG = "Telegramedia"
    }

    private fun mapChat(chat: TdApi.Chat): TgChat {
        val kind = when (val type = chat.type) {
            is TdApi.ChatTypePrivate, is TdApi.ChatTypeSecret -> ChatKind.PRIVATE
            is TdApi.ChatTypeBasicGroup -> ChatKind.GROUP
            is TdApi.ChatTypeSupergroup -> if (type.isChannel) ChatKind.CHANNEL else ChatKind.GROUP
            else -> ChatKind.GROUP
        }
        val lastMessage = when (val content = chat.lastMessage?.content) {
            is TdApi.MessageText -> content.text.text
            is TdApi.MessageVideo -> content.caption.text.ifBlank { "📹 Video" }
            is TdApi.MessageDocument -> content.document.fileName.ifBlank { "📄 Document" }
            is TdApi.MessageAudio -> content.audio.fileName.ifBlank { "🎵 Audio" }
            is TdApi.MessagePhoto -> content.caption.text.ifBlank { "🖼 Photo" }
            else -> null // also covers chat.lastMessage == null
        }
        return TgChat(
            id = chat.id,
            title = chat.title,
            kind = kind,
            lastMessage = lastMessage,
            unreadCount = chat.unreadCount,
            photoUrl = null,
            photoFileId = chat.photo?.small?.id,
            hasMedia = false,
        )
    }
}
