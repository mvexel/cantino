package io.github.mvexel.cantino

import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * Tuning and endpoints for [AreaManager]. The defaults target the public
 * SliceOSM service and suit a city-sized area; most apps pass `AreaConfig()`
 * or only set [foreground]. Tests inject a local server through [sliceBaseUrl].
 *
 * Retries happen at two levels. Inside one run, a transient HTTP failure
 * (network error, timeout, 5xx, 408, 429) is retried [inlineRetries] times
 * with a delay starting at [inlineRetryDelayMillis] and doubling, so a
 * short server hiccup does not cost a WorkManager backoff. If that is not
 * enough the run ends with `Result.retry()` and WorkManager reschedules it
 * with exponential backoff from [backoffDelayMillis] (WorkManager enforces a
 * 10 s minimum), up to [maxRunAttempts] runs in total. Permanent failures
 * (other 4xx, data that fails to import) are never retried.
 *
 * The config is copied into each work request, so a run restarted after
 * process death uses the config it was started with.
 *
 * @property sliceBaseUrl SliceOSM service root (http or https, ending in `/`).
 * @property pollIntervalMillis Delay between job status polls while SliceOSM slices.
 * @property maxSliceWaitMillis Slicing longer than this within one run is
 *   treated as transient: the run retries later and resumes polling the same job.
 * @property connectTimeoutMillis HTTP connect timeout per request.
 * @property readTimeoutMillis HTTP read timeout: the longest silence tolerated mid-response.
 * @property inlineRetries Retries of a transient HTTP failure within one run.
 * @property inlineRetryDelayMillis First inline retry delay; doubles per retry.
 * @property maxRunAttempts WorkManager runs in total before a transient cause becomes [AreaState.Failed].
 * @property backoffDelayMillis Initial WorkManager backoff between runs (exponential, at least 10 s).
 * @property importOptions Options for the on-device import of the downloaded extract.
 * @property basemapParallelism Parallel HTTP range requests of a
 *   [BasemapSource.Extract] (go-pmtiles uses 4).
 * @property foreground Opt-in: run downloads as a foreground service with a
 *   progress notification (see [ForegroundConfig]). Null (the default) runs
 *   them as ordinary background work, subject to WorkManager's 10-minute
 *   limit per run.
 */
public data class AreaConfig(
    val sliceBaseUrl: String = DEFAULT_SLICE_BASE_URL,
    val pollIntervalMillis: Long = 2_000,
    val maxSliceWaitMillis: Long = 8 * 60_000,
    val connectTimeoutMillis: Int = 15_000,
    val readTimeoutMillis: Int = 60_000,
    val inlineRetries: Int = 3,
    val inlineRetryDelayMillis: Long = 1_000,
    val maxRunAttempts: Int = 5,
    val backoffDelayMillis: Long = 30_000,
    val importOptions: ImportOptions = ImportOptions(),
    val basemapParallelism: Int = 4,
    val foreground: ForegroundConfig? = null,
) {
    /** Defaults of [AreaConfig]. */
    public companion object {
        /** The public SliceOSM service (OpenStreetMap US). */
        public const val DEFAULT_SLICE_BASE_URL: String = "https://slice.openstreetmap.us/"
    }
}

