package io.github.mvexel.osmframework

import org.json.JSONObject
import java.io.File

/**
 * Tuning and endpoints for [AreaManager]. Defaults target the public SliceOSM
 * service; tests inject a local server through [sliceBaseUrl].
 *
 * Retries happen at two levels. Inside one run, a transient HTTP failure
 * (network error, timeout, 5xx, 408, 429) is retried [inlineRetries] times
 * with a delay starting at [inlineRetryDelayMillis] and doubling, so a
 * short server hiccup does not cost a WorkManager backoff. If that is not
 * enough the run ends with `Result.retry()` and WorkManager reschedules it
 * with exponential backoff from [backoffDelayMillis] (WorkManager enforces a
 * 10 s minimum), up to [maxRunAttempts] runs in total. Permanent failures
 * (other 4xx, data that fails to import) are never retried.
 */
data class AreaConfig(
    val sliceBaseUrl: String = DEFAULT_SLICE_BASE_URL,
    val pollIntervalMillis: Long = 2_000,
    /** Slicing longer than this within one run is treated as transient (the run retries and resumes polling). */
    val maxSliceWaitMillis: Long = 8 * 60_000,
    val connectTimeoutMillis: Int = 15_000,
    val readTimeoutMillis: Int = 60_000,
    val inlineRetries: Int = 3,
    val inlineRetryDelayMillis: Long = 1_000,
    val maxRunAttempts: Int = 5,
    val backoffDelayMillis: Long = 30_000,
    val importOptions: ImportOptions = ImportOptions(),
) {
    companion object {
        const val DEFAULT_SLICE_BASE_URL = "https://slice.openstreetmap.us/"
    }
}

/**
 * What is known about a published area file. [metadata] is null when the
 * sidecar is missing or does not describe this file (possible only if the
 * process died between publishing the file and writing its sidecar); the
 * file itself is still a complete, valid area.
 */
data class AreaInfo(val areaId: String, val file: File, val metadata: AreaMetadata?)

/**
 * [snapshotTimestamp] is SliceOSM's replication timestamp of the OSM data
 * (ISO 8601, e.g. `2026-10-03T20:30:01Z`): show it to users as the data age.
 * It is null if the server did not report one. [importedAtMillis] is the
 * device clock when the area was published.
 */
data class AreaMetadata(
    val bbox: Bbox,
    val name: String,
    val snapshotTimestamp: String?,
    val importedAtMillis: Long,
    val report: ImportReport,
) {
    internal fun toJson(): JSONObject = JSONObject()
        .put("bbox", bbox.toJson())
        .put("name", name)
        .put("snapshot_timestamp", snapshotTimestamp ?: JSONObject.NULL)
        .put("imported_at_millis", importedAtMillis)
        .put("report", report.toJson())

    internal companion object {
        fun fromJson(json: JSONObject) = AreaMetadata(
            Bbox.fromJson(json.getJSONObject("bbox")),
            json.getString("name"),
            if (json.isNull("snapshot_timestamp")) null else json.getString("snapshot_timestamp"),
            json.getLong("imported_at_millis"),
            ImportReport.fromJson(json.getJSONObject("report")),
        )
    }
}

/**
 * Lifecycle of one area's download, as observed through [AreaManager.state].
 *
 * ```
 * Idle ─download()→ Queued ─constraints met→ Submitting → Slicing → Downloading → Importing → Ready
 *                     ↑            │              │            │             │
 *                     └─ transient failure (backoff, same SliceOSM job resumed)┘
 * any non-final state ─cancel()→ Cancelled      permanent failure / retries exhausted → Failed
 * ```
 *
 * Invariant for every state: the previously published area (see
 * [AreaManager.publishedArea]) stays intact and openable until the moment an
 * import publishes its replacement. Only a run that reaches [Ready] replaces it.
 * WorkManager prunes finished work after about a day; the state then falls
 * back to [Idle] carrying the published area.
 */
sealed interface AreaState {
    /** No download is known. [published] is the area on disk, if any. */
    data class Idle(val published: AreaInfo?) : AreaState

    /**
     * Enqueued and waiting: for network/storage constraints, or for a backoff
     * after a transient failure ([attempt] runs have already happened).
     */
    data class Queued(val attempt: Int) : AreaState

    /** Submitting the job to SliceOSM, or re-attaching to the job of an interrupted run. */
    data object Submitting : AreaState

    /** SliceOSM is cutting the extract. [fraction] is 0..1, or null before the server reports totals. */
    data class Slicing(val fraction: Double?) : AreaState

    /** Downloading the PBF. [total] is null when the server does not send a length. */
    data class Downloading(val bytes: Long, val total: Long?) : AreaState

    /** Importing into SQLite and publishing. Cancellation is no longer honored from here on. */
    data object Importing : AreaState

    /** Published. Open [area]'s file with [OsmStore.open]; already-open stores keep the old snapshot. */
    data class Ready(val report: ImportReport, val snapshotTimestamp: String?, val area: AreaInfo) : AreaState

    /**
     * Gave up. [retryable] is true for transient causes (network, server)
     * that exhausted their retries: calling [AreaManager.download] again later
     * may succeed. False means the request or the data is bad.
     */
    data class Failed(val message: String, val retryable: Boolean) : AreaState

    /**
     * Cancelled by [AreaManager.cancel]. (A run replaced by a newer
     * [AreaManager.download] of the same area is not reported: the state
     * follows the new run.)
     */
    data object Cancelled : AreaState
}
