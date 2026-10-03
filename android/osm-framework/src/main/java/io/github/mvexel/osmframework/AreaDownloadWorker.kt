package io.github.mvexel.osmframework

import android.content.Context
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import org.json.JSONObject

/**
 * One run of an area download: submit (or resume) a SliceOSM job, poll it,
 * download the PBF, import it into a staging directory, download the
 * basemap (if requested) next to it, then publish everything in one commit
 * ([AreaStorage.commit]).
 *
 * Lifecycle and failure behavior:
 * - **Previous area.** Nothing touches the published files (data, basemap,
 *   sidecar) until the commit point. Every failure, cancellation or process
 *   death before it leaves the old area intact and openable; after it, the
 *   new area is published completely (a dead process's commit is finished by
 *   the next [AreaStorage.recover]).
 * - **Staging.** The PBF and basemap range files go to `noBackupFilesDir`;
 *   the imported area and the basemap are built in a per-run staging
 *   directory beside the published files. All of it is deleted when the run
 *   ends without committing (`finally`); leftovers of killed processes are
 *   deleted when the next run starts.
 * - **Cancellation** (by [AreaManager.cancel], a REPLACE, lost constraints or
 *   the system) cancels this coroutine: HTTP is aborted via [SliceHttp]'s
 *   watchdog. It is honored at every point up to the commit point, including
 *   during the import: the native import cannot be interrupted, but its
 *   staged result is discarded when it returns. The check before the commit
 *   point runs under the area lock and also consults WorkManager's own record
 *   of the run (cancel marks it CANCELLED before the coroutine learns of it),
 *   so [AreaManager.state] can never say Cancelled for a run that published:
 *   once the commit point has passed, cancel is ignored and the state reads
 *   [AreaState.Ready].
 * - **Process death / constraint loss.** WorkManager re-runs the same work
 *   ID. The job checkpoint (see [AreaStorage.readCheckpoint]) lets the new
 *   run resume polling the job already submitted instead of creating a new
 *   one. Downloads (PBF, basemap) restart from scratch. A run that dies
 *   after its commit point finds its own area published and succeeds at once.
 * - **Retries.** Transient HTTP failures are retried inline, then via
 *   `Result.retry()` (WorkManager backoff, checkpoint kept). Permanent
 *   failures end the work as failed and clear the checkpoint. A retried run
 *   repeats the data download and import (the staged result is not kept).
 * - **Execution limit.** WorkManager stops a run after 10 minutes; the slicing
 *   wait is capped below that and a stopped run resumes from the checkpoint.
 *   A large basemap extract can exceed the limit (see TODO: setForeground).
 */