/**
 * Foreground mode for area downloads: the download worker runs as a
 * foreground service of type `dataSync` and shows an ongoing notification
 * with the download's progress and a cancel action. Enable it through
 * [AreaConfig.foreground] for large areas.
 *
 * **Why.** WorkManager stops an ordinary background run after about 10
 * minutes (the run then retries and repeats its download), and Doze/app
 * standby can defer it. A foreground run has no such limit while it lasts.
 * A city-sized area (10×10 km) takes well under a minute, so most apps do
 * not need this.
 *
 * **The app declares it (opt-in manifest entries).** The library manifest
 * does not declare foreground-service entries, so apps that never use this
 * mode do not carry a `dataSync` foreground service (which Google Play asks
 * apps to justify in the Play Console). An app that sets [AreaConfig.foreground]
 * adds to its own `AndroidManifest.xml`:
 *
 * ```xml
 * <manifest xmlns:android="http://schemas.android.com/apk/res/android"
 *     xmlns:tools="http://schemas.android.com/tools">
 *     <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
 *     <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
 *     <application>
 *         <service
 *             android:name="androidx.work.impl.foreground.SystemForegroundService"
 *             android:foregroundServiceType="dataSync"
 *             tools:node="merge" />
 *     </application>
 * </manifest>
 * ```
 *
 * (WorkManager itself already declares `FOREGROUND_SERVICE` and the service;
 * the snippet adds the `dataSync` type and its permission.) If foreground
 * mode is requested but these entries are missing, each run logs a warning
 * (tag `AreaDownloadWorker`) naming what is missing and runs as ordinary
 * background work; it never fails and never starts a service the manifest
 * does not allow. Apps on Google Play must declare the dataSync foreground
 * service use in the Play Console.
 *
 * **What the app is responsible for.**
 * - `POST_NOTIFICATIONS` on Android 13+: declare and request it if you want
 *   the notification visible. If it is denied the download still runs in
 *   the foreground; the notification is just not shown in the shade (it
 *   still appears in the system's task manager).
 * - Start the download while the app is visible (for example from a button).
 *   Android 12+ refuses to start a foreground service from the background; a
 *   run that starts in the background (a retry after backoff, a restart
 *   after process death) then continues as ordinary background work with its
 *   10-minute limit. This is logged, never a failure.
 * - Android 15+ limits `dataSync` foreground services to about 6 hours per
 *   day; past that the system stops the run, which then retries like any
 *   interrupted run.
 *
 * @property channelId Notification channel of the progress notification.
 *   Created (importance low, no sound) if it does not exist; if the app
 *   created it already, the app's settings stay.
 * @property channelName User-visible channel name, used only when the channel is created.
 * @property title Notification title (the text below it shows progress numbers only).
 * @property smallIcon Drawable resource ID of the status bar icon. Stored by
 *   resource name, so an app update that renumbers resources does not break
 *   a queued download; an icon that no longer resolves falls back to the default.
 * @property cancelLabel Label of the notification's cancel action, or null
 *   for no action. The action cancels like [AreaManager.cancel].
 */
public data class ForegroundConfig(
    val channelId: String = DEFAULT_CHANNEL_ID,
    val channelName: String = "Offline area downloads",
    val title: String = "Downloading offline area",
    val smallIcon: Int = android.R.drawable.stat_sys_download,
    val cancelLabel: String? = "Cancel",
) {
    /** Defaults of [ForegroundConfig]. */
    public companion object {
        /** Channel used when the app does not name one. */
        public const val DEFAULT_CHANNEL_ID: String = "cantino-area-downloads"
    }
}

/**
 * A published area as found on disk: OSM data, optional basemap, metadata.
 *
 * @property areaId The app-chosen area ID (see [AreaManager]).
 * @property dataFile The OSM data (`filesDir/cantino-areas/<areaId>.sqlite`);
 *   open it with [OsmStore.open].
 * @property metadata What the download recorded (bbox, snapshot age, import
 *   report, basemap). Null when the sidecar is missing or does not describe
 *   these files (areas published by hand or before the sidecar existed);
 *   the files themselves are still complete and valid.
 * @property basemapFile The published PMTiles basemap, or null when the area
 *   was downloaded with [BasemapSource.None].
 */
public class AreaInfo internal constructor(
    public val areaId: String,
    public val dataFile: File,
    public val metadata: AreaMetadata?,
    public val basemapFile: File? = null,
) {
    /**
     * The basemap as a MapLibre Native source URL
     * (`pmtiles://file:///data/.../<areaId>.pmtiles`), or null without a
     * basemap. Use it as the `url` of a vector source in the style.
     */
    public val pmtilesUrl: String? get() = basemapFile?.let { "pmtiles://file://${it.absolutePath}" }

    override fun equals(other: Any?): Boolean = this === other || other is AreaInfo &&
        areaId == other.areaId && dataFile == other.dataFile && metadata == other.metadata && basemapFile == other.basemapFile

    override fun hashCode(): Int = hash(areaId, dataFile, metadata, basemapFile)

    override fun toString(): String = "AreaInfo(areaId=$areaId, dataFile=$dataFile, metadata=$metadata, basemapFile=$basemapFile)"
}

