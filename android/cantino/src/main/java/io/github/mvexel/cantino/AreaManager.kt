package io.github.mvexel.cantino

import android.content.Context
import android.content.res.Resources
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Downloads, refreshes and locates the offline areas of an app.
 *
 * Model: an app names each area with an ID: 1 to 64 characters from
 * `[A-Za-z0-9_-]` (e.g. `"city"`); IDs become file names, and every method
 * throws [IllegalArgumentException] for any other ID. Each ID has at most one
 * published area: OSM data at `filesDir/cantino-areas/<areaId>.sqlite` and, when
 * requested with a [BasemapSource], a PMTiles basemap at
 * `filesDir/cantino-areas/<areaId>.pmtiles`. A [download] fetches a fresh
 * SliceOSM extract for a bbox (and the basemap) and, only once every part is
 * ready, replaces the published area in one commit. There is no incremental
 * update and no merging of overlapping areas: a refresh is a full
 * re-download of every part.
 *
 * Downloads run in WorkManager (unique work per area ID), so they survive the
 * app leaving the foreground and process death, wait for a network
 * connection and for storage not being low, and retry transient failures with
 * backoff. Observe progress with [state]. Large areas can opt into a
 * foreground service ([AreaConfig.foreground]).
 *
 * Guarantees for callers:
 * - Until a download reaches [AreaState.Ready], the previously published area
 *   is untouched: failure, cancellation and process death never damage or
 *   remove it.
 * - Data, basemap and metadata are replaced together: a reader of this class
 *   sees either the complete old area or the complete new one. An
 *   [OsmStore] that is already open keeps reading its old snapshot (the old
 *   file stays alive while open); close and reopen it after
 *   [AreaState.Ready] to see the new data.
 * - The library manifest adds INTERNET and ACCESS_NETWORK_STATE to the app,
 *   nothing else of its own. Foreground mode needs manifest entries the app
 *   adds itself (see [ForegroundConfig]).
 *
 * Threading: [download], [cancel] and [state] are cheap and may be called
 * from any thread, including the main thread. [dataFile], [basemapFile] and
 * [publishedArea] read the disk (and may wait a few milliseconds for a commit
 * in progress): call them off the main thread. Instances are lightweight and
 * stateless; several may coexist (all state lives in WorkManager and on disk).
 * All of an app's area access must happen in one process.
 *
 * [config] applies to downloads started by this instance; it travels with the
 * work request, so a run restarted after process death uses the same config.
 */
public class AreaManager @JvmOverloads constructor(context: Context, private val config: AreaConfig = AreaConfig()) {
    private val context = context.applicationContext
    private val storage = AreaStorage(this.context)
    private val workManager get() = WorkManager.getInstance(context)

