package io.github.mvexel.cantino

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
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
 * Minimal HTTP for area downloads on [HttpURLConnection] (no OkHttp in the
 * AAR): the SliceOSM protocol, plain file downloads, and the HTTP range
 * requests of a basemap extract. Every call runs on [Dispatchers.IO], has connect and read
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
    suspend fun download(
        url: String,
        target: File,
        goneOn404: Boolean = true,
        onProgress: suspend (Long, Long?) -> Unit,
    ): Long =
        connect(url) { connection ->
            // No transparent gzip: byte counts must be the file's own size.
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.checkStatus(url, goneOn404 = goneOn404)
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

    /**
     * HEAD [url]: true for 2xx, false for 404, otherwise the usual failure
     * classes. Used to find the newest Protomaps daily build.
     */
    suspend fun exists(url: String): Boolean = connect(url) { connection ->
        connection.requestMethod = "HEAD"
        if (connection.responseCode == 404) return@connect false
        connection.checkStatus(url, goneOn404 = false)
        true
    }

    /**
     * Bytes `offset until offset + length` of [url] into memory (directory
     * ranges of a basemap extract: small). With [allowShort] the server may
     * answer with fewer bytes when the file ends earlier (the 16 KiB first
     * request of a tiny archive). See [checkRange] for what is accepted.
     */
    suspend fun getRange(url: String, offset: Long, length: Long, allowShort: Boolean = false): ByteArray =
        connect(url) { connection ->
            val expected = connection.openRange(url, offset, length, allowShort)
            val body = ByteArray(expected.toInt())
            connection.inputStream.use { input ->
                var at = 0
                while (at < body.size) {
                    val read = input.read(body, at, body.size - at)
                    if (read < 0) throw DownloadFailure.Transient("range $offset+$length truncated at $at of ${body.size} bytes")
                    at += read
                }
            }
            body
        }

    /**
     * Streams bytes `offset until offset + length` of [url] into [target]
     * (truncating it), for tile ranges, which can be megabytes: they go to
     * disk and from there into the native assembler, never through the heap.
     */
    suspend fun downloadRange(url: String, offset: Long, length: Long, target: File) = connect(url) { connection ->
        connection.openRange(url, offset, length, allowShort = false)
        var bytes = 0L
        connection.inputStream.use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    bytes += read
                }
            }
        }
        // More than asked for cannot happen once Content-Range matched; fewer
        // is a dropped connection: transient, re-fetch the range.
        if (bytes != length) throw DownloadFailure.Transient("range $offset+$length truncated: $bytes bytes")
    }

    /**
     * Sends `Range: bytes=a-b` and checks the answer before any body byte is
     * read. Returns the number of body bytes to expect.
     *
     * - **206** with a `Content-Range` starting at [offset] and exactly
     *   [length] bytes (or fewer with [allowShort], ending at the file's end)
     *   is the only success.
     * - **200** means the server ignored the Range header and is about to
     *   send the whole (possibly 100+ GB planet) file: a permanent failure
     *   with a clear message, the body is never read.
     * - 416 (range not satisfiable: the file is shorter than its own header
     *   says, or changed) and other 4xx are permanent; 5xx, 408 and 429 are
     *   transient, as everywhere else.
     */
    private fun HttpURLConnection.openRange(url: String, offset: Long, length: Long, allowShort: Boolean): Long {
        require(length > 0) { "empty range" }
        setRequestProperty("Range", "bytes=$offset-${offset + length - 1}")
        // Byte ranges address the stored representation; no transparent gzip.
        setRequestProperty("Accept-Encoding", "identity")
        val code = responseCode
        if (code == 200) {
            throw DownloadFailure.Permanent(
                "server ignored the Range header (HTTP 200 instead of 206) for $url; " +
                    "basemap extracts need a server or CDN with HTTP range support",
            )
        }
        if (code == 416) throw DownloadFailure.Permanent("HTTP 416 range $offset+$length not satisfiable at $url")
        checkStatus(url, goneOn404 = false)
        if (code != 206) throw DownloadFailure.Permanent("HTTP $code for a range request to $url (need 206)")
        // "bytes first-last/total"
        val range = getHeaderField("Content-Range")
            ?.let { CONTENT_RANGE.matchEntire(it.trim()) }
            ?: throw DownloadFailure.Permanent("206 without a valid Content-Range from $url")
        val first = range.groupValues[1].toLong()
        val last = range.groupValues[2].toLong()
        val got = last - first + 1
        val lengthOk = if (allowShort) got in 1..length else got == length
        if (first != offset || !lengthOk) {
            throw DownloadFailure.Permanent("server sent bytes $first-$last for requested $offset+$length from $url")
        }
        val contentLength = contentLengthLong
        if (contentLength >= 0 && contentLength != got) {
            throw DownloadFailure.Permanent("Content-Length $contentLength disagrees with Content-Range $first-$last from $url")
        }
        return got
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
        val CONTENT_RANGE = Regex("""bytes (\d+)-(\d+)/(\d+|\*)""")
        const val USER_AGENT = "cantino/${Cantino.VERSION}"
    }
}

/**
 * Retries transient failures within one run with doubling delays
 * ([AreaConfig.inlineRetries] retries after the first attempt). Permanent
 * failures and cancellation pass straight through.
 */
internal suspend fun <T> retryingInline(config: AreaConfig, block: suspend () -> T): T {
    var wait = config.inlineRetryDelayMillis
    repeat(config.inlineRetries) {
        try {
            return block()
        } catch (_: DownloadFailure.Transient) {
            delay(wait)
            wait *= 2
        }
    }
    return block()
}
