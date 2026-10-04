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
 * Why a download ended in [AreaState.Failed], grouped by what the app (or
 * its user) can do about it. Read it from [AreaState.Failed.reason];
 * [AreaState.Failed.retryable] says whether the same request may simply be
 * retried later.
 *
 * | Reason | Typical causes | Retryable |
 * | --- | --- | --- |
 * | [NETWORK] | No connection, timeouts, a dropped or truncated transfer | yes, after retries ran out |
 * | [SERVER] | SliceOSM or basemap host: 5xx, 408, 429, a 404 for a vanished job, an unparseable answer, a job that never finishes, no HTTP range support | mostly yes |
 * | [INVALID_REQUEST] | Invalid bbox or name, other HTTP 4xx (a 400 on submit, a basemap URL that 404s), zooms the archive lacks | no |
 * | [STORAGE] | Disk full or a staging file that cannot be written; publishing interrupted by an I/O error | no (yes for publishing) |
 * | [INVALID_DATA] | A PBF that fails to import (corrupt, truncated, not a snapshot), a basemap that is not a valid or supported PMTiles v3 archive | no |
 * | [UNKNOWN] | An unexpected error (a bug in Cantino), or a failure recorded by Cantino 0.1 | no |
 *
 * Low storage before a run starts does not fail it: WorkManager waits
 * ([AreaState.Queued]) until storage is no longer low.
 */
public enum class FailureReason {
    /** No connection, DNS or connect/read timeout, a connection dropped mid-transfer. */
    NETWORK,

    /**
     * The server failed or misbehaved: HTTP 5xx, 408 or 429, a SliceOSM job
     * that vanished (404) or never finished, an answer that does not parse,
     * a download size that disagrees with the job, a basemap host that
     * ignores `Range` or answers with the wrong range.
     */
    SERVER,

    /**
     * The request is invalid and fails again unchanged: an invalid bbox (see
     * [Bbox]) or name, any other HTTP 4xx (a 400 from SliceOSM on submit, a
     * basemap URL that is 404 or forbidden), requested zooms the basemap
     * archive does not have.
     */
    INVALID_REQUEST,

    /**
     * Device storage: the disk is full or a download/staging file cannot be
     * written, or the import cannot write its database. Free space, then
     * download again.
     */
    STORAGE,

    /**
     * The downloaded data is unusable: an OSM extract that fails to import
     * (corrupt, truncated, unsorted or duplicate IDs) or a basemap that is
     * not a valid PMTiles v3 archive (or uses a feature the extract engine
     * does not support).
     */
    INVALID_DATA,

    /**
     * Not classified. Two real paths lead here and fit no other value: an
     * unexpected exception in the download worker (a bug in Cantino, worth
     * reporting with [AreaState.Failed.message]), and a failed run recorded
     * by Cantino 0.1, which stored no reason, read after upgrading.
     */
    UNKNOWN,
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
 *
 * Every state except [Idle] carries [runId], the ID of the run it belongs
 * to (the value [AreaManager.download] returned). It is the first
 * constructor property of each subtype and takes part in `equals`.
 */
public sealed interface AreaState {
    /**
     * The WorkManager ID of the run this state belongs to: the same
     * [UUID] [AreaManager.download] returned for it. Null only for [Idle]
     * (no run known); every other state carries it.
     *
     * [AreaManager.state] follows the *area*, not one run: right after
     * [AreaManager.download] it can still emit the previous run's final
     * state. Match [runId] to wait for your own download:
     *
     * ```
     * val runId = areaManager.download("home", bbox)
     * val last = areaManager.state("home")
     *     .filter { it.runId == runId && it.isTerminal }
     *     .first()
     * ```
     */
    public val runId: UUID?

    /**
     * True for the states a run ends in: [Ready], [Failed] and [Cancelled].
     * [Idle] is not terminal (no run), and neither is [Queued] after a
     * transient failure: the same run resumes. Combine with [runId] to wait
     * for your own download (see there).
     */
    public val isTerminal: Boolean
        get() = this is Ready || this is Failed || this is Cancelled

    /**
     * No download is known.
     *
     * @property published The area on disk, if any.
     */
    public class Idle internal constructor(public val published: AreaInfo?) : AreaState {
        /** Always null: no run is known. */
        override val runId: UUID? get() = null
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
    public class Queued internal constructor(override val runId: UUID, public val previousRuns: Int) : AreaState {
        override fun equals(other: Any?): Boolean = this === other || other is Queued &&
            runId == other.runId && previousRuns == other.previousRuns
        override fun hashCode(): Int = hash(runId, previousRuns)
        override fun toString(): String = "Queued(runId=$runId, previousRuns=$previousRuns)"
    }