    /**
     * Starts downloading [bbox] as area [areaId] and returns the WorkManager
     * ID of the new run (the same ID ends up in [AreaMetadata.workId] once
     * it publishes).
     *
     * A download already running or queued for the same ID is cancelled and
     * replaced (its partial data is discarded; the published area is
     * unaffected). [name] is the job name shown by SliceOSM. [basemap] opts
     * into an offline basemap for the same bbox (default: none; a refresh
     * without one removes a previous basemap).
     *
     * [bbox] must be valid and not cross the antimeridian (see [Bbox]); an
     * invalid box is not rejected here but fails the run with a
     * non-retryable [AreaState.Failed]. Throws [IllegalArgumentException] for
     * an invalid [areaId] or a [ForegroundConfig.smallIcon] that is not a
     * resource.
     */
    @JvmOverloads
    public fun download(areaId: String, bbox: Bbox, name: String = areaId, basemap: BasemapSource = BasemapSource.None): UUID {
        AreaStorage.requireValidAreaId(areaId)
        // Resource IDs can change between app versions; a queued run keeps the icon's name.
        val iconName = config.foreground?.let { foreground ->
            try {
                context.resources.getResourceName(foreground.smallIcon)
            } catch (error: Resources.NotFoundException) {
                throw IllegalArgumentException("ForegroundConfig.smallIcon ${foreground.smallIcon} is not a resource", error)
            }
        }
        val request = OneTimeWorkRequestBuilder<AreaDownloadWorker>()
            .setInputData(AreaDownloadWorker.DownloadRequest(areaId, bbox, name, config, basemap, iconName).toData())
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, config.backoffDelayMillis, TimeUnit.MILLISECONDS)
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(workName(areaId), ExistingWorkPolicy.REPLACE, request)
        return request.id
    }

    /**
     * Cancels a running or queued download of [areaId]. The published area
     * stays. No-op if none. A run already publishing (a few milliseconds of
     * renames) finishes and is reported as [AreaState.Ready]. Returns
     * immediately; the state turns [AreaState.Cancelled] shortly after.
     */
    public fun cancel(areaId: String) {
        AreaStorage.requireValidAreaId(areaId)
        workManager.cancelUniqueWork(workName(areaId))
    }

    /**
     * Download state of [areaId]: emits the current state on collection, then
     * every change (consecutive duplicates are dropped). It never completes;
     * cancel the collecting coroutine to stop. Collect it from any
     * coroutine; disk reads run on [Dispatchers.IO].
     */
    public fun state(areaId: String): Flow<AreaState> {
        AreaStorage.requireValidAreaId(areaId)
        return workManager.getWorkInfosForUniqueWorkFlow(workName(areaId))
            // REPLACE deletes the replaced run, so there is normally one entry;
            // prefer an unfinished one should two ever be visible.
            .map { infos -> toState(areaId, infos.firstOrNull { !it.state.isFinished } ?: infos.firstOrNull()) }
            .distinctUntilChanged()
            .flowOn(Dispatchers.IO)
    }

    /**
     * The published OSM data file of [areaId], or null if none has been
     * published. Open it with [OsmStore.open]. Reads disk.
     */
    public fun dataFile(areaId: String): File? {
        AreaStorage.requireValidAreaId(areaId)
        storage.recover(areaId)
        return storage.dataFile(areaId).takeIf { it.isFile }
    }

    /**
     * The published PMTiles basemap of [areaId], or null (none requested, or
     * no area). See [AreaInfo.pmtilesUrl] for the MapLibre URL. Reads disk.
     */
    public fun basemapFile(areaId: String): File? {
        AreaStorage.requireValidAreaId(areaId)
        storage.recover(areaId)
        return storage.basemapFile(areaId).takeIf { it.isFile }
    }

    /**
     * The published area of [areaId] with its files and metadata (snapshot
     * age, bbox, import report), or null if none is published. Reads disk.
     */
    public fun publishedArea(areaId: String): AreaInfo? {
        AreaStorage.requireValidAreaId(areaId)
        return storage.published(areaId)
    }

    private fun toState(areaId: String, info: WorkInfo?): AreaState {
        if (info == null) return AreaState.Idle(storage.published(areaId))
        return when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> AreaState.Queued(info.runAttemptCount)
            WorkInfo.State.RUNNING -> running(info)
            // Whether a finished run published is decided by the published
            // sidecar's work ID, not by WorkManager's state: a cancel that
            // arrives after the commit point leaves the work CANCELLED although
            // the run published (and the run's own result is ignored).
            // storage.published() waits for a commit in progress (area lock),
            // so a run that passed its commit point is always seen as such.
            WorkInfo.State.SUCCEEDED, WorkInfo.State.CANCELLED -> {
                val published = storage.published(areaId)
                val metadata = published?.metadata
                when {
                    metadata != null && metadata.workId == info.id ->
                        AreaState.Ready(published)
                    info.state == WorkInfo.State.CANCELLED -> AreaState.Cancelled
                    // Succeeded but replaced since (or pruned metadata): what is on disk.
                    else -> AreaState.Idle(published)
                }
            }
            WorkInfo.State.FAILED -> AreaState.Failed(
                info.outputData.getString(AreaDownloadWorker.KEY_MESSAGE) ?: "download failed",
                info.outputData.getBoolean(AreaDownloadWorker.KEY_RETRYABLE, false),
            )
        }
    }

    private fun running(info: WorkInfo): AreaState {
        val progress = info.progress
        return when (progress.getString(AreaDownloadWorker.KEY_PHASE)) {
            AreaDownloadWorker.PHASE_SLICING ->
                AreaState.Slicing(progress.getDouble(AreaDownloadWorker.KEY_FRACTION, Double.NaN).takeUnless { it.isNaN() })
            AreaDownloadWorker.PHASE_DOWNLOADING -> AreaState.Downloading(
                progress.getLong(AreaDownloadWorker.KEY_BYTES, 0),
                progress.getLong(AreaDownloadWorker.KEY_TOTAL, -1).takeIf { it >= 0 },
            )
            AreaDownloadWorker.PHASE_IMPORTING -> AreaState.Importing
            AreaDownloadWorker.PHASE_BASEMAP -> AreaState.Basemap(
                progress.getString(AreaDownloadWorker.KEY_BASEMAP_PHASE)
                    ?.let { BasemapPhase.valueOf(it) } ?: BasemapPhase.DIRECTORIES,
                progress.getLong(AreaDownloadWorker.KEY_BYTES, 0),
                progress.getLong(AreaDownloadWorker.KEY_TOTAL, -1).takeIf { it >= 0 },
            )
            // Started but no progress published yet.
            else -> AreaState.Submitting
        }
    }

    private companion object {
        const val TAG = "cantino-area-download"

        fun workName(areaId: String) = "$TAG:$areaId"
    }
}
