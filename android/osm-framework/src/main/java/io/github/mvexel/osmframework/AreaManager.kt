package io.github.mvexel.osmframework

import android.content.Context
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
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Downloads, refreshes and locates the offline area of an app.
 *
 * Model: an app names each area with an ID (`[A-Za-z0-9_-]{1,64}`, e.g.
 * `"city"`). Each ID has at most one published area file, at
 * `filesDir/osm-areas/<areaId>.sqlite`. A [download] fetches a fresh SliceOSM
 * extract for a bbox and, only once it imported successfully, atomically
 * replaces that file. There is no incremental update and no merging of
 * overlapping areas: a refresh is a full re-download.
 *
 * Downloads run in WorkManager (unique work per area ID), so they survive the
 * app leaving the foreground and process death, wait for a network
 * connection and for storage not being low, and retry transient failures with
 * backoff. Observe progress with [state].
 *
 * Guarantees for callers:
 * - Until a download reaches [AreaState.Ready], the previously published area
 *   is untouched: failure, cancellation and process death never damage or
 *   remove it.
 * - Replacement is an atomic rename. An [OsmStore] that is already open keeps
 *   reading its old snapshot (the old file stays alive while open); close and
 *   reopen it after [AreaState.Ready] to see the new data.
 * - The library manifest adds INTERNET and ACCESS_NETWORK_STATE to the app.
 *
 * [config] applies to downloads started by this instance; it travels with the
 * work request, so a run restarted after process death uses the same config.
 */
class AreaManager @JvmOverloads constructor(context: Context, private val config: AreaConfig = AreaConfig()) {
    private val context = context.applicationContext
    private val storage = AreaStorage(this.context)
    private val workManager get() = WorkManager.getInstance(context)

    /**
     * Starts downloading [bbox] as area [areaId]. A download already running
     * or queued for the same ID is cancelled and replaced (its partial data is
     * discarded; the published area is unaffected). [name] is the job name
     * shown by SliceOSM. Returns the WorkManager ID of the new run.
     *
     * [bbox] must be valid and not cross the dateline; invalid boxes fail the
     * work with a non-retryable [AreaState.Failed].
     */
    @JvmOverloads
    fun download(areaId: String, bbox: Bbox, name: String = areaId): UUID {
        AreaStorage.requireValidAreaId(areaId)
        val request = OneTimeWorkRequestBuilder<AreaDownloadWorker>()
            .setInputData(AreaDownloadWorker.DownloadRequest(areaId, bbox, name, config).toData())
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

    /** Cancels a running or queued download of [areaId]. The published area stays. No-op if none. */
    fun cancel(areaId: String) {
        AreaStorage.requireValidAreaId(areaId)
        workManager.cancelUniqueWork(workName(areaId))
    }

    /**
     * Current download state of [areaId], updated as it changes. Collect it
     * from any coroutine; it does disk reads on [Dispatchers.IO].
     */
    fun state(areaId: String): Flow<AreaState> {
        AreaStorage.requireValidAreaId(areaId)
        return workManager.getWorkInfosForUniqueWorkFlow(workName(areaId))
            // REPLACE deletes the replaced run, so there is normally one entry;
            // prefer an unfinished one should two ever be visible.
            .map { infos -> toState(areaId, infos.firstOrNull { !it.state.isFinished } ?: infos.firstOrNull()) }
            .distinctUntilChanged()
            .flowOn(Dispatchers.IO)
    }

    /** The published area file, or null if none has been downloaded. Open it with [OsmStore.open]. */
    fun areaFile(areaId: String): File? {
        AreaStorage.requireValidAreaId(areaId)
        return storage.areaFile(areaId).takeIf { it.isFile }
    }

    /** The published area with its metadata (snapshot age, bbox, import report), or null. Reads disk. */
    fun publishedArea(areaId: String): AreaInfo? {
        AreaStorage.requireValidAreaId(areaId)
        return storage.published(areaId)
    }

    private fun toState(areaId: String, info: WorkInfo?): AreaState {
        if (info == null) return AreaState.Idle(storage.published(areaId))
        return when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> AreaState.Queued(info.runAttemptCount)
            WorkInfo.State.RUNNING -> running(info)
            WorkInfo.State.SUCCEEDED -> {
                val metadata = info.outputData.getString(AreaDownloadWorker.KEY_METADATA)
                    ?.let { AreaMetadata.fromJson(JSONObject(it)) }
                if (metadata == null) {
                    AreaState.Idle(storage.published(areaId))
                } else {
                    AreaState.Ready(
                        metadata.report,
                        metadata.snapshotTimestamp,
                        AreaInfo(areaId, storage.areaFile(areaId), metadata),
                    )
                }
            }
            WorkInfo.State.FAILED -> AreaState.Failed(
                info.outputData.getString(AreaDownloadWorker.KEY_MESSAGE) ?: "download failed",
                info.outputData.getBoolean(AreaDownloadWorker.KEY_RETRYABLE, false),
            )
            WorkInfo.State.CANCELLED -> AreaState.Cancelled
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
            // Started but no progress published yet.
            else -> AreaState.Submitting
        }
    }

    private companion object {
        const val TAG = "osm-area-download"

        fun workName(areaId: String) = "$TAG:$areaId"
    }
}