    /** Submitting the job to SliceOSM, or re-attaching to the job of an interrupted run. */
    public class Submitting internal constructor(override val runId: UUID) : AreaState {
        override fun equals(other: Any?): Boolean = this === other || other is Submitting && runId == other.runId
        override fun hashCode(): Int = runId.hashCode()
        override fun toString(): String = "Submitting(runId=$runId)"
    }

    /**
     * SliceOSM is cutting the extract.
     *
     * @property fraction Progress 0..1, or null before the server reports totals.
     */
    public class Slicing internal constructor(override val runId: UUID, public val fraction: Double?) : AreaState {
        override fun equals(other: Any?): Boolean = this === other || other is Slicing &&
            runId == other.runId && fraction == other.fraction
        override fun hashCode(): Int = hash(runId, fraction)
        override fun toString(): String = "Slicing(runId=$runId, fraction=$fraction)"
    }

    /**
     * Downloading the OSM data (PBF).
     *
     * @property bytes Bytes downloaded so far.
     * @property totalBytes Size of the download, or null when the server sends no length.
     */
    public class Downloading internal constructor(
        override val runId: UUID,
        public val bytes: Long,
        public val totalBytes: Long?,
    ) : AreaState {
        override fun equals(other: Any?): Boolean = this === other || other is Downloading &&
            runId == other.runId && bytes == other.bytes && totalBytes == other.totalBytes
        override fun hashCode(): Int = hash(runId, bytes, totalBytes)
        override fun toString(): String = "Downloading(runId=$runId, bytes=$bytes, totalBytes=$totalBytes)"
    }

    /**
     * Importing into SQLite, into a staging file: nothing is published yet.
     * The native import itself cannot be interrupted, but a cancel during it
     * is honored when it returns (the staged import is discarded).
     */
    public class Importing internal constructor(override val runId: UUID) : AreaState {
        override fun equals(other: Any?): Boolean = this === other || other is Importing && runId == other.runId
        override fun hashCode(): Int = runId.hashCode()
        override fun toString(): String = "Importing(runId=$runId)"
    }

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
        override val runId: UUID,
        public val phase: BasemapPhase,
        public val bytes: Long,
        public val totalBytes: Long?,
    ) : AreaState {
        override fun equals(other: Any?): Boolean = this === other || other is Basemap &&
            runId == other.runId && phase == other.phase && bytes == other.bytes && totalBytes == other.totalBytes
        override fun hashCode(): Int = hash(runId, phase, bytes, totalBytes)
        override fun toString(): String = "Basemap(runId=$runId, phase=$phase, bytes=$bytes, totalBytes=$totalBytes)"
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
    public class Ready internal constructor(override val runId: UUID, public val area: AreaInfo) : AreaState {
        override fun equals(other: Any?): Boolean = this === other || other is Ready &&
            runId == other.runId && area == other.area
        override fun hashCode(): Int = hash(runId, area)
        override fun toString(): String = "Ready(runId=$runId, area=$area)"
    }

    /**
     * Gave up. [reason] says why, by what the app can do about it (branch on
     * it, e.g. to tell the user to free space or check the connection);
     * [message] is a developer-facing description (not localized, not for
     * parsing). [retryable] is true for transient causes (network, server)
     * that exhausted their retries: calling [AreaManager.download] again
     * later may succeed as is. False means the same request fails again
     * until something changes: the request ([FailureReason.INVALID_REQUEST]),
     * the source data ([FailureReason.INVALID_DATA]) or free storage
     * ([FailureReason.STORAGE]).
     *
     * @property message Developer-facing description of the failure (not localized).
     * @property retryable True for a transient cause that exhausted its retries.
     * @property reason Category of the failure; [FailureReason.UNKNOWN] only
     *   for unexpected errors and failures recorded before Cantino 0.2.
     */
    public class Failed internal constructor(
        override val runId: UUID,
        public val message: String,
        public val retryable: Boolean,
        public val reason: FailureReason,
    ) : AreaState {
        override fun equals(other: Any?): Boolean = this === other || other is Failed &&
            runId == other.runId && message == other.message && retryable == other.retryable &&
            reason == other.reason
        override fun hashCode(): Int = hash(runId, message, retryable, reason)
        override fun toString(): String =
            "Failed(runId=$runId, message=$message, retryable=$retryable, reason=$reason)"
    }

    /**
     * Cancelled by [AreaManager.cancel] before publishing began: nothing of
     * this run was published. (A run replaced by a newer
     * [AreaManager.download] of the same area is not reported: the state
     * follows the new run.)
     */
    public class Cancelled internal constructor(override val runId: UUID) : AreaState {
        override fun equals(other: Any?): Boolean = this === other || other is Cancelled && runId == other.runId
        override fun hashCode(): Int = runId.hashCode()
        override fun toString(): String = "Cancelled(runId=$runId)"
    }
}
