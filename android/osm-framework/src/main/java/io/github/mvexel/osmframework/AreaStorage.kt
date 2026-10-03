package io.github.mvexel.osmframework

import android.content.Context
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * On-disk layout of downloaded areas. One place decides every path, so the
 * worker, [AreaManager] and tests cannot disagree.
 *
 * ```
 * filesDir/osm-areas/<areaId>.sqlite     published area (replaced only by OsmStore.importArea)
 * filesDir/osm-areas/<areaId>.json       metadata sidecar for that file (snapshot age, bbox, report)
 * noBackupFilesDir/osm-area-downloads/<areaId>/
 *     checkpoint.json                     SliceOSM job of the current WorkManager run
 *     <workId>.osm.pbf.part               PBF being downloaded by run <workId>
 * ```
 *
 * Published files live in `filesDir` (backed up by default; apps that do not
 * want ~100 MB in Auto Backup must exclude `osm-areas/`). Transient files
 * live in `noBackupFilesDir` so a backup never captures half a download.
 */
internal class AreaStorage(context: Context) {
    private val areas = File(context.filesDir, "osm-areas")
    private val downloads = File(context.noBackupFilesDir, "osm-area-downloads")

    fun areaFile(areaId: String) = File(areas, "$areaId.sqlite")

    private fun infoFile(areaId: String) = File(areas, "$areaId.json")

    private fun workDir(areaId: String) = File(downloads, areaId)

    private fun checkpointFile(areaId: String) = File(workDir(areaId), "checkpoint.json")

    /** Creates the directories an import and a download need. */
    fun prepare(areaId: String) {
        areas.mkdirs()
        workDir(areaId).mkdirs()
    }

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

    // --- Published area metadata -------------------------------------------

    /**
     * The published area, or null when none exists. The metadata is trusted
     * only if its recorded database size equals the file's size: the sidecar
     * is written right after the atomic publish, so a process death in
     * between leaves a newer file with an older (or no) sidecar, and that
     * must read as "metadata unknown" rather than as wrong metadata.
     */
    fun published(areaId: String): AreaInfo? {
        val file = areaFile(areaId)
        if (!file.isFile) return null
        val metadata = try {
            infoFile(areaId).takeIf { it.isFile }?.readText()?.let { AreaMetadata.fromJson(JSONObject(it)) }
        } catch (_: IOException) {
            null
        } catch (_: JSONException) {
            null
        }
        return AreaInfo(areaId, file, metadata?.takeIf { it.report.databaseBytes == file.length() })
    }

    /** Records metadata for the file just published. Atomic via write-then-rename. */
    fun writeMetadata(areaId: String, metadata: AreaMetadata) =
        writeAtomically(infoFile(areaId), metadata.toJson().toString())

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
        private val AREA_ID = Regex("[A-Za-z0-9_-]{1,64}")

        /** Area IDs become file names, so they are restricted to a safe alphabet. */
        fun requireValidAreaId(areaId: String) =
            require(AREA_ID.matches(areaId)) { "areaId must match ${AREA_ID.pattern}: $areaId" }
    }
}
