package lol.osm.cantino

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
 * Why a download step failed, classified two ways: by what the worker does
 * next (the subclass: retry or give up) and by what the app can do about it
 * ([reason], which ends up in [AreaState.Failed.reason]). Every throw site
 * names its reason explicitly; there is no default. The worker maps these
 * onto WorkManager results; nothing else interprets HTTP status codes.
 */
internal sealed class DownloadFailure(
    message: String,
    val reason: FailureReason,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /**
     * May succeed later without changing the request: network errors and
     * timeouts, HTTP 5xx, 408 and 429, a truncated body, a slow slicing job.
     */
    class Transient(message: String, reason: FailureReason, cause: Throwable? = null) :
        DownloadFailure(message, reason, cause)

    /**
     * Retrying the same request cannot help: other HTTP 4xx, invalid input,
     * data that fails to import.
     */
    class Permanent(message: String, reason: FailureReason, cause: Throwable? = null) :
        DownloadFailure(message, reason, cause)

    /**
     * The device's storage failed (typically a full disk). Retried by
     * WorkManager, whose storage-not-low constraint holds the next run until
     * space is freed (Martijn 2026-10-03), but never inline: retrying within
     * the run would only fill the same disk again.
     */
    class Storage(message: String, cause: Throwable? = null) :
        DownloadFailure(message, FailureReason.STORAGE, cause)

    /** The server no longer knows this job (HTTP 404 on status or file): submit a new one. */
    class JobGone(message: String) : DownloadFailure(message, FailureReason.SERVER)
}

/**
 * The [FailureReason] for a native failure during a download, when the call
 * site adds no better context. Exhaustive over the sealed hierarchy:
 * - InvalidFile: the downloaded data (PBF, PMTiles) is bad.
 * - Io: the device's storage (disk full, unwritable staging file).
 * - InvalidArgument: what the worker passed came from the request (bbox,
 *   zooms, URLs), so the request is invalid.
 * - WrongThread: the worker's own threading is broken, a Cantino bug.
 */
internal fun CantinoException.failureReason(): FailureReason = when (this) {
    is CantinoException.InvalidFile -> FailureReason.INVALID_DATA
    is CantinoException.Io -> FailureReason.STORAGE
    is CantinoException.InvalidArgument -> FailureReason.INVALID_REQUEST
    is CantinoException.WrongThread -> FailureReason.UNKNOWN
}

/**
 * A [DownloadFailure] for a native failure: storage becomes
 * [DownloadFailure.Storage] (retried by WorkManager once storage is no longer
 * low); everything else repeats on retry, so it is permanent.
 */
internal fun failure(message: String, error: CantinoException): DownloadFailure {
    val reason = error.failureReason()
    return if (reason == FailureReason.STORAGE) {
        DownloadFailure.Storage(message, error)
    } else {
        DownloadFailure.Permanent(message, reason, error)
    }
}

/**
 * Runs a write to a local download file. An IOException here is the
 * device's storage (typically a full disk), not the network: it must not be
 * caught by [SliceHttp]'s network handler and reported as a network error,
 * so it becomes a [DownloadFailure.Storage] failure (retried by WorkManager,
 * not inline). [DownloadFailure] is not an IOException, so it passes that
 * handler.
 */
private inline fun <T> disk(target: File, block: () -> T): T = try {
    block()
} catch (error: IOException) {
    throw DownloadFailure.Storage(
        "cannot write ${target.name}: ${error.message ?: error.javaClass.simpleName}",
        error,
    )
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
                // Reads are network (IOException → NETWORK in connect());
                // writes are storage (disk → STORAGE).
                disk(target) { target.outputStream() }.use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        disk(target) { output.write(buffer, 0, read) }
                        bytes += read
                        onProgress(bytes, total)
                    }
                    // Durable before import reads it; the import itself fsyncs its output.
                    disk(target) { output.fd.sync() }
                }
            }
            if (total != null && bytes != total) {
                throw DownloadFailure.Transient("download truncated: $bytes of $total bytes", FailureReason.NETWORK)
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
     * request of a tiny archive). See [openRange] for what is accepted.
     */
    suspend fun getRange(url: String, offset: Long, length: Long, allowShort: Boolean = false): ByteArray =
        connect(url) { connection ->
            val expected = connection.openRange(url, offset, length, allowShort)
            val body = ByteArray(expected.toInt())
            connection.inputStream.use { input ->
                var at = 0
                while (at < body.size) {
                    val read = input.read(body, at, body.size - at)
                    if (read < 0) {
                        throw DownloadFailure.Transient(
                            "range $offset+$length truncated at $at of ${body.size} bytes",
                            FailureReason.NETWORK,
                        )
                    }
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
            disk(target) { target.outputStream() }.use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    disk(target) { output.write(buffer, 0, read) }
                    bytes += read
                }
            }
        }
        // More than asked for cannot happen once Content-Range matched; fewer
        // is a dropped connection: transient, re-fetch the range.
        if (bytes != length) {
            throw DownloadFailure.Transient("range $offset+$length truncated: $bytes bytes", FailureReason.NETWORK)
        }
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
                FailureReason.SERVER,
            )
        }
        // The archive is shorter than its own directories say (or changed
        // under us): the server's file, not the request.
        if (code == 416) {
            throw DownloadFailure.Permanent("HTTP 416 range $offset+$length not satisfiable at $url", FailureReason.SERVER)
        }
        checkStatus(url, goneOn404 = false)
        if (code != 206) {
            throw DownloadFailure.Permanent("HTTP $code for a range request to $url (need 206)", FailureReason.SERVER)
        }
        // "bytes first-last/total"
        val range = getHeaderField("Content-Range")
            ?.let { CONTENT_RANGE.matchEntire(it.trim()) }
            ?: throw DownloadFailure.Permanent("206 without a valid Content-Range from $url", FailureReason.SERVER)
        val first = range.groupValues[1].toLong()
        val last = range.groupValues[2].toLong()
        val got = last - first + 1
        val lengthOk = if (allowShort) got in 1..length else got == length
        if (first != offset || !lengthOk) {
            throw DownloadFailure.Permanent(
                "server sent bytes $first-$last for requested $offset+$length from $url",
                FailureReason.SERVER,
            )
        }
        val contentLength = contentLengthLong
        if (contentLength >= 0 && contentLength != got) {
            throw DownloadFailure.Permanent(
                "Content-Length $contentLength disagrees with Content-Range $first-$last from $url",
                FailureReason.SERVER,
            )
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
                throw DownloadFailure.Transient(
                    "network error: ${error.message ?: error.javaClass.simpleName}",
                    FailureReason.NETWORK,
                    error,
                )
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
            code == 408 || code == 429 || code >= 500 -> DownloadFailure.Transient(message, FailureReason.SERVER)
            // Any other 4xx: the server understood and refused what we asked
            // for (a bbox SliceOSM rejects, a basemap URL that does not exist).
            else -> DownloadFailure.Permanent(message, FailureReason.INVALID_REQUEST)
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
