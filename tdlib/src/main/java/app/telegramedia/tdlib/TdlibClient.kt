package app.telegramedia.tdlib

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi

/** Thrown when TDLib returns a [TdApi.Error] for a query. */
class TdlibException(val code: Int, override val message: String) : Exception("TDLib $code: $message")

/**
 * Thin coroutine/Flow wrapper around the raw TDLib JNI [Client].
 *
 * - [updates] is a hot stream of every `TdApi.Update*` pushed by TDLib.
 * - [send] suspends until TDLib answers a query, surfacing errors as [TdlibException].
 *
 * Owns one native client for the lifetime of the instance.
 */
class TdlibClient {

    companion object {
        @Volatile private var nativeLoaded = false

        private fun ensureNativeLoaded() {
            if (!nativeLoaded) {
                synchronized(this) {
                    if (!nativeLoaded) {
                        System.loadLibrary("tdjni")
                        nativeLoaded = true
                    }
                }
            }
        }

        /** Set TDLib's global log verbosity (0 = fatal only … 5 = verbose). Safe to call early. */
        fun setLogVerbosity(level: Int) {
            ensureNativeLoaded()
            runCatching { Client.execute(TdApi.SetLogVerbosityLevel(level)) }
        }
    }

    private val _updates = MutableSharedFlow<TdApi.Object>(
        replay = 1,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val updates: SharedFlow<TdApi.Object> = _updates.asSharedFlow()

    private val client: Client = run {
        ensureNativeLoaded()
        Client.create(
            { update -> _updates.tryEmit(update) },
            { /* update-handler exception — ignored, surfaced per-query instead */ },
            { /* default exception */ },
        )
    }

    /** Suspends until TDLib answers; throws [TdlibException] on a TdApi.Error. */
    suspend fun <T : TdApi.Object> send(query: TdApi.Function<T>): T {
        val deferred = CompletableDeferred<TdApi.Object>()
        client.send(
            query,
            { result -> deferred.complete(result) },
            { e -> deferred.completeExceptionally(e) },
        )
        val result = deferred.await()
        if (result is TdApi.Error) throw TdlibException(result.code, result.message)
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    /** Fire-and-forget; ignores the result and any error. */
    fun sendOneWay(query: TdApi.Function<*>) {
        client.send(query, {}, {})
    }
}
