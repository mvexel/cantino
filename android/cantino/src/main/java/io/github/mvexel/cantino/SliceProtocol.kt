package io.github.mvexel.cantino

import org.json.JSONObject

/**
 * Typed Kotlin view of the SliceOSM protocol helpers in the Rust core
 * (`src/slice.rs`, exposed through `cantino_slice_*`). The core decides
 * what to send and how to read answers; this file only converts JSON, and
 * [SliceHttp] performs the HTTP. Keeping the protocol in Rust means the iOS
 * adapter gets exactly the same request bodies, URL layout and progress rules.
 *
 * All functions are pure and may be called from any thread. Invalid input
 * (bad bbox or base URL, non-UUID job response, non-JSON status) throws
 * [CantinoException.InvalidArgument]; the worker decides from the call site
 * whether that is the app's request or the server's answer.
 */
internal object SliceProtocol {
    /** POST [body] (JSON) to [url]; the answer body is the job ID text. */
    data class JobRequest(val url: String, val body: String)

    data class Job(val id: String, val statusUrl: String, val downloadUrl: String)

    /**
     * [fraction] is null until the server reports totals. [timestamp] is the
     * OSM replication timestamp of the snapshot (the data's age), ISO 8601.
     */
    data class Progress(val complete: Boolean, val fraction: Double?, val sizeBytes: Long?, val timestamp: String?)

    fun jobRequest(base: String?, bbox: Bbox, name: String): JobRequest =
        JSONObject(NativeBridge.sliceJobRequest(base, bbox.toJson().toString(), name))
            .let { JobRequest(it.getString("url"), it.getString("body")) }

    /** Parses a submit response, or re-validates a persisted job ID. */
    fun job(base: String?, response: String): Job =
        JSONObject(NativeBridge.sliceJob(base, response))
            .let { Job(it.getString("job_id"), it.getString("status_url"), it.getString("download_url")) }

    fun progress(status: String): Progress =
        JSONObject(NativeBridge.sliceProgress(status)).let {
            Progress(
                complete = it.getBoolean("complete"),
                fraction = it.optNullableDouble("fraction"),
                sizeBytes = if (it.isNull("size_bytes")) null else it.getLong("size_bytes"),
                timestamp = if (it.isNull("timestamp")) null else it.getString("timestamp"),
            )
        }
}

internal fun JSONObject.optNullableDouble(key: String): Double? = if (isNull(key)) null else getDouble(key)
