package app.telegramedia.telegram

import app.telegramedia.stream.StreamSource
import app.telegramedia.tdlib.TdlibClient
import kotlinx.coroutines.runBlocking
import org.drinkless.tdlib.TdApi

/**
 * [StreamSource] backed by a TDLib file. `getFile` is a cheap local query, so
 * the server can poll it; `seekTo` issues an offset [TdApi.DownloadFile] so
 * forward seeks fetch the needed region.
 */
class TdlibStreamSource(
    private val client: TdlibClient,
    private val fileId: Int,
    private val knownSize: Long = 0,
) : StreamSource {

    @Volatile private var cached: TdApi.File? = null
    @Volatile private var cachedAtNanos = 0L

    /**
     * Latest file state, cached for [CACHE_TTL_NANOS] so the HTTP server's tight
     * read loop (which calls size/path/availableEnd repeatedly) issues at most one
     * `getFile` per interval instead of one per accessor per iteration.
     */
    private fun current(): TdApi.File? {
        val now = System.nanoTime()
        cached?.let { if (now - cachedAtNanos < CACHE_TTL_NANOS) return it }
        val file = runBlocking { runCatching { client.send(TdApi.GetFile(fileId)) }.getOrNull() }
        if (file != null) {
            cached = file
            cachedAtNanos = now
        }
        return file ?: cached
    }

    private fun totalOf(file: TdApi.File): Long =
        if (file.size > 0) file.size else file.expectedSize

    override fun size(): Long = if (knownSize > 0) knownSize else current()?.let(::totalOf) ?: -1

    override fun path(): String? = current()?.local?.path?.ifEmpty { null }

    override fun availableEnd(): Long {
        val file = current() ?: return 0
        val local = file.local
        return if (local.isDownloadingCompleted) totalOf(file)
        else local.downloadOffset + local.downloadedPrefixSize
    }

    override fun seekTo(offset: Long) {
        runBlocking {
            runCatching { client.send(TdApi.DownloadFile(fileId, 32, offset, 0, false)) }
        }
    }

    private companion object {
        const val CACHE_TTL_NANOS = 100_000_000L // 100 ms
    }
}
