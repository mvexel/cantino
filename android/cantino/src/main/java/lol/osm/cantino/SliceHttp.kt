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
import org.json.JSONObject

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
 * Which [DownloadFailure] a failure input is, decided by the core
 * (`cantino_classify_failure`, `src/failure.rs`) so Android and iOS classify
 * identically. Kotlin keeps the messages; the class and [FailureReason] come
 * from the core's table (HTTP status by request kind, network vs storage
 * I/O, native error kind by call site).
 */
internal object Failures {
    /** HTTP [status] answering a [context] request (`job`, `request`, `range`), or null if accepted. */
    fun http(status: Int, context: String): Classified? = classify(JSONObject().put("http", status).put("context", context))

    /** An I/O error on the `network` or in local `storage`. */
    fun io(context: String): Classified = checkNotNull(classify(JSONObject().put("io", context))) { "io $context" }

    /**
     * A native [error] from a [context] call: `default` (import, PMTiles
     * validation), `engine` (basemap extract), `protocol_request`,
     * `protocol_response` (SliceOSM).
     */
    fun native(error: CantinoException, context: String): Classified =
        checkNotNull(classify(JSONObject().put("native", error.code).put("context", context))) { "native $context" }

    private fun classify(input: JSONObject): Classified? = NativeBridge.classifyFailure(input.toString())?.let {
        val json = JSONObject(it)
        Classified(json.getString("class"), FailureReason.valueOf(json.getString("reason").uppercase(java.util.Locale.ROOT)))
    }

    /** The `CANTINO_ERROR_*` code of a typed native error (the JNI layer chose the subclass from it). */
    private val CantinoException.code: Int
        get() = when (this) {
            is CantinoException.InvalidArgument -> 1
            is CantinoException.InvalidFile -> 2
            is CantinoException.Io -> 3
            is CantinoException.WrongThread -> 4
        }
}

/** A classification from [Failures]: [kind] is the core's class name. */
internal class Classified(val kind: String, val reason: FailureReason) {
    /** The [DownloadFailure] to throw, carrying [message]. */
    fun failure(message: String, cause: Throwable? = null): DownloadFailure = when (kind) {
        "transient" -> DownloadFailure.Transient(message, reason, cause)
        "permanent" -> DownloadFailure.Permanent(message, reason, cause)
        "storage" -> DownloadFailure.Storage(message, cause) // reason is STORAGE by definition
        "job_gone" -> DownloadFailure.JobGone(message) // reason is SERVER by definition
        else -> throw IllegalStateException("unknown failure class $kind")
    }
}

/**
 * A [DownloadFailure] for a native failure of the import or of PMTiles
 * validation: storage becomes [DownloadFailure.Storage] (retried by
 * WorkManager once storage is no longer low); everything else repeats on
 * retry, so it is permanent (core table, context `default`).
 */
internal fun failure(message: String, error: CantinoException): DownloadFailure =
    Failures.native(error, "default").failure(message, error)

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
    throw Failures.io("storage").failure(
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
        // The class and reason come from the core's table (context "range");
        // only 206 passes. The messages are chosen here.
        Failures.http(code, "range")?.let { classified ->
            throw classified.failure(
                when (code) {
                    200 -> "server ignored the Range header (HTTP 200 instead of 206) for $url; " +
                        "basemap extracts need a server or CDN with HTTP range support"
                    // The archive is shorter than its own directories say (or
                    // changed under us): the server's file, not the request.
                    416 -> "HTTP 416 range $offset+$length not satisfiable at $url"
                    in 200..299 -> "HTTP $code for a range request to $url (need 206)"
                    else -> statusMessage(url, code)
                },
            )
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
                throw Failures.io("network").failure(
                    "network error: ${error.message ?: error.javaClass.simpleName}",
                    error,
                )
            } finally {
                watchdog.cancel()
                connection.disconnect()
            }
        }

    /**
     * Throws for a status the request does not accept. The core's table
     * (`cantino_classify_failure`): 2xx passes; 404 is a vanished job
     * ([DownloadFailure.JobGone]) only with [goneOn404] (context `job`);
     * 408, 429 and 5xx are transient; any other status means the server
     * understood and refused what we asked for (a bbox SliceOSM rejects, a
     * basemap URL that does not exist): permanent, INVALID_REQUEST.
     */
    private fun HttpURLConnection.checkStatus(url: String, goneOn404: Boolean) {
        val code = responseCode
        val classified = Failures.http(code, if (goneOn404) "job" else "request") ?: return
        throw classified.failure(statusMessage(url, code))
    }

    /** "HTTP <code> from <url> <start of the error body>". */
    private fun HttpURLConnection.statusMessage(url: String, code: Int): String {
        val detail = try {
            errorStream?.use { it.readBytes().decodeToString().take(200) }.orEmpty()
        } catch (_: IOException) {
            ""
        }
        return "HTTP $code from $url ${detail.trim()}".trim()
    }

    private companion object {
        val CONTENT_RANGE = Regex("""bytes (\d+)-(\d+)/(\d+|\*)""")
        const val USER_AGENT = "cantino/${Cantino.VERSION}"
    }
}

/**
 * Retries transient failures within one run with doubling delays
 * ([DownloadTuning.inlineRetries] retries after the first attempt). Permanent
 * failures and cancellation pass straight through.
 */
internal suspend fun <T> retryingInline(block: suspend () -> T): T {
    var wait = DownloadTuning.current.inlineRetryDelayMillis
    repeat(DownloadTuning.current.inlineRetries) {
        try {
            return block()
        } catch (_: DownloadFailure.Transient) {
            delay(wait)
            wait *= 2
        }
    }
    return block()
}
