package lol.osm.cantino.inspector

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Review and export of the app-owned field overlay. No action here writes to OSM. */
class FindingsActivity : Activity() {
    private val scope = MainScope()
    private lateinit var repository: FindingsRepository
    private lateinit var root: LinearLayout
    private var pendingExport: PendingExport? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = FindingsRepository.open(this)
        root = vertical(children = arrayOf(text("Loading findings…")))
        setContentView(
            ScrollView(this).apply {
                fitsSystemWindows = true
                setBackgroundColor(android.graphics.Color.WHITE)
                addView(root)
            },
        )
        useLightSystemBars()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        scope.launch {
            val findings = try {
                withContext(Dispatchers.IO) { repository.all() }
            } catch (error: Exception) {
                root.removeAllViews()
                root.addView(text("Could not read findings: ${error.message}", color = Colors.ERROR))
                return@launch
            }
            render(findings)
        }
    }

    private fun render(findings: List<Finding>) {
        root.removeAllViews()
        val reviewed = findings.filter { it.status == FindingStatus.REVIEWED }
        root.addView(text("Field findings", 22f, bold = true))
        root.addView(
            text(
                "${findings.size} saved · ${reviewed.size} ready for review. Exports contain only reviewed findings and never upload to OSM.",
                13f,
                color = Colors.MUTED,
            ),
        )
        root.addView(
            horizontal(
                button("Export GeoJSON") { export(reviewed, ExportFormat.GEOJSON) }.apply { isEnabled = reviewed.isNotEmpty() },
                button("Export CSV") { export(reviewed, ExportFormat.CSV) }.apply { isEnabled = reviewed.isNotEmpty() },
            ),
        )
        root.addView(
            text(
                "Send the files to a review queue such as MapRoulette, or review each object in iD/JOSM before making an ordinary OSM changeset.",
                13f,
                color = Colors.MUTED,
            ),
        )
        if (findings.isEmpty()) {
            section("No findings yet")
            root.addView(text("Inspect an object on the map, then choose Record finding."))
            return
        }

        findings.forEach { finding ->
            section(Labels.kindId(finding.osmId))
            root.addView(
                text(
                    if (finding.status == FindingStatus.REVIEWED) "Ready for review" else "Draft — review before export",
                    13f,
                    bold = true,
                    color = if (finding.status == FindingStatus.REVIEWED) Colors.HIT else Colors.MUTED,
                ),
            )
            if (finding.proposedTags.isNotEmpty()) root.addView(mono(ProposedTags.format(finding.proposedTags)))
            if (finding.note.isNotBlank()) root.addView(text(finding.note))
            if (finding.evidence.isNotBlank()) root.addView(text("Evidence: ${finding.evidence}", 13f, color = Colors.MUTED))
            root.addView(text("Observed ${finding.observedAt} · base version ${finding.baseVersion ?: "unknown"}", 12f, color = Colors.MUTED))
            root.addView(
                horizontal(
                    button("Open") {
                        startActivity(Intent(this, ObjectActivity::class.java).putExtra(ObjectActivity.EXTRA_ID, Labels.path(finding.osmId)))
                    },
                    button(if (finding.status == FindingStatus.REVIEWED) "Return to draft" else "Mark reviewed") {
                        updateStatus(finding)
                    },
                    button("Delete") { confirmDelete(finding) },
                ),
            )
        }
    }

    private fun updateStatus(finding: Finding) {
        val next = if (finding.status == FindingStatus.REVIEWED) FindingStatus.DRAFT else FindingStatus.REVIEWED
        scope.launch {
            try {
                withContext(Dispatchers.IO) { repository.upsert(finding.copy(status = next)) }
                reload()
            } catch (error: Exception) {
                showError("Could not update finding: ${error.message}")
            }
        }
    }

    private fun confirmDelete(finding: Finding) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${Labels.kindId(finding.osmId)} finding?")
            .setMessage("This removes the app-owned observation. The offline OSM object is unchanged.")
            .setPositiveButton("Delete") { _, _ ->
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { repository.delete(finding.osmId) }
                        reload()
                    } catch (error: Exception) {
                        showError("Could not delete finding: ${error.message}")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun export(findings: List<Finding>, format: ExportFormat) {
        if (findings.isEmpty()) return
        val timestamp = FILE_TIME.format(Instant.now())
        val pending = when (format) {
            ExportFormat.GEOJSON -> PendingExport(
                "cantino-findings-$timestamp.geojson",
                "application/geo+json",
                FindingsExport.geoJson(findings),
            )
            ExportFormat.CSV -> PendingExport(
                "cantino-findings-$timestamp.csv",
                "text/csv",
                FindingsExport.csv(findings),
            )
        }
        pendingExport = pending
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(pending.mimeType)
                .putExtra(Intent.EXTRA_TITLE, pending.fileName),
            REQUEST_EXPORT,
        )
    }

    @Deprecated("Activity result API is sufficient for this framework-free example app")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_EXPORT) return
        val pending = pendingExport
        pendingExport = null
        val uri = data?.data
        if (resultCode != RESULT_OK || pending == null || uri == null) return
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(pending.content) }
                        ?: error("the selected document could not be opened")
                }
                showMessage("Exported ${pending.fileName}")
            } catch (error: Exception) {
                showError("Could not export findings: ${error.message}")
            }
        }
    }

    private fun section(title: String) {
        root.addView(text(title, 17f, bold = true).apply { setPadding(0, dp(16), 0, dp(4)) })
    }

    private fun showMessage(message: String) {
        AlertDialog.Builder(this).setMessage(message).setPositiveButton("Close", null).show()
    }

    private fun showError(message: String) {
        AlertDialog.Builder(this).setTitle("Field findings").setMessage(message).setPositiveButton("Close", null).show()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private enum class ExportFormat { GEOJSON, CSV }
    private data class PendingExport(val fileName: String, val mimeType: String, val content: String)

    companion object {
        private const val REQUEST_EXPORT = 1
        private val FILE_TIME = DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss").withZone(ZoneOffset.UTC)
    }
}
