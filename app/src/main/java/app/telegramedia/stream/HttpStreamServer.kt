package app.telegramedia.stream

import android.util.Log
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Where a served file's bytes come from. Implementations back the HTTP server
 * with either a TDLib download (progressive) or a fully-present local file.
 */
interface StreamSource {
    /** Total size in bytes, or <= 0 if not yet known. */
    fun size(): Long

    /** Local file path to read from, or null until it exists. */
    fun path(): String?

    /** Absolute byte index up to which bytes are currently readable from [path]. */
    fun availableEnd(): Long

    /** Ask the source to make bytes available starting at [offset] (e.g. an offset download). */
    fun seekTo(offset: Long)
}

/**
 * Minimal localhost HTTP/1.1 server with `Range` support. mpv plays from
 * `http://127.0.0.1:<port>/<key>`; we stream bytes as the [StreamSource] makes
 * them available, so playback can begin before the whole file is downloaded.
 *
 * Native ffmpeg (mpv) opens these sockets directly, so Android's cleartext
 * policy doesn't apply.
 */
class HttpStreamServer {

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile var port: Int = 0
        private set

    private val sources = ConcurrentHashMap<String, StreamSource>()

    @Synchronized
    fun ensureStarted() {
        if (serverSocket != null) return
        val ss = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        serverSocket = ss
        port = ss.localPort
        thread(isDaemon = true, name = "tg-http-accept") {
            while (true) {
                val socket = try {
                    ss.accept()
                } catch (_: Exception) {
                    break
                }
                thread(isDaemon = true) { runCatching { serve(socket) } }
            }
        }
    }

    /** Register [source] under [key] and return the URL mpv should open. */
    fun register(key: String, source: StreamSource): String {
        ensureStarted()
        sources[key] = source
        return "http://127.0.0.1:$port/$key"
    }

    /** Drop all registered sources, releasing the [StreamSource]s (and any TDLib
     *  client they captured). Called when the backing client is torn down. */
    fun clearSources() {
        sources.clear()
    }

    private fun serve(socket: Socket): Unit = socket.use {
        socket.tcpNoDelay = true
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        val requestLine = reader.readLine() ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 2) return
        val method = parts[0]
        val key = parts[1].trimStart('/')
        val source = sources[key] ?: return writeStatus(socket, 404)

        var start = 0L
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Range:", ignoreCase = true)) {
                start = RANGE.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            }
        }

        // Resolve total size (may take a moment for a fresh TDLib file).
        var total = source.size()
        var tries = 0
        while (total <= 0 && tries++ < 200) {
            Thread.sleep(50)
            total = source.size()
        }
        if (total <= 0) {
            Log.w(TAG, "serve key=$key size unresolved → 500")
            return writeStatus(socket, 500)
        }
        // A range at/beyond EOF can't be satisfied. Reset-to-0 would send byte 0
        // under a 206 the client read as "here's the offset you asked for" → it
        // misplaces data. 416 lets the client fail/clamp cleanly instead.
        if (start >= total) return writeStatus(socket, 416)

        // Trigger a download from `start` only when those bytes aren't already
        // readable. Re-seeking on EVERY request reset TDLib's download head, so
        // parallel mpv connections and rapid backward seeks thrashed and stalled.
        // We take the smaller of TDLib's reported prefix and the real file length:
        //  - a file trimCache deleted reports a stale-high prefix but length 0 → re-download
        //  - a pre-allocated file reports a high length but a small prefix → re-download
        // so the trimmed-file recovery still works without the per-request thrash.
        val availAtStart = source.availableEnd()
        val onDisk = source.path()?.let { java.io.File(it).length() } ?: 0L
        val readableEnd = minOf(availAtStart, onDisk)
        Log.i(TAG, "serve key=$key start=$start availAtStart=$availAtStart onDisk=$onDisk total=$total")
        if (readableEnd <= start) source.seekTo(start)

        val out = BufferedOutputStream(socket.getOutputStream())
        out.write(
            (
                "HTTP/1.1 206 Partial Content\r\n" +
                    "Content-Type: application/octet-stream\r\n" +
                    "Accept-Ranges: bytes\r\n" +
                    "Content-Range: bytes $start-${total - 1}/$total\r\n" +
                    "Content-Length: ${total - start}\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray()
        )
        // Flush headers immediately. Otherwise they sit in the buffer while the body
        // loop waits for the first bytes, and mpv's open sees no response (EOF).
        out.flush()
        if (method.equals("HEAD", ignoreCase = true)) {
            return
        }

        Log.i(TAG, "serve key=$key start=$start total=$total — headers sent")
        val buffer = ByteArray(128 * 1024)
        var pos = start
        var raf: RandomAccessFile? = null
        var stalledMs = 0L
        var wrote = 0L
        try {
            while (pos < total) {
                val path = source.path()
                val end = source.availableEnd()
                // Open the file lazily; it may not exist on disk for the first moment.
                if (path != null && raf == null) {
                    raf = runCatching { RandomAccessFile(path, "r") }.getOrNull()
                    if (raf == null && stalledMs == 0L) {
                        val f = java.io.File(path)
                        Log.w(TAG, "RAF open failed: exists=${f.exists()} len=${f.length()} path=$path")
                    }
                }
                if (raf == null || end <= pos) {
                    if (stalledMs >= STALL_TIMEOUT_MS) {
                        Log.w(TAG, "serve key=$key stall: pos=$pos end=$end path=${path != null} wrote=$wrote")
                        break
                    }
                    Thread.sleep(50)
                    stalledMs += 50
                    continue
                }
                stalledMs = 0
                raf.seek(pos)
                val toRead = minOf(buffer.size.toLong(), end - pos, total - pos).toInt()
                val n = raf.read(buffer, 0, toRead)
                if (n <= 0) {
                    if (stalledMs >= STALL_TIMEOUT_MS) break
                    Thread.sleep(50)
                    stalledMs += 50
                    continue
                }
                out.write(buffer, 0, n)
                pos += n
                wrote += n
            }
            out.flush()
        } catch (e: IOException) {
            // Usually the client closing the connection (seek/stop).
            Log.i(TAG, "serve key=$key closed after $wrote bytes (${e.message})")
        } catch (e: Throwable) {
            Log.e(TAG, "serve key=$key error after $wrote bytes", e)
        } finally {
            runCatching { raf?.close() }
        }
    }

    private fun writeStatus(socket: Socket, code: Int) {
        runCatching {
            socket.getOutputStream()
                .write("HTTP/1.1 $code _\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        }
    }

    private companion object {
        val RANGE = Regex("bytes=(\\d+)-")
        const val STALL_TIMEOUT_MS = 20_000L
        const val TAG = "TgStream"
    }
}

/** A fully-present local file — used in demo mode (and any complete download). */
class StaticFileStreamSource(private val absolutePath: String) : StreamSource {
    private val length = java.io.File(absolutePath).length()
    override fun size() = length
    override fun path() = absolutePath
    override fun availableEnd() = length
    override fun seekTo(offset: Long) {}
}
