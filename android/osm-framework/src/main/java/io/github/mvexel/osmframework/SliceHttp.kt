package io.github.mvexel.osmframework

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Why a download step failed, classified by what the caller should do next.
 * The worker maps these onto WorkManager results; nothing else interprets
 * HTTP status codes.
 */
internal sealed class DownloadFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /**
     * May succeed later without changing the request: network errors and
     * timeouts, HTTP 5xx, 408 and 429, a truncated body, a slow slicing job.
     */
    class Transient(message: String, cause: Throwable? = null) : DownloadFailure(message, cause)

    /** Retrying the same request cannot help: other HTTP 4xx, invalid input, data that fails to import. */
    class Permanent(message: String, cause: Throwable? = null) : DownloadFailure(message, cause)

    /** The server no longer knows this job (HTTP 404 on status or file): submit a new one. */
    class JobGone(message: String) : DownloadFailure(message)
}

/**
 * Minimal HTTP for the SliceOSM protocol on [HttpURLConnection] (no OkHttp in
 * the AAR). Every call runs on [Dispatchers.IO], has connect and read
 * timeouts, and is cancellable: a stalled socket read ignores coroutine
 * cancellation and thread interrupts, so a watchdog coroutine disconnects
 * the connection when the caller is cancelled, which makes the read fail
 * immediately. IOExceptions become [DownloadFailure.Transient] unless the
 * caller was cancelled, in which case the CancellationException wins.
 */
internal class SliceHttp(private val connectTimeoutMillis: Int, private val readTimeoutMillis: Int) {
    suspend fun postJson(url: String, body: String): String = connect(url) { connection ->
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(body.toByteArray()) }
        connection.checkStatus(url, goneOn404 = false)
        connection.inputStream.use { it.readBytes().decodeToString() }
    }

    /** GET a small text document. A 404 means the job is gone. */
    suspend fun getText(url: String): String = connect(url) { connection ->
        connection.checkStatus(url, goneOn404 = true)
        connection.inputStream.use { it.readBytes().decodeToString() }
    }

    /**
     * Streams [url] into [target] (truncating it: there is no byte-range
     * resume, an interrupted download restarts from byte 0). [onProgress] is
     * called between chunks with (bytes so far, total or null); it is a
     * suspend function so the caller can publish progress and so cancellation
     * is observed at least once per chunk. Returns the number of bytes written.
     */
    suspend fun download(url: String, target: File, onProgress: suspend (Long, Long?) -> Unit): Long =
        connect(url) { connection ->
            // No transparent gzip: byte counts must be the file's own size.
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.checkStatus(url, goneOn404 = true)
            val total = connection.contentLengthLong.takeIf { it >= 0 }
            var bytes = 0L
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        bytes += read
                        onProgress(bytes, total)
                    }
                    // Durable before import reads it; the import itself fsyncs its output.
                    output.fd.sync()
                }
            }
            if (total != null && bytes != total) {
                throw DownloadFailure.Transient("download truncated: $bytes of $total bytes")
            }
            bytes
        }

    private suspend fun <T> connect(url: String, block: suspend (HttpURLConnection) -> T): T =
        withContext(Dispatchers.IO) {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = connectTimeoutMillis
                readTimeout = readTimeoutMillis
                setRequestProperty("User-Agent", USER_AGENT)
            }
            val watchdog = launch {
                try {
                    awaitCancellation()
                } finally {
                    connection.disconnect()
                }
            }
            try {
                block(connection)
            } catch (error: IOException) {
                ensureActive() // cancelled: report the cancellation, not the socket error it caused
                throw DownloadFailure.Transient("network error: ${error.message ?: error.javaClass.simpleName}", error)
            } finally {
                watchdog.cancel()
                connection.disconnect()
            }
        }

    private fun HttpURLConnection.checkStatus(url: String, goneOn404: Boolean) {
        val code = responseCode
        if (code in 200..299) return
        val detail = try {
            errorStream?.use { it.readBytes().decodeToString().take(200) }.orEmpty()
        } catch (_: IOException) {
            ""
        }
        val message = "HTTP $code from $url ${detail.trim()}".trim()
        throw when {
            code == 404 && goneOn404 -> DownloadFailure.JobGone(message)
            code == 408 || code == 429 || code >= 500 -> DownloadFailure.Transient(message)
            else -> DownloadFailure.Permanent(message)
        }
    }

    private companion object {
        const val USER_AGENT = "osm-framework-android/0.1.0"
    }
}
