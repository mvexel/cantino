package lol.osm.cantino

import android.content.Context
import androidx.annotation.VisibleForTesting
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * On-disk layout of downloaded areas and the commit protocol that publishes
 * them. A thin wrapper: the layout, staging, the roll-forward commit
 * journal, recovery after a kill, the area lock and the reading of the
 * published area live in the Rust core (`src/area_storage.rs`, through
 * `cantino_area_*`), so Android and iOS behave identically by construction.
 * This class chooses the directories and keeps what is Android-specific:
 * the download scratch files and the WorkManager job checkpoint.
 *
 * ```
 * filesDir/cantino-areas/                      the core's root (layout in src/area_storage.rs):
 *     <areaId>.sqlite, <areaId>.pmtiles, <areaId>.json   published data, basemap, sidecar
 *     <areaId>.commit                          commit journal, only while publishing
 *     <areaId>.lock                            area lock file (empty)
 *     .staging/<areaId>/<workId>/              the next version, built by run <workId>
 * noBackupFilesDir/cantino-area-downloads/<areaId>/
 *     checkpoint.json                     SliceOSM job of the current WorkManager run
 *     <workId>.osm.pbf.part               PBF being downloaded by run <workId>
 *     range-<id>.part                     basemap tile range in flight
 * ```
 *
 * The layout and file formats are unchanged from 0.2.0 (apart from the new
 * lock file), so areas written by 0.2.0 are read and recovered as they are.
 *
 * ## Publishing (the area is data + basemap + sidecar)
 *
 * A run builds the complete next version in its staging directory; [commit]
 * publishes data, basemap and sidecar together through a roll-forward
 * journal whose atomic write is the commit point. Before it, nothing has
 * changed (a failure, cancel or kill leaves the old area); after it, the
 * new version is published whatever happens to the process (the next
 * [recover], which every reader and every new run performs, finishes it).
 *
 * The core holds a per-area lock (in-process, plus `flock` on
 * `<areaId>.lock` across processes) around every commit, recovery and read
 * of the published area, so a reader through this class sees the complete
 * old area or the complete new one, and waits (milliseconds) for a commit
 * in progress. (A reader that opens `<areaId>.sqlite` directly, bypassing
 * this class, can see the data renamed before the sidecar during the few
 * milliseconds of the roll-forward; [published] then reports the metadata
 * as unknown rather than wrong.)
 *
 * Published files live in `filesDir` (backed up by default; apps that do not
 * want ~100 MB in Auto Backup must exclude `cantino-areas/`). Transient files
 * live in `noBackupFilesDir` (except the staging directory, which must share
 * the published files' file system).
 */
internal class AreaStorage @VisibleForTesting internal constructor(
    private val areas: File,
    private val downloads: File,
) {
    constructor(context: Context) : this(
        File(context.filesDir, "cantino-areas"),
        File(context.noBackupFilesDir, "cantino-area-downloads"),
    )

    /** The core's root directory for this app's areas. */
    private val root: String get() = areas.path

    /** Paths of [areaId] (and of run [workId]'s staging) as the core lays them out. */
    private fun layout(areaId: String, workId: UUID? = null) =
        JSONObject(NativeBridge.areaLayout(root, areaId, workId?.toString()))

    fun dataFile(areaId: String) = File(layout(areaId).getString("data"))

    fun basemapFile(areaId: String) = File(layout(areaId).getString("basemap"))

    private fun workDir(areaId: String) = File(downloads, areaId)

    private fun checkpointFile(areaId: String) = File(workDir(areaId), "checkpoint.json")

    /** Creates the directories an import and a download need. */
    fun prepare(areaId: String) {
        areas.mkdirs()
        workDir(areaId).mkdirs()
    }

    /** Scratch directory for range files of a basemap extract (not backed up). */
    fun downloadDir(areaId: String) = workDir(areaId)

    /**
     * Staging file for one run. The work ID in the name keeps a cancelled run
     * that is still unwinding from deleting or overwriting the file of the run
     * that replaced it.
     */
    fun stagingFile(areaId: String, workId: UUID) = File(workDir(areaId), "$workId.osm.pbf.part")

    /** Deletes partial downloads of earlier runs (killed processes never run their `finally`). */
    fun deleteStaleStaging(areaId: String, keep: File) {
        workDir(areaId).listFiles { file -> file.name.endsWith(".part") && file != keep }?.forEach { it.delete() }
    }

    // --- Staged next version (the core) ---------------------------------------

    /** Where run [workId] builds the next version of [areaId]. */
    fun stagingDir(areaId: String, workId: UUID) = File(layout(areaId, workId).getString("staging_dir"))

    fun stagedArea(areaId: String, workId: UUID) = File(layout(areaId, workId).getString("staged_data"))

    fun stagedBasemap(areaId: String, workId: UUID) = File(layout(areaId, workId).getString("staged_basemap"))

    /**
     * Creates an empty staging directory for run [workId] and deletes those
     * of other runs (left by killed processes or cancelled runs), after
     * rolling a pending commit forward (so a committed version is never
     * deleted). Throws [IOException] if that roll-forward fails.
     */
    fun prepareStaging(areaId: String, workId: UUID): File = storageIo {
        File(JSONObject(NativeBridge.areaPrepareStaging(root, areaId, workId.toString())).getString("staging_dir"))
    }

    /** Discards run [workId]'s staging directory unless its commit is pending (the journal owns it then). */
    fun discardStaging(areaId: String, workId: UUID) = storageIo {
        NativeBridge.areaDiscardStaging(root, areaId, workId.toString())
    }

    /** Writes the sidecar for the staged version; it is published with it. */
    fun writeStagedMetadata(areaId: String, workId: UUID, metadata: AreaMetadata) = storageIo {
        NativeBridge.areaWriteStagedMetadata(root, areaId, workId.toString(), metadata.toJson().toString())
    }

    /**
     * Publishes run [workId]'s staged version (see the class comment).
     * [beforeCommit] runs under the area lock immediately before the commit
     * point; throwing from it aborts with nothing changed, and the exception
     * propagates. [hasData] and [hasBasemap] say whether the version includes
     * `area.sqlite` and `basemap.pmtiles` (at least one); a published part the
     * version lacks is removed.
     * [AreaTestHooks.afterCommitPoint] runs right after the commit point.
     *
     * Throws [IOException] for a file system failure: before the commit
     * point nothing changed; after it (a failed rename) the version is
     * committed and the next [recover] finishes it.
     * [CantinoException.InvalidArgument] if the staged version is incomplete.
     */
    fun commit(areaId: String, workId: UUID, hasData: Boolean, hasBasemap: Boolean, beforeCommit: () -> Unit) {
        val parts = (if (hasData) NativeBridge.PART_DATA else 0) or (if (hasBasemap) NativeBridge.PART_BASEMAP else 0)
        val published = storageIo {
            NativeBridge.areaCommit(root, areaId, workId.toString(), parts) { stage ->
            when (stage) {
                    AreaCommitHook.STAGE_BEFORE_COMMIT -> beforeCommit()
                    AreaCommitHook.STAGE_AFTER_COMMIT_POINT -> AreaTestHooks.afterCommitPoint?.invoke()
                }
            }
        }
        // The hook aborts only by throwing, and that exception is rethrown
        // when the native call returns; false without one is a broken contract.
        check(published) { "commit of $areaId aborted without an exception" }
    }

    /**
     * Finishes a commit left half-done by a dead process (or a failed
     * rename). Cheap when there is nothing to do; blocks while a commit of
     * this area is running. Throws [IOException] if recovery cannot finish
     * (including a damaged journal); the journal remains for a later retry.
     */
    fun recover(areaId: String) = storageIo { NativeBridge.areaRecover(root, areaId) }

    // --- Published area metadata -------------------------------------------

    /**
     * The published area, or null when none exists. Completes a pending
     * commit first under the area lock and reads the files and sidecar
     * under the same lock, so the answer describes one version; throws
     * [IOException] if recovery cannot finish (no mixed area is returned).
     * The core trusts the metadata only if it describes the
     * files present (recorded database size = data file size, recorded
     * basemap = basemap file); anything else reads as "metadata unknown"
     * (null) rather than as wrong metadata.
     */
    fun published(areaId: String): AreaInfo? {
        val json = JSONObject(storageIo { NativeBridge.areaPublished(root, areaId) } ?: return null)
        val metadata = json.optJSONObject("metadata")?.let { sidecar ->
            // The core validated the sidecar already; this only guards
            // against a Kotlin parser stricter than the core's.
            try {
                AreaMetadata.fromJson(sidecar)
            } catch (_: JSONException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: NoSuchElementException) {
                null
            }
        }
        val basemap = if (json.isNull("basemap")) null else File(json.getString("basemap"))
        val data = if (json.isNull("data")) null else File(json.getString("data"))
        return AreaInfo(areaId, data, metadata, basemap)
    }

    // --- Job checkpoint (process-death resume) -------------------------------

    /**
     * The checkpoint ties a SliceOSM job to one WorkManager run ([workId]) and
     * one server ([base]). A run restarted after process death or a retry has
     * the same work ID, so it resumes polling the job it already submitted. A
     * new [AreaManager.download] call creates a new work ID and therefore
     * always submits a fresh job (fresh data), never reusing an old one.
     */
    fun readCheckpoint(areaId: String, workId: UUID, base: String): String? = try {
        val json = JSONObject(checkpointFile(areaId).readText())
        json.getString("job_id").takeIf { json.getString("work_id") == workId.toString() && json.getString("base") == base }
    } catch (_: IOException) {
        null
    } catch (_: JSONException) {
        null
    }

    fun writeCheckpoint(areaId: String, workId: UUID, base: String, jobId: String) = writeAtomically(
        checkpointFile(areaId),
        JSONObject().put("work_id", workId.toString()).put("base", base).put("job_id", jobId).toString(),
    )

    fun clearCheckpoint(areaId: String, workId: UUID) {
        // Only our own: a replacing run may already have written its checkpoint.
        val file = checkpointFile(areaId)
        val owner = try {
            JSONObject(file.readText()).optString("work_id")
        } catch (_: IOException) {
            return
        } catch (_: JSONException) {
            null
        }
        if (owner == null || owner == workId.toString()) file.delete()
    }

    private fun writeAtomically(target: File, text: String) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.${UUID.randomUUID()}.tmp")
        try {
            temporary.outputStream().use { output ->
                output.write(text.toByteArray())
                output.fd.sync()
            }
            if (!temporary.renameTo(target)) throw IOException("cannot rename $temporary to $target")
        } finally {
            temporary.delete()
        }
    }

    /** The core reports file system failures as [CantinoException.Io]; this class's contract is [IOException]. */
    private inline fun <T> storageIo(block: () -> T): T = try {
        block()
    } catch (error: CantinoException.Io) {
        throw IOException(error.message, error)
    }

    companion object {

        /**
         * Area IDs become file names, so they are restricted to a safe
         * alphabet: 1 to 64 characters from `[A-Za-z0-9_-]`. The rule lives
         * in the core (`cantino_area_validate_id`); this turns its rejection
         * into the [IllegalArgumentException] [AreaManager] documents.
         */
        fun requireValidAreaId(areaId: String) {
            try {
                NativeBridge.areaValidateId(areaId)
            } catch (error: CantinoException.InvalidArgument) {
                throw IllegalArgumentException("areaId must match [A-Za-z0-9_-]{1,64}: $areaId", error)
            }
        }
    }
}

/**
 * Test-only hooks into the download path (null in production). They run
 * blocking code at points where a real device would be slow, so
 * instrumented tests can cancel at exactly those points. Internal, so not
 * part of the public API; the module's own androidTest sources see it.
 */
@VisibleForTesting
internal object AreaTestHooks {
    @Volatile var downloadTuning: DownloadTuning? = null

    /** Runs right after the (uninterruptible) native import returned, standing in for a long import. */
    @Volatile var afterImport: (() -> Unit)? = null

    /** Runs inside the commit, right after the commit point (under the area lock). */
    @Volatile var afterCommitPoint: (() -> Unit)? = null

    /** Runs after each successful `setForeground` of a run in foreground mode, with the notification ID. */
    @Volatile var onForeground: ((Int) -> Unit)? = null

    /** Replaces the manifest check of foreground mode: the gaps to report (empty = all declared). */
    @Volatile var foregroundManifestGaps: (() -> List<String>)? = null
}
