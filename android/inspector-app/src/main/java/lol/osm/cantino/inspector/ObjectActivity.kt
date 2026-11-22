package lol.osm.cantino.inspector

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lol.osm.cantino.ObjectMetadata
import lol.osm.cantino.OsmId
import lol.osm.cantino.OsmKind
import lol.osm.cantino.OsmObject

/**
 * The object navigator: one OSM object of the offline area, raw, with its
 * references followed. Grown from android/cafe-app DetailActivity.kt.
 *
 * - Tags, metadata (or "not stored" for untagged nodes and their location version).
 * - Node: coordinate, and "ways using this node" ([Inspect.parentWays], best effort).
 * - Way: its nodes in order (repeats kept), tagged vertices marked, each a link.
 * - Relation: its members with roles, each a link.
 * - Missing references: the way nodes or members that are not in this area
 *   (a batch `get` returns null for them).
 * - "Show on map" returns to the map with the object highlighted; the
 *   openstreetmap.org link is the only thing here that needs the network.
 *
 * Opened with [EXTRA_ID] = "way/123".
 */
class ObjectActivity : Activity() {
    private val scope = MainScope()
    private lateinit var repository: FindingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_ID)?.let(Labels::parsePath) ?: return finish()
        repository = FindingsRepository.open(this)
        title = Labels.kindId(id)
        val root = vertical(16, text("Loading ${Labels.kindId(id)}…"))
        setContentView(ScrollView(this).apply { fitsSystemWindows = true; setBackgroundColor(android.graphics.Color.WHITE); addView(root) })
        useLightSystemBars()

        scope.launch {
            val loaded = try {
                InspectorStore.withStore(this@ObjectActivity) { store, area ->
                    Inspect.detail(store, id) to area.metadata?.snapshotTimestamp?.toString()
                }
            } catch (error: Exception) {
                root.removeAllViews()
                root.addView(text("Could not read the offline area: ${error.message}", color = Colors.ERROR))
                return@launch
            }
            val finding = try {
                withContext(Dispatchers.IO) { repository.get(id) }
            } catch (error: Exception) {
                root.removeAllViews()
                root.addView(text("Could not read field findings: ${error.message}", color = Colors.ERROR))
                return@launch
            }
            render(root, loaded.first, finding, loaded.second)
        }
    }

    private fun render(root: LinearLayout, detail: ObjectDetail, finding: Finding?, snapshotTimestamp: String?) {
        root.removeAllViews()
        val id = detail.id
        val obj = detail.obj
        val name = obj?.tags?.get("name")
        root.addView(text(name ?: Labels.kindId(id), 22f, bold = true))
        if (name != null) root.addView(text(Labels.kindId(id), 13f, color = Colors.MUTED))
        root.addView(
            horizontal(
                button("Show on map") { showOnMap(id) }.apply { isEnabled = obj != null && !detail.shape.isEmpty },
                button("openstreetmap.org ↗") { openOsmOrg(id) },
            ),
        )
        root.addView(text("The openstreetmap.org page needs a network connection; everything else here is offline.", 12f, color = Colors.MUTED))
        if (obj == null) {
            root.addView(text("Not in this area: the offline extract does not contain this object (it lies outside the area, or was clipped at its edge)."))
            return
        }

        section(root, "Field finding")
        if (finding == null) {
            root.addView(text("No app-owned observation for this object.", 13f, color = Colors.MUTED))
        } else {
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
            root.addView(
                text(
                    "Observed ${finding.observedAt} · base version ${finding.baseVersion ?: "unknown"}",
                    12f,
                    color = Colors.MUTED,
                ),
            )
        }
        root.addView(
            horizontal(
                button(if (finding == null) "Record finding" else "Edit finding") {
                    showFindingEditor(root, detail, finding, snapshotTimestamp)
                },
                button("All findings") { startActivity(Intent(this, FindingsActivity::class.java)) },
                button("Delete") { deleteFinding(root, detail, finding, snapshotTimestamp) }
                    .apply { visibility = if (finding == null) View.GONE else View.VISIBLE },
            ),
        )
        root.addView(
            text(
                "Findings are stored separately from the offline OSM snapshot. Mark one reviewed before it can be exported.",
                12f,
                color = Colors.MUTED,
            ),
        )


        section(root, "Tags (${obj.tags.size})")
        if (obj.tags.isEmpty()) root.addView(text("No tags.", color = Colors.MUTED))
        obj.tags.toSortedMap().forEach { (k, v) -> root.addView(mono("$k = $v")) }

        section(root, "Metadata")
        val metadata = obj.metadata
        if (metadata == null) {
            val version = (obj as? OsmObject.Node)?.locationVersion
            root.addView(text("Not stored (untagged node imported without metadata)" + (version?.let { "; location version $it" } ?: "") + "."))
        } else {
            root.addView(mono(describe(metadata)))
        }

        when (obj) {
            is OsmObject.Node -> {
                section(root, "Node")
                root.addView(mono("lat %.7f\nlon %.7f".format(java.util.Locale.ROOT, obj.lat, obj.lon)))
                section(root, "Ways using this node (${detail.parentWays.size})")
                root.addView(text("Best effort: the ways in this area whose bbox contains the node and whose node list has it. Ways outside the extract are unknown.", 12f, color = Colors.MUTED))
                detail.parentWays.forEach { way ->
                    root.addView(link("way ${way.id.id} · ${way.tags["name"] ?: Labels.primaryTag(way.tags)}", way.id))
                }
            }
            is OsmObject.Way -> {
                val closed = obj.nodeIds.size > 1 && obj.nodeIds.first() == obj.nodeIds.last()
                section(root, "Way nodes (${obj.nodeIds.size} refs, ${detail.references} distinct${if (closed) ", closed" else ""})")
                missing(root, detail, "nodes")
                detail.nodeRefs.forEachIndexed { index, ref ->
                    val label = "${index + 1}. node ${ref.id}  " + (ref.location?.toString() ?: "not in this area") + if (ref.tagged) " · tagged" else ""
                    root.addView(if (ref.location != null) link(label, OsmId(OsmKind.NODE, ref.id)) else mono(label, Colors.MUTED))
                }
            }
            is OsmObject.Relation -> {
                section(root, "Relation members (${obj.members.size})")
                missing(root, detail, "members")
                detail.members.forEachIndexed { index, row ->
                    root.addView(if (row.present) link(row.text(index), row.member.id) else mono(row.text(index), Colors.MUTED))
                }
            }
        }
    }

    private fun showFindingEditor(
        root: LinearLayout,
        detail: ObjectDetail,
        current: Finding?,
        snapshotTimestamp: String?,
    ) {
        val proposed = EditText(this).apply {
            hint = "wheelchair=yes\nkerb=lowered"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            setText(current?.let { ProposedTags.format(it.proposedTags) }.orEmpty())
        }
        val note = EditText(this).apply {
            hint = "Observation or context"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            setText(current?.note.orEmpty())
        }
        val evidence = EditText(this).apply {
            hint = "Evidence: measurement, sign, source or photo reference"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            setText(current?.evidence.orEmpty())
        }
        val reviewed = CheckBox(this).apply {
            text = "Ready for human review and export"
            isChecked = current?.status == FindingStatus.REVIEWED
        }
        val error = text("", 13f, color = Colors.ERROR)
        val form = vertical(
            8,
            text("Proposed OSM tags (one key=value per line)", 13f, bold = true),
            proposed,
            text("Note", 13f, bold = true),
            note,
            text("Evidence", 13f, bold = true),
            evidence,
            reviewed,
            error,
        )
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (current == null) "Record field finding" else "Edit field finding")
            .setView(form)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val proposedTags = try {
                    ProposedTags.parse(proposed.text.toString())
                } catch (problem: IllegalArgumentException) {
                    error.text = problem.message
                    return@setOnClickListener
                }
                val noteValue = note.text.toString().trim()
                val evidenceValue = evidence.text.toString().trim()
                if (proposedTags.isEmpty() && noteValue.isEmpty() && evidenceValue.isEmpty()) {
                    error.text = "Add a proposed tag, note or evidence."
                    return@setOnClickListener
                }
                val obj = checkNotNull(detail.obj)
                val finding = current?.copy(
                    proposedTags = proposedTags,
                    note = noteValue,
                    evidence = evidenceValue,
                    status = if (reviewed.isChecked) FindingStatus.REVIEWED else FindingStatus.DRAFT,
                ) ?: Finding(
                    osmId = detail.id,
                    baseVersion = obj.metadata?.version?.takeIf { it > 0 }
                        ?: (obj as? OsmObject.Node)?.locationVersion?.takeIf { it > 0 }?.toLong(),
                    baseTags = obj.tags.toMap(),
                    snapshotTimestamp = snapshotTimestamp,
                    observedAt = Instant.now(),
                    proposedTags = proposedTags,
                    note = noteValue,
                    evidence = evidenceValue,
                    status = if (reviewed.isChecked) FindingStatus.REVIEWED else FindingStatus.DRAFT,
                    anchor = FindingAnchor.from(detail.shape),
                )
                dialog.dismiss()
                saveFinding(root, detail, finding, snapshotTimestamp)
            }
        }
        dialog.show()
    }

    private fun saveFinding(
        root: LinearLayout,
        detail: ObjectDetail,
        finding: Finding,
        snapshotTimestamp: String?,
    ) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { repository.upsert(finding) }
                render(root, detail, finding, snapshotTimestamp)
            } catch (error: Exception) {
                AlertDialog.Builder(this@ObjectActivity)
                    .setTitle("Field finding")
                    .setMessage("Could not save finding: ${error.message}")
                    .setPositiveButton("Close", null)
                    .show()
            }
        }
    }

    private fun deleteFinding(
        root: LinearLayout,
        detail: ObjectDetail,
        finding: Finding?,
        snapshotTimestamp: String?,
    ) {
        if (finding == null) return
        AlertDialog.Builder(this)
            .setTitle("Delete this finding?")
            .setMessage("The app-owned observation is removed. The offline OSM object is unchanged.")
            .setPositiveButton("Delete") { _, _ ->
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { repository.delete(finding.osmId) }
                        render(root, detail, null, snapshotTimestamp)
                    } catch (error: Exception) {
                        AlertDialog.Builder(this@ObjectActivity)
                            .setTitle("Field finding")
                            .setMessage("Could not delete finding: ${error.message}")
                            .setPositiveButton("Close", null)
                            .show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun missing(root: LinearLayout, detail: ObjectDetail, what: String) {
        val text = if (detail.missingReferences == 0) "All ${detail.references} $what are in this area."
        else "${detail.missingReferences} of ${detail.references} $what are not in this area (outside the extract)."
        root.addView(text(text, 13f, color = if (detail.missingReferences == 0) Colors.MUTED else Colors.ERROR))
    }

    private fun describe(m: ObjectMetadata): String = buildString {
        // Absent source fields are stored as 0/"" by the core: show them as unknown.
        appendLine("version   ${m.version.takeIf { it > 0 } ?: "unknown"}")
        appendLine("timestamp ${if (m.timestampSeconds > 0) Instant.ofEpochSecond(m.timestampSeconds).toString() else "unknown"}")
        appendLine("changeset ${m.changeset.takeIf { it > 0 } ?: "unknown"}")
        append("user      ${m.user.ifEmpty { "unknown" }}${if (m.uid > 0) " (uid ${m.uid})" else ""}")
    }

    private fun section(root: LinearLayout, title: String) {
        root.addView(text(title, 17f, bold = true).apply { setPadding(0, dp(16), 0, dp(4)) })
    }

    /** A row that opens the navigator for [target]. */
    private fun link(label: String, target: OsmId): View =
        text(label, 13f, color = Colors.LINK).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, dp(6), 0, dp(6)) // a finger-sized target
            setOnClickListener {
                startActivity(Intent(this@ObjectActivity, ObjectActivity::class.java).putExtra(EXTRA_ID, Labels.path(target)))
            }
        }

    private fun showOnMap(id: OsmId) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_SHOW, Labels.path(id)),
        )
    }

    private fun openOsmOrg(id: OsmId) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Labels.osmOrgUrl(id))))
        } catch (_: ActivityNotFoundException) {
            Unit // no browser installed: nothing to open
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ID = "id"
    }
}
