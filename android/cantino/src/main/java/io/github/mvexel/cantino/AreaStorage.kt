package io.github.mvexel.cantino

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.annotation.VisibleForTesting
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * On-disk layout of downloaded areas and the commit protocol that publishes
 * them. One place decides every path, so the worker, [AreaManager] and tests
 * cannot disagree.
 *
 * ```
 * filesDir/cantino-areas/<areaId>.sqlite     published OSM data
 * filesDir/cantino-areas/<areaId>.pmtiles    published basemap (only with a BasemapSource)
 * filesDir/cantino-areas/<areaId>.json       metadata sidecar describing both
 * filesDir/cantino-areas/<areaId>.commit     commit journal, present only while publishing
 * filesDir/cantino-areas/.staging/<areaId>/<workId>/
 *     area.sqlite, basemap.pmtiles, area.json   the next version, built by run <workId>
 * noBackupFilesDir/cantino-area-downloads/<areaId>/
 *     checkpoint.json                     SliceOSM job of the current WorkManager run
 *     <workId>.osm.pbf.part               PBF being downloaded by run <workId>
 *     range-<id>.part                     basemap tile range in flight
 * ```
 *
 * ## Publishing (the area is data + basemap + sidecar)
 *
 * A run builds the complete next version in its own staging directory, on
 * the same file system as the published files so each part moves by
 * `rename(2)`. Three files cannot be renamed atomically together, so the
 * commit uses a roll-forward journal:
 *
 * 1. Under the per-area lock, the run's `beforeCommit` check runs (it throws
 *    if the run was cancelled: nothing has changed, the staging directory is
 *    discarded).
 * 2. `<areaId>.commit` is written atomically and durably. **This is the
 *    commit point.** From here on the new version is published, whatever
 *    happens to the process.
 * 3. Roll forward: rename basemap (or delete the old one when the new
 *    version has none), data, sidecar into place; fsync the directory;
 *    delete the journal and the staging directory.
 *
 * Every reader ([published]) and every new run first calls [recover], which
 * finishes step 3 for a journal left by a dead process. Since readers take the
 * same lock, nobody observes a half-renamed area in this process: they see
 * the old version or wait (milliseconds) for the new one. Step 3 is
 * idempotent: a part whose staged file is gone was already moved.
 *
 * (A reader in another process, or one that opens `<areaId>.sqlite` directly
 * without going through this class, can see the data renamed before the
 * sidecar during the few milliseconds of step 3; the sidecar's size check in
 * [published] then reports metadata as unknown rather than wrong.)
 *
 * Published files live in `filesDir` (backed up by default; apps that do not
 * want ~100 MB in Auto Backup must exclude `cantino-areas/`). Transient files
 * live in `noBackupFilesDir` (except the staging directory, which must share
 * the published files' file system).
 */
internal class AreaStorage(context: Context) {
    private val areas = File(context.filesDir, "cantino-areas")
    private val stagingRoot = File(areas, ".staging")
    private val downloads = File(context.noBackupFilesDir, "cantino-area-downloads")

    fun dataFile(areaId: String) = File(areas, "$areaId.sqlite")

    fun basemapFile(areaId: String) = File(areas, "$areaId.pmtiles")

    private fun infoFile(areaId: String) = File(areas, "$areaId.json")

    private fun journalFile(areaId: String) = File(areas, "$areaId.commit")

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

    // --- Staged next version -------------------------------------------------

    /** Where run [workId] builds the next version of [areaId]. */
    fun stagingDir(areaId: String, workId: UUID) = File(File(stagingRoot, areaId), workId.toString())

    fun stagedArea(areaId: String, workId: UUID) = File(stagingDir(areaId, workId), "area.sqlite")

    fun stagedBasemap(areaId: String, workId: UUID) = File(stagingDir(areaId, workId), "basemap.pmtiles")

    private fun stagedInfo(areaId: String, workId: UUID) = File(stagingDir(areaId, workId), "area.json")

    /**
     * Creates an empty staging directory for run [workId] and deletes those
     * of other runs (left by killed processes or cancelled runs). Called at
     * the start of a run, after [recover], so a pending commit is never
     * deleted. Only one run per area is active (unique work); a cancelled run
     * still unwinding loses its directory, which it was about to discard.
     */
    fun prepareStaging(areaId: String, workId: UUID): File = synchronized(lock(areaId)) {
        rollForward(areaId)
        File(stagingRoot, areaId).listFiles()?.forEach { it.deleteRecursively() }
        stagingDir(areaId, workId).also { it.mkdirs() }
    }

    /** Discards run [workId]'s staging directory unless its commit is pending (the journal owns it then). */
    fun discardStaging(areaId: String, workId: UUID) = synchronized(lock(areaId)) {
        if (readJournal(areaId)?.workId != workId) stagingDir(areaId, workId).deleteRecursively()
    }

    /** Writes the sidecar for the staged version; it is published with it. */
    fun writeStagedMetadata(areaId: String, workId: UUID, metadata: AreaMetadata) =
        writeAtomically(stagedInfo(areaId, workId), metadata.toJson().toString())

    /**
     * Publishes run [workId]'s staged version (see the class comment).
     * [beforeCommit] runs under the lock immediately before the commit point;
     * throwing from it aborts with nothing changed. [hasBasemap] says whether
     * the version includes `basemap.pmtiles` (if not, a previously published
     * basemap is removed). [AreaTestHooks.afterCommitPoint] is a test hook.
     *
     * Throws [IOException] only after the commit point if a rename failed:
     * the version is committed and the next [recover] finishes it.
     */
    fun commit(areaId: String, workId: UUID, hasBasemap: Boolean, beforeCommit: () -> Unit) =
        synchronized(lock(areaId)) {
            // A journal of an earlier run (a dead process) completes first.
            rollForward(areaId)
            beforeCommit()
            check(stagedArea(areaId, workId).isFile && stagedInfo(areaId, workId).isFile) { "staged area incomplete" }
            check(!hasBasemap || stagedBasemap(areaId, workId).isFile) { "staged basemap missing" }
            writeAtomically(
                journalFile(areaId),
                JSONObject().put("work_id", workId.toString()).put("basemap", hasBasemap).toString(),
            )
            syncDirectory(areas)
            // ---- committed ----
            AreaTestHooks.afterCommitPoint?.invoke()
            rollForward(areaId)
        }

    /**
     * Finishes a commit left half-done by a dead process (or a failed
     * rename). Cheap when there is nothing to do; blocks while a commit of
     * this area is running in this process.
     */
    fun recover(areaId: String) = synchronized(lock(areaId)) {
        try {
            rollForward(areaId)
        } catch (error: IOException) {
            // Leave the journal; the next recover retries. Readers meanwhile
            // see a mix of old and new parts, flagged by the sidecar checks.
            Log.w(TAG, "cannot finish the pending commit of $areaId", error)
        }
    }

    private data class Journal(val workId: UUID, val basemap: Boolean)

    private fun readJournal(areaId: String): Journal? = try {
        val json = JSONObject(journalFile(areaId).readText())
        Journal(UUID.fromString(json.getString("work_id")), json.getBoolean("basemap"))
    } catch (_: IOException) {
        null
    } catch (_: JSONException) {
        null // never committed: the journal is written atomically, so a torn one cannot exist
    } catch (_: IllegalArgumentException) {
        null
    }

    /** Step 3 of the commit. Caller holds the lock. Idempotent. */
    private fun rollForward(areaId: String) {
        val journal = readJournal(areaId) ?: return
        val workId = journal.workId
        if (journal.basemap) {
            moveIfPresent(stagedBasemap(areaId, workId), basemapFile(areaId))
        } else {
            basemapFile(areaId).delete()
        }
        moveIfPresent(stagedArea(areaId, workId), dataFile(areaId))
        moveIfPresent(stagedInfo(areaId, workId), infoFile(areaId))
        syncDirectory(areas)
        journalFile(areaId).delete()
        stagingDir(areaId, workId).deleteRecursively()
    }

    private fun moveIfPresent(from: File, to: File) {
        if (!from.exists()) return // moved by an earlier, interrupted roll-forward
        if (!from.renameTo(to)) throw IOException("cannot rename $from to $to")
    }

    /** Makes renames in [directory] durable (best effort; a crash before it may undo them). */
    private fun syncDirectory(directory: File) {
        try {
            val fd = Os.open(directory.path, OsConstants.O_RDONLY, 0)
            try {
                Os.fsync(fd)
            } finally {
                Os.close(fd)
            }
        } catch (_: ErrnoException) {
        }
    }

    // --- Published area metadata -------------------------------------------

    /**
     * The published area, or null when none exists. Completes a pending
     * commit first ([recover]). The metadata is trusted only if it describes
     * the files present: the recorded database size must equal the data
     * file's, and the recorded basemap (or its absence) must match the
     * basemap file. Anything else reads as "metadata unknown" (null) rather
     * than as wrong metadata.
     */
    fun published(areaId: String): AreaInfo? {
        recover(areaId)
        val file = dataFile(areaId)
        if (!file.isFile) return null
        val basemap = basemapFile(areaId).takeIf { it.isFile }
        val metadata = try {
            infoFile(areaId).takeIf { it.isFile }?.readText()?.let { AreaMetadata.fromJson(JSONObject(it)) }
        } catch (_: IOException) {
            null
        } catch (_: JSONException) {
            null
        } catch (_: IllegalArgumentException) {
            null // malformed work ID
        } catch (_: NoSuchElementException) {
            null // unknown basemap kind
        }
        val consistent = metadata != null &&
            metadata.report.databaseBytes == file.length() &&
            metadata.basemap?.fileBytes == basemap?.length()
        return AreaInfo(areaId, file, metadata?.takeIf { consistent }, basemap)
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

    companion object {
        private const val TAG = "AreaStorage"
        private val AREA_ID = Regex("[A-Za-z0-9_-]{1,64}")

        /**
         * One lock per area ID for the whole process: commits, recovery and
         * readers of the published area serialize on it. (WorkManager runs
         * workers in the app's process by default; a multi-process app must
         * route all area access through one process.)
         */
        private val locks = ConcurrentHashMap<String, Any>()

        private fun lock(areaId: String): Any = locks.getOrPut(areaId) { Any() }

        /** Area IDs become file names, so they are restricted to a safe alphabet. */
        fun requireValidAreaId(areaId: String) =
            require(AREA_ID.matches(areaId)) { "areaId must match ${AREA_ID.pattern}: $areaId" }
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
    /** Runs right after the (uninterruptible) native import returned, standing in for a long import. */
    @Volatile var afterImport: (() -> Unit)? = null

    /** Runs inside the commit, right after the commit point. */
    @Volatile var afterCommitPoint: (() -> Unit)? = null

    /** Runs after each successful `setForeground` of a run in foreground mode, with the notification ID. */
    @Volatile var onForeground: ((Int) -> Unit)? = null

    /** Replaces the manifest check of foreground mode: the gaps to report (empty = all declared). */
    @Volatile var foregroundManifestGaps: (() -> List<String>)? = null
}
