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
    /** Parallel HTTP range requests of a [BasemapSource.Extract] (go-pmtiles uses 4). */
    val basemapParallelism: Int = 4,
) {
    companion object {
        const val DEFAULT_SLICE_BASE_URL = "https://slice.openstreetmap.us/"
    }
}

/**
 * What is known about a published area. [file] is the OSM data (open it with
 * [OsmStore.open]); [basemapFile] is the published PMTiles basemap, or null
 * when the area was downloaded with [BasemapSource.None]. [metadata] is null
 * when the sidecar is missing or does not describe these files (areas
 * published before the sidecar existed, or by hand); the files themselves
 * are still complete and valid.
 */
data class AreaInfo(
    val areaId: String,
    val file: File,
    val metadata: AreaMetadata?,
    val basemapFile: File? = null,
) {
    /**
     * The basemap as a MapLibre Native source URL
     * (`pmtiles://file:///data/.../<areaId>.pmtiles`), or null without a
     * basemap. Use it as the `url` of a vector source in the style.
     */
    val pmtilesUrl: String? get() = basemapFile?.let { "pmtiles://file://${it.absolutePath}" }
}

/**
 * The published basemap: where it came from ([kind] `"url"` or `"extract"`,
 * [sourceUrl]), its size and tile count, and what the download cost
 * ([requests], [transferredBytes]; for a Url download one request and the
 * file size).
 */
data class BasemapMetadata(
    val kind: String,
    val sourceUrl: String,
    val bytes: Long,
    val addressedTiles: Long,
    val minZoom: Int,
    val maxZoom: Int,
    val requests: Long,
    val transferredBytes: Long,
) {
    internal fun toJson(): JSONObject = JSONObject()
        .put("kind", kind)
        .put("source_url", sourceUrl)
        .put("bytes", bytes)
        .put("addressed_tiles", addressedTiles)
        .put("min_zoom", minZoom)
        .put("max_zoom", maxZoom)
        .put("requests", requests)
        .put("transferred_bytes", transferredBytes)

    internal companion object {
        fun fromJson(json: JSONObject) = BasemapMetadata(
            json.getString("kind"),
            json.getString("source_url"),
            json.getLong("bytes"),
            json.getLong("addressed_tiles"),
            json.getInt("min_zoom"),
            json.getInt("max_zoom"),
            json.getLong("requests"),
            json.getLong("transferred_bytes"),
        )
    }
}

/**
 * [snapshotTimestamp] is SliceOSM's replication timestamp of the OSM data
 * (ISO 8601, e.g. `2026-10-03T20:30:01Z`): show it to users as the data age.
 * It is null if the server did not report one. [importedAtMillis] is the
 * device clock when the area was published. [basemap] describes the
 * published basemap, null for [BasemapSource.None]. [workId] is the
 * WorkManager run that published the area (null for areas published by older
 * versions); [AreaManager.state] uses it to tell whether a finished run
 * published.
 */
data class AreaMetadata(
    val bbox: Bbox,
    val name: String,
    val snapshotTimestamp: String?,
    val importedAtMillis: Long,
    val report: ImportReport,
    val basemap: BasemapMetadata? = null,
    val workId: String? = null,
) {
    internal fun toJson(): JSONObject = JSONObject()
        .put("bbox", bbox.toJson())
        .put("name", name)
        .put("snapshot_timestamp", snapshotTimestamp ?: JSONObject.NULL)
        .put("imported_at_millis", importedAtMillis)
        .put("report", report.toJson())
        .put("basemap", basemap?.toJson() ?: JSONObject.NULL)
        .put("work_id", workId ?: JSONObject.NULL)

    internal companion object {
        fun fromJson(json: JSONObject) = AreaMetadata(
            Bbox.fromJson(json.getJSONObject("bbox")),
            json.getString("name"),
            if (json.isNull("snapshot_timestamp")) null else json.getString("snapshot_timestamp"),
            json.getLong("imported_at_millis"),
            ImportReport.fromJson(json.getJSONObject("report")),
            json.optJSONObject("basemap")?.let { BasemapMetadata.fromJson(it) },
            if (json.isNull("work_id")) null else json.getString("work_id"),
        )
    }
}

/**
 * Lifecycle of one area's download, as observed through [AreaManager.state].
 *
 * ```
 * Idle ─download()→ Queued ─constraints met→ Submitting → Slicing → Downloading → Importing ─┬────────→ Ready
 *                     ↑            │              │            │             │          └→ Basemap ┘
 *                     └─ transient failure (backoff, same SliceOSM job resumed)┘    (opt-in, BasemapSource)
 * any non-final state ─cancel()→ Cancelled      permanent failure / retries exhausted → Failed
 * ```
 *
 * Invariant for every state: the previously published area (OSM data,
 * basemap and metadata; see [AreaManager.publishedArea]) stays intact and
 * openable until one short commit step publishes the replacement, all parts
 * together. Only a run that reaches [Ready] replaces it; [Failed] and
 * [Cancelled] runs publish nothing.
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

    /**
     * Importing into SQLite, into a staging file: nothing is published yet.
     * The native import itself cannot be interrupted, but a cancel during it
     * is honored when it returns (the staged import is discarded).
     */
    data object Importing : AreaState

    /**
     * Downloading the basemap ([BasemapSource.Url] or [BasemapSource.Extract])
     * after the OSM data was imported (staged, not yet published). [bytes] is
     * the progress in [phase]; [total] is null while unknown (directory
     * phase, or a server sending no length).
     */
    data class Basemap(val phase: BasemapPhase, val bytes: Long, val total: Long?) : AreaState

    /**
     * Published: OSM data and, if requested, the basemap. Open [area]'s file
     * with [OsmStore.open]; already-open stores keep the old snapshot. A
     * cancel that arrives once publishing has begun is ignored and the run
     * still ends here.
     */
    data class Ready(val report: ImportReport, val snapshotTimestamp: String?, val area: AreaInfo) : AreaState

    /**
     * Gave up. [retryable] is true for transient causes (network, server)
     * that exhausted their retries: calling [AreaManager.download] again later
     * may succeed. False means the request or the data is bad.
     */
    data class Failed(val message: String, val retryable: Boolean) : AreaState

    /**
     * Cancelled by [AreaManager.cancel] before publishing began: nothing of
     * this run was published. (A run replaced by a newer
     * [AreaManager.download] of the same area is not reported: the state
     * follows the new run.)
     */
    data object Cancelled : AreaState
}