/** How a published basemap was obtained; see [BasemapSource]. */
public enum class BasemapKind(internal val wire: String) {
    /** Downloaded as a ready-made file ([BasemapSource.Url]). */
    URL("url"),

    /** Cut on the device from a remote archive ([BasemapSource.Extract]). */
    EXTRACT("extract");

    internal companion object {
        fun fromWire(value: String) = entries.first { it.wire == value }
    }
}

/**
 * The published basemap of an area, as recorded when it was downloaded.
 *
 * @property kind How it was obtained.
 * @property sourceUrl The file ([BasemapKind.URL]) or remote archive ([BasemapKind.EXTRACT]) it came from.
 * @property fileBytes Size of the published PMTiles file.
 * @property addressedTiles Tiles addressable in the file.
 * @property minZoom Lowest zoom level in the file.
 * @property maxZoom Highest zoom level in the file.
 * @property requests HTTP requests the download made (1 for a URL download).
 * @property transferredBytes Bytes transferred (the file size for a URL download).
 */
public class BasemapMetadata internal constructor(
    public val kind: BasemapKind,
    public val sourceUrl: String,
    public val fileBytes: Long,
    public val addressedTiles: Long,
    public val minZoom: Int,
    public val maxZoom: Int,
    public val requests: Long,
    public val transferredBytes: Long,
) {
    override fun equals(other: Any?): Boolean = this === other || other is BasemapMetadata &&
        kind == other.kind && sourceUrl == other.sourceUrl && fileBytes == other.fileBytes &&
        addressedTiles == other.addressedTiles && minZoom == other.minZoom && maxZoom == other.maxZoom &&
        requests == other.requests && transferredBytes == other.transferredBytes

    override fun hashCode(): Int =
        hash(kind, sourceUrl, fileBytes, addressedTiles, minZoom, maxZoom, requests, transferredBytes)

    override fun toString(): String =
        "BasemapMetadata(kind=$kind, sourceUrl=$sourceUrl, fileBytes=$fileBytes, addressedTiles=$addressedTiles, " +
            "minZoom=$minZoom, maxZoom=$maxZoom, requests=$requests, transferredBytes=$transferredBytes)"

    // Sidecar keys are a stored format: renaming a Kotlin property must not change them.
    internal fun toJson(): JSONObject = JSONObject()
        .put("kind", kind.wire)
        .put("source_url", sourceUrl)
        .put("bytes", fileBytes)
        .put("addressed_tiles", addressedTiles)
        .put("min_zoom", minZoom)
        .put("max_zoom", maxZoom)
        .put("requests", requests)
        .put("transferred_bytes", transferredBytes)

    internal companion object {
        fun fromJson(json: JSONObject) = BasemapMetadata(
            BasemapKind.fromWire(json.getString("kind")),
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
 * What a download recorded about the area it published (the sidecar
 * `<areaId>.json`).
 *
 * @property bbox The requested area.
 * @property name The job name given to [AreaManager.download].
 * @property snapshotTimestamp SliceOSM's replication timestamp of the OSM data:
 *   show it to users as the age of the data. Null if the server did not
 *   report one or reported one that is not an RFC 3339 date-time (the
 *   sidecar file keeps the server's string as received, for diagnosis).
 * @property importedAtMillis Device clock (Unix milliseconds) when the area was published.
 * @property report The import's object counts and database size.
 * @property basemap The published basemap, null for [BasemapSource.None].
 * @property workId The [AreaManager.download] run that published the area
 *   (null for areas published by hand). Changes with every refresh, so it
 *   also serves as a version key for caches of the area's content.
 */
public class AreaMetadata internal constructor(
    public val bbox: Bbox,
    public val name: String,
    /**
     * The server's timestamp string as received (SliceOSM status
     * `Timestamp`); stored in the sidecar unchanged. [snapshotTimestamp] is
     * its parsed form.
     */
    internal val snapshotTimestampRaw: String?,
    public val importedAtMillis: Long,
    public val report: ImportReport,
    public val basemap: BasemapMetadata? = null,
    public val workId: UUID? = null,
) {
    public val snapshotTimestamp: Instant? = parseSnapshotTimestamp(snapshotTimestampRaw)

    // Value semantics over the public view: two sidecars that spell the same
    // instant differently describe the same snapshot.
    override fun equals(other: Any?): Boolean = this === other || other is AreaMetadata &&
        bbox == other.bbox && name == other.name && snapshotTimestamp == other.snapshotTimestamp &&
        importedAtMillis == other.importedAtMillis && report == other.report &&
        basemap == other.basemap && workId == other.workId

    override fun hashCode(): Int = hash(bbox, name, snapshotTimestamp, importedAtMillis, report, basemap, workId)

    override fun toString(): String =
        "AreaMetadata(bbox=$bbox, name=$name, snapshotTimestamp=$snapshotTimestamp, importedAtMillis=$importedAtMillis, " +
            "report=$report, basemap=$basemap, workId=$workId)"

    internal fun toJson(): JSONObject = JSONObject()
        .put("bbox", bbox.toJson())
        .put("name", name)
        .put("snapshot_timestamp", snapshotTimestampRaw ?: JSONObject.NULL)
        .put("imported_at_millis", importedAtMillis)
        .put("report", report.toJson())
        .put("basemap", basemap?.toJson() ?: JSONObject.NULL)
        .put("work_id", workId?.toString() ?: JSONObject.NULL)

    internal companion object {
        fun fromJson(json: JSONObject) = AreaMetadata(
            Bbox.fromJson(json.getJSONObject("bbox")),
            json.getString("name"),
            if (json.isNull("snapshot_timestamp")) null else json.getString("snapshot_timestamp"),
            json.getLong("imported_at_millis"),
            ImportReport.fromJson(json.getJSONObject("report")),
            json.optJSONObject("basemap")?.let { BasemapMetadata.fromJson(it) },
            if (json.isNull("work_id")) null else UUID.fromString(json.getString("work_id")),
        )

        /**
         * RFC 3339 (SliceOSM sends `2026-10-03T20:30:01Z`): `Z` or a numeric
         * offset, optional fraction, `T`/`t` or a space between date and
         * time. Anything else (or null) is null, never an exception: a
         * malformed server timestamp must not fail a download or hide an area.
         */
        fun parseSnapshotTimestamp(value: String?): Instant? {
            if (value.isNullOrBlank()) return null
            val normalized = value.trim().uppercase(java.util.Locale.ROOT).let {
                if (it.length > 10 && it[10] == ' ') it.substring(0, 10) + "T" + it.substring(11) else it
            }
            return try {
                OffsetDateTime.parse(normalized).toInstant()
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }
}

/**
 * Lifecycle of one area's download, as observed through [AreaManager.state].
 * Sealed: a `when` over it is exhaustive.
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
public sealed interface AreaState {
    /**
     * No download is known.
     *
     * @property published The area on disk, if any.
     */
    public class Idle internal constructor(public val published: AreaInfo?) : AreaState {
        override fun equals(other: Any?): Boolean = this === other || other is Idle && published == other.published
        override fun hashCode(): Int = hash(published)
        override fun toString(): String = "Idle(published=$published)"
    }

    /**
     * Enqueued and waiting: for network/storage constraints, or for a backoff
     * after a transient failure.
     *
     * @property previousRuns Runs that already happened (0 before the first).
     */
    public class Queued internal constructor(public val previousRuns: Int) : AreaState {
        override fun equals(other: Any?): Boolean = this === other || other is Queued && previousRuns == other.previousRuns
        override fun hashCode(): Int = previousRuns
        override fun toString(): String = "Queued(previousRuns=$previousRuns)"
    }

    /** Submitting the job to SliceOSM, or re-attaching to the job of an interrupted run. */
    public data object Submitting : AreaState

    /**
     * SliceOSM is cutting the extract.
     *
     * @property fraction Progress 0..1, or null before the server reports totals.
     */
    public class Slicing internal constructor(public val fraction: Double?) : AreaState {
        override fun equals(other: Any?): Boolean = this === other || other is Slicing && fraction == other.fraction
        override fun hashCode(): Int = hash(fraction)
        override fun toString(): String = "Slicing(fraction=$fraction)"
    }

    /**
     * Downloading the OSM data (PBF).
     *
     * @property bytes Bytes downloaded so far.
     * @property totalBytes Size of the download, or null when the server sends no length.
     */
    public class Downloading internal constructor(public val bytes: Long, public val totalBytes: Long?) : AreaState {
        override fun equals(other: Any?): Boolean = this === other || other is Downloading &&
            bytes == other.bytes && totalBytes == other.totalBytes
        override fun hashCode(): Int = hash(bytes, totalBytes)
        override fun toString(): String = "Downloading(bytes=$bytes, totalBytes=$totalBytes)"
    }

    /**
     * Importing into SQLite, into a staging file: nothing is published yet.
     * The native import itself cannot be interrupted, but a cancel during it
     * is honored when it returns (the staged import is discarded).
     */
    public data object Importing : AreaState

    /**
     * Downloading the basemap ([BasemapSource.Url] or [BasemapSource.Extract])
     * after the OSM data was imported (staged, not yet published). [bytes] is
     * the progress in [phase]; [totalBytes] is null while unknown (directory
     * phase, or a server sending no length).
     *
     * @property phase What the basemap step is doing.
     * @property bytes Bytes transferred so far in [phase].
     * @property totalBytes Bytes to transfer in [phase], or null while unknown.
     */
    public class Basemap internal constructor(
        public val phase: BasemapPhase,
        public val bytes: Long,
        public val totalBytes: Long?,
    ) : AreaState {
        override fun equals(other: Any?): Boolean = this === other || other is Basemap &&
            phase == other.phase && bytes == other.bytes && totalBytes == other.totalBytes
        override fun hashCode(): Int = hash(phase, bytes, totalBytes)
        override fun toString(): String = "Basemap(phase=$phase, bytes=$bytes, totalBytes=$totalBytes)"
    }

    /**
     * Published: OSM data and, if requested, the basemap. Open [area]'s
     * [AreaInfo.dataFile] with [OsmStore.open]; already-open stores keep the
     * old snapshot. A cancel that arrives once publishing has begun is
     * ignored and the run still ends here. The import report and snapshot
     * timestamp are in [area]'s [AreaInfo.metadata].
     *
     * @property area The area this run published.
     */
    public class Ready internal constructor(public val area: AreaInfo) : AreaState {
        override fun equals(other: Any?): Boolean = this === other || other is Ready && area == other.area
        override fun hashCode(): Int = area.hashCode()
        override fun toString(): String = "Ready(area=$area)"
    }

    /**
     * Gave up. [message] is a developer-facing description (not localized).
     * [retryable] is true for transient causes (network, server) that
     * exhausted their retries: calling [AreaManager.download] again later may
     * succeed. False means the request or the data is bad.
     *
     * @property message Developer-facing description of the failure (not localized).
     * @property retryable True for a transient cause that exhausted its retries.
     */
    public class Failed internal constructor(public val message: String, public val retryable: Boolean) : AreaState {
        override fun equals(other: Any?): Boolean = this === other || other is Failed &&
            message == other.message && retryable == other.retryable
        override fun hashCode(): Int = hash(message, retryable)
        override fun toString(): String = "Failed(message=$message, retryable=$retryable)"
    }

    /**
     * Cancelled by [AreaManager.cancel] before publishing began: nothing of
     * this run was published. (A run replaced by a newer
     * [AreaManager.download] of the same area is not reported: the state
     * follows the new run.)
     */
    public data object Cancelled : AreaState
}
