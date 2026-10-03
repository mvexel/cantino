package io.github.mvexel.osmframework

import android.content.Context
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * One run of an area download: submit (or resume) a SliceOSM job, poll it,
 * download the PBF to a staging file, import it with [OsmStore.importArea]
 * (which publishes atomically) and record the metadata sidecar.
 *
 * Lifecycle and failure behavior:
 * - **Previous area.** Nothing touches the published file until
 *   [OsmStore.importArea] renames a fully built replacement over it. Every
 *   failure, cancellation or process death before that leaves the old area
 *   intact and openable.
 * - **Staging.** The PBF goes to a per-run `.part` file in `noBackupFilesDir`.
 *   It is deleted when the run ends in any way (`finally`), and stale ones
 *   from killed processes are deleted when the next run starts.
 * - **Cancellation** (by [AreaManager.cancel], a REPLACE, lost constraints or
 *   the system) cancels this coroutine: HTTP is aborted via [SliceHttp]'s
 *   watchdog and the staging file is deleted. It is honored until the import
 *   starts. The native import cannot be interrupted; it finishes in seconds
 *   and publishes, and the run then still ends as cancelled.
 * - **Process death / constraint loss.** WorkManager re-runs the same work
 *   ID. The job checkpoint (see [AreaStorage.readCheckpoint]) lets the new
 *   run resume polling the job already submitted instead of creating a new
 *   one. The PBF download itself restarts from byte 0 (no byte-range resume).
 * - **Retries.** Transient HTTP failures are retried inline, then via
 *   `Result.retry()` (WorkManager backoff, checkpoint kept). Permanent
 *   failures end the work as failed and clear the checkpoint.
 * - **Execution limit.** WorkManager stops a run after 10 minutes; the slicing
 *   wait is capped below that and a stopped run resumes from the checkpoint.
 */
internal class AreaDownloadWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val request = DownloadRequest.fromData(inputData)
        val config = request.config
        val storage = AreaStorage(applicationContext)
        val http = SliceHttp(config.connectTimeoutMillis, config.readTimeoutMillis)
        storage.prepare(request.areaId)
        val staging = storage.stagingFile(request.areaId, id)
        storage.deleteStaleStaging(request.areaId, keep = staging)
        try {
            val (job, progress) = sliceJob(request, storage, http)

            report(PHASE_DOWNLOADING, bytes = 0, total = progress.sizeBytes)
            var lastReport = 0L
            val bytes = retryingInline(config) {
                http.download(job.downloadUrl, staging) { bytes, total ->
                    // Progress goes through WorkManager's database: throttle it.
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastReport >= PROGRESS_INTERVAL_MILLIS) {
                        lastReport = now
                        report(PHASE_DOWNLOADING, bytes = bytes, total = total ?: progress.sizeBytes)
                    }
                }
            }
            if (progress.sizeBytes != null && bytes != progress.sizeBytes) {
                throw DownloadFailure.Transient("download size $bytes differs from SliceOSM's ${progress.sizeBytes}")
            }

            // Last cancellation point: once the native import starts it runs to completion.
            report(PHASE_IMPORTING)
            val area = storage.areaFile(request.areaId)
            val importReport = withContext(Dispatchers.IO) {
                try {
                    OsmStore.importArea(staging.path, area.path, config.importOptions)
                } catch (error: OsmFrameworkException) {
                    // Bad or truncated PBF, or a full disk. The old area is intact.
                    throw DownloadFailure.Permanent("import failed: ${error.message}", error)
                }
            }
            val metadata = AreaMetadata(
                request.bbox,
                request.name,
                progress.timestamp,
                System.currentTimeMillis(),
                importReport,
            )
            storage.writeMetadata(request.areaId, metadata)
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
            // run's deleteStaleStaging covers that.
            staging.delete()
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

    /** Retries transient HTTP failures within this run with doubling delays. */
    private suspend fun <T> retryingInline(config: AreaConfig, block: suspend () -> T): T {
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

    /** Maps a protocol (Rust) rejection onto the failure classes. */
    private inline fun <T> protocol(transient: Boolean = false, block: () -> T): T = try {
        block()
    } catch (error: OsmFrameworkException) {
        val message = "SliceOSM protocol: ${error.message}"
        throw if (transient) DownloadFailure.Transient(message, error) else DownloadFailure.Permanent(message, error)
    }

    private suspend fun report(phase: String, fraction: Double? = null, bytes: Long = 0, total: Long? = null) =
        setProgress(
            workDataOf(
                KEY_PHASE to phase,
                KEY_FRACTION to (fraction ?: Double.NaN),
                KEY_BYTES to bytes,
                KEY_TOTAL to (total ?: -1L),
            ),
        )

    private fun failed(message: String?, retryable: Boolean) =
        Result.failure(workDataOf(KEY_MESSAGE to (message ?: "download failed"), KEY_RETRYABLE to retryable))

    /** Everything a run needs, carried in the WorkRequest's input so a restarted process has it too. */
    internal data class DownloadRequest(val areaId: String, val bbox: Bbox, val name: String, val config: AreaConfig) {
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
                    ),
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
        const val PHASE_SUBMITTING = "submitting"
        const val PHASE_SLICING = "slicing"
        const val PHASE_DOWNLOADING = "downloading"
        const val PHASE_IMPORTING = "importing"
        const val PROGRESS_INTERVAL_MILLIS = 250L
    }
}