internal class AreaDownloadWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val request = DownloadRequest.fromData(inputData)
        val config = request.config
        val storage = AreaStorage(applicationContext)
        val http = SliceHttp(config.connectTimeoutMillis, config.readTimeoutMillis)
        storage.prepare(request.areaId)
        // A commit of this very run may have passed its commit point before
        // the process died: then the area is published and the run is done.
        storage.published(request.areaId)?.metadata?.takeIf { it.workId == id.toString() }?.let { metadata ->
            storage.clearCheckpoint(request.areaId, id)
            return Result.success(workDataOf(KEY_METADATA to metadata.toJson().toString()))
        }
        val staging = storage.stagingFile(request.areaId, id)
        storage.deleteStaleStaging(request.areaId, keep = staging)
        storage.prepareStaging(request.areaId, id)
        try {
            val (job, progress) = sliceJob(request, storage, http)

            report(PHASE_DOWNLOADING, bytes = 0, total = progress.sizeBytes)
            val bytes = retryingInline(config) {
                http.download(job.downloadUrl, staging, onProgress = throttled { bytes, total ->
                    report(PHASE_DOWNLOADING, bytes = bytes, total = total ?: progress.sizeBytes)
                })
            }
            if (progress.sizeBytes != null && bytes != progress.sizeBytes) {
                throw DownloadFailure.Transient("download size $bytes differs from SliceOSM's ${progress.sizeBytes}")
            }

            report(PHASE_IMPORTING)
            val importReport = withContext(Dispatchers.IO) {
                try {
                    // Into the staging directory: nothing is published here.
                    OsmStore.importArea(staging.path, storage.stagedArea(request.areaId, id).path, config.importOptions)
                } catch (error: OsmFrameworkException) {
                    // Bad or truncated PBF, or a full disk. The old area is intact.
                    throw DownloadFailure.Permanent("import failed: ${error.message}", error)
                }
            }
            AreaTestHooks.afterImport?.invoke()
            staging.delete() // free the PBF's space before the basemap download
            coroutineContext.ensureActive() // a cancel during the import ends the run here

            val basemap = basemap(request, storage, http)
            val metadata = AreaMetadata(
                request.bbox,
                request.name,
                progress.timestamp,
                System.currentTimeMillis(),
                importReport,
                basemap,
                id.toString(),
            )
            storage.writeStagedMetadata(request.areaId, id, metadata)
            publish(request, storage, hasBasemap = basemap != null)
            storage.clearCheckpoint(request.areaId, id)
            return Result.success(workDataOf(KEY_METADATA to metadata.toJson().toString()))
        } catch (failure: DownloadFailure.Permanent) {
            storage.clearCheckpoint(request.areaId, id)
            return failed(failure.message, retryable = false)
        } catch (failure: DownloadFailure) {
            // Transient: keep the checkpoint so the next run resumes the same
            // job. A job whose file vanished (JobGone from the download) must
            // not be resumed: drop the checkpoint so the next run resubmits.
            if (failure is DownloadFailure.JobGone) storage.clearCheckpoint(request.areaId, id)
            return if (runAttemptCount + 1 < config.maxRunAttempts) {
                Result.retry()
            } else {
                storage.clearCheckpoint(request.areaId, id)
                failed(failure.message, retryable = true)
            }
        } finally {
            // Also runs on cancellation. A killed process skips this; the next
            // run's deleteStaleStaging / prepareStaging cover that.
            staging.delete()
            storage.discardStaging(request.areaId, id)
        }
    }

    /**
     * The commit: see [AreaStorage.commit]. The last cancellation check runs
     * under the area lock; past it, the run publishes and reports success
     * even if a cancel arrives meanwhile ([AreaManager.state] then reads the
     * published sidecar's work ID and reports Ready).
     */
    private suspend fun publish(request: DownloadRequest, storage: AreaStorage, hasBasemap: Boolean) {
        val job = coroutineContext[Job]
        val workManager = WorkManager.getInstance(applicationContext)
        // NonCancellable: once the commit point has passed, a cancel must not
        // turn the return from this block into a CancellationException (the
        // run published; it finishes as success). The check before the
        // commit point uses the outer job, so cancellation still counts there.
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                storage.commit(request.areaId, id, hasBasemap) {
                    job?.ensureActive()
                    // cancelUniqueWork records CANCELLED before it stops the
                    // worker; checking the record here closes that window.
                    val state = workManager.getWorkInfoById(id).get()?.state
                    if (isStopped || state == null || state.isFinished) {
                        throw CancellationException("run $id was cancelled before publishing")
                    }
                }
            } catch (error: java.io.IOException) {
                // Past the commit point: the next run's recover() finishes
                // the renames and then finds its area published.
                throw DownloadFailure.Transient("publishing interrupted: ${error.message}", error)
            }
        }
    }

    /**
     * Downloads the requested basemap into the staging directory. Returns
     * its metadata, or null for [BasemapSource.None].
     */
    private suspend fun basemap(request: DownloadRequest, storage: AreaStorage, http: SliceHttp): BasemapMetadata? {
        val output = storage.stagedBasemap(request.areaId, id)
        val config = request.config
        return when (val source = request.basemap) {
            BasemapSource.None -> null
            is BasemapSource.Url -> {
                report(PHASE_BASEMAP, bytes = 0, basemapPhase = BasemapPhase.DOWNLOAD)
                val bytes = retryingInline(config) {
                    // A 404 here is permanent (wrong URL), not a vanished SliceOSM job.
                    http.download(source.url, output, goneOn404 = false, onProgress = throttled { bytes, total ->
                        report(PHASE_BASEMAP, bytes = bytes, total = total, basemapPhase = BasemapPhase.DOWNLOAD)
                    })
                }
                val info = try {
                    withContext(Dispatchers.IO) { PmtilesInfo.read(output) }
                } catch (error: OsmFrameworkException) {
                    throw DownloadFailure.Permanent("basemap ${source.url} is not a valid PMTiles v3 archive: ${error.message}", error)
                }
                BasemapMetadata("url", source.url, output.length(), info.addressedTiles, info.minZoom, info.maxZoom, 1, bytes)
            }
            is BasemapSource.Extract -> {
                report(PHASE_BASEMAP, bytes = 0, basemapPhase = BasemapPhase.DIRECTORIES)
                val stats = BasemapExtract(http, config, storage.downloadDir(request.areaId)).run(
                    source.planetUrl,
                    request.bbox,
                    source.maxZoom,
                    source.overfetch,
                    output,
                    throttledPhase { phase, bytes, total -> report(PHASE_BASEMAP, bytes = bytes, total = total, basemapPhase = phase) },
                )
                val info = withContext(Dispatchers.IO) { PmtilesInfo.read(output) }
                BasemapMetadata(
                    "extract",
                    source.planetUrl,
                    output.length(),
                    stats.addressedTiles,
                    info.minZoom,
                    info.maxZoom,
                    stats.requests,
                    stats.transferredBytes,
                )
            }
        }
    }

    /** Progress goes through WorkManager's database: at most one update per interval. */
    private fun throttled(block: suspend (Long, Long?) -> Unit): suspend (Long, Long?) -> Unit {
        var last = 0L
        return { bytes, total ->
            val now = SystemClock.elapsedRealtime()
            if (now - last >= PROGRESS_INTERVAL_MILLIS) {
                last = now
                block(bytes, total)
            }
        }
    }

    /** As [throttled], for progress reported from several threads (basemap extract). */
    private fun throttledPhase(block: suspend (BasemapPhase, Long, Long?) -> Unit): suspend (BasemapPhase, Long, Long?) -> Unit {
        val last = java.util.concurrent.atomic.AtomicLong(0)
        val lastPhase = java.util.concurrent.atomic.AtomicReference<BasemapPhase?>(null)
        return { phase, bytes, total ->
            val now = SystemClock.elapsedRealtime()
            val previous = last.get()
            val phaseChanged = lastPhase.getAndSet(phase) != phase
            if ((phaseChanged || now - previous >= PROGRESS_INTERVAL_MILLIS) && last.compareAndSet(previous, now)) {
                block(phase, bytes, total)
            }
        }
    }

    /**
     * Gets a finished SliceOSM job: resumes the checkpointed job of this
     * work ID if there is one, otherwise submits a new job. If the server no
     * longer knows a resumed job (404), submits once more.
     */
    private suspend fun sliceJob(
        request: DownloadRequest,
        storage: AreaStorage,
        http: SliceHttp,
    ): Pair<SliceProtocol.Job, SliceProtocol.Progress> {
        val config = request.config
        val base = config.sliceBaseUrl
        report(PHASE_SUBMITTING)
        var job = storage.readCheckpoint(request.areaId, id, base)?.let { SliceProtocol.job(base, it) }
        repeat(2) {
            if (job == null) {
                val submit = protocol { SliceProtocol.jobRequest(base, request.bbox, request.name) }
                val response = retryingInline(config) { http.postJson(submit.url, submit.body) }
                // An unparseable answer (an HTML error page, say) is a server
                // fault, not a bad request: transient.
                job = protocol(transient = true) { SliceProtocol.job(base, response) }
                storage.writeCheckpoint(request.areaId, id, base, job!!.id)
            }
            try {
                return job!! to awaitSlice(job!!, http, config)
            } catch (gone: DownloadFailure.JobGone) {
                // Expired or unknown job (e.g. a checkpoint from long ago): start over.
                job = null
            }
        }
        throw DownloadFailure.Transient("SliceOSM lost the job twice")
    }

    private suspend fun awaitSlice(
        job: SliceProtocol.Job,
        http: SliceHttp,
        config: AreaConfig,
    ): SliceProtocol.Progress {
        val started = SystemClock.elapsedRealtime()
        while (true) {
            val status = retryingInline(config) { http.getText(job.statusUrl) }
            val progress = protocol(transient = true) { SliceProtocol.progress(status) }
            report(PHASE_SLICING, fraction = progress.fraction)
            if (progress.complete) return progress
            if (SystemClock.elapsedRealtime() - started > config.maxSliceWaitMillis) {
                throw DownloadFailure.Transient("SliceOSM job ${job.id} still running after ${config.maxSliceWaitMillis} ms")
            }
            delay(config.pollIntervalMillis)
        }
    }

    /** Maps a protocol (Rust) rejection onto the failure classes. */
    private inline fun <T> protocol(transient: Boolean = false, block: () -> T): T = try {
        block()
    } catch (error: OsmFrameworkException) {
        val message = "SliceOSM protocol: ${error.message}"
        throw if (transient) DownloadFailure.Transient(message, error) else DownloadFailure.Permanent(message, error)
    }

    private suspend fun report(
        phase: String,
        fraction: Double? = null,
        bytes: Long = 0,
        total: Long? = null,
        basemapPhase: BasemapPhase? = null,
    ) = setProgress(
        workDataOf(
            KEY_PHASE to phase,
            KEY_FRACTION to (fraction ?: Double.NaN),
            KEY_BYTES to bytes,
            KEY_TOTAL to (total ?: -1L),
            KEY_BASEMAP_PHASE to basemapPhase?.name,
        ),
    )

    private fun failed(message: String?, retryable: Boolean) =
        Result.failure(workDataOf(KEY_MESSAGE to (message ?: "download failed"), KEY_RETRYABLE to retryable))

    /** Everything a run needs, carried in the WorkRequest's input so a restarted process has it too. */
    internal data class DownloadRequest(
        val areaId: String,
        val bbox: Bbox,
        val name: String,
        val config: AreaConfig,
        val basemap: BasemapSource = BasemapSource.None,
    ) {
        fun toData(): Data = Data.Builder()
            .putString(KEY_AREA, areaId)
            .putString(KEY_BBOX, bbox.toJson().toString())
            .putString(KEY_NAME, name)
            .putString("base", config.sliceBaseUrl)
            .putLong("poll", config.pollIntervalMillis)
            .putLong("max_slice_wait", config.maxSliceWaitMillis)
            .putInt("connect_timeout", config.connectTimeoutMillis)
            .putInt("read_timeout", config.readTimeoutMillis)
            .putInt("inline_retries", config.inlineRetries)
            .putLong("inline_retry_delay", config.inlineRetryDelayMillis)
            .putInt("max_runs", config.maxRunAttempts)
            .putLong("backoff", config.backoffDelayMillis)
            .putBoolean("preserve_untagged_metadata", config.importOptions.preserveUntaggedMetadata)
            .putInt("cache_mb", config.importOptions.cacheMb)
            .putInt("basemap_parallelism", config.basemapParallelism)
            .apply {
                when (basemap) {
                    BasemapSource.None -> putString("basemap_kind", "none")
                    is BasemapSource.Url -> putString("basemap_kind", "url").putString("basemap_url", basemap.url)
                    is BasemapSource.Extract -> putString("basemap_kind", "extract")
                        .putString("basemap_url", basemap.planetUrl)
                        .putInt("basemap_max_zoom", basemap.maxZoom)
                        .putDouble("basemap_overfetch", basemap.overfetch)
                }
            }
            .build()

        companion object {
            fun fromData(data: Data): DownloadRequest {
                val defaults = AreaConfig()
                return DownloadRequest(
                    areaId = requireNotNull(data.getString(KEY_AREA)),
                    bbox = Bbox.fromJson(JSONObject(requireNotNull(data.getString(KEY_BBOX)))),
                    name = requireNotNull(data.getString(KEY_NAME)),
                    config = AreaConfig(
                        sliceBaseUrl = data.getString("base") ?: defaults.sliceBaseUrl,
                        pollIntervalMillis = data.getLong("poll", defaults.pollIntervalMillis),
                        maxSliceWaitMillis = data.getLong("max_slice_wait", defaults.maxSliceWaitMillis),
                        connectTimeoutMillis = data.getInt("connect_timeout", defaults.connectTimeoutMillis),
                        readTimeoutMillis = data.getInt("read_timeout", defaults.readTimeoutMillis),
                        inlineRetries = data.getInt("inline_retries", defaults.inlineRetries),
                        inlineRetryDelayMillis = data.getLong("inline_retry_delay", defaults.inlineRetryDelayMillis),
                        maxRunAttempts = data.getInt("max_runs", defaults.maxRunAttempts),
                        backoffDelayMillis = data.getLong("backoff", defaults.backoffDelayMillis),
                        importOptions = ImportOptions(
                            data.getBoolean("preserve_untagged_metadata", false),
                            data.getInt("cache_mb", ImportOptions().cacheMb),
                        ),
                        basemapParallelism = data.getInt("basemap_parallelism", defaults.basemapParallelism),
                    ),
                    basemap = when (data.getString("basemap_kind")) {
                        "url" -> BasemapSource.Url(requireNotNull(data.getString("basemap_url")))
                        "extract" -> BasemapSource.Extract(
                            requireNotNull(data.getString("basemap_url")),
                            data.getInt("basemap_max_zoom", 15),
                            data.getDouble("basemap_overfetch", 0.05),
                        )
                        else -> BasemapSource.None
                    },
                )
            }
        }
    }

    internal companion object {
        const val KEY_AREA = "area_id"
        const val KEY_BBOX = "bbox"
        const val KEY_NAME = "name"
        const val KEY_PHASE = "phase"
        const val KEY_FRACTION = "fraction"
        const val KEY_BYTES = "bytes"
        const val KEY_TOTAL = "total"
        const val KEY_METADATA = "metadata"
        const val KEY_MESSAGE = "message"
        const val KEY_RETRYABLE = "retryable"
        const val KEY_BASEMAP_PHASE = "basemap_phase"
        const val PHASE_SUBMITTING = "submitting"
        const val PHASE_SLICING = "slicing"
        const val PHASE_DOWNLOADING = "downloading"
        const val PHASE_IMPORTING = "importing"
        const val PHASE_BASEMAP = "basemap"
        const val PROGRESS_INTERVAL_MILLIS = 250L
    }
}
