package lol.osm.cantino.inspector

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import lol.osm.cantino.ObjectMetadata
import lol.osm.cantino.OsmId
import lol.osm.cantino.OsmKind
import lol.osm.cantino.OsmObject
import java.time.Instant
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_ID)?.let(Labels::parsePath) ?: return finish()
        title = Labels.kindId(id)
        val root = vertical(16, text("Loading ${Labels.kindId(id)}…"))
        setContentView(ScrollView(this).apply { fitsSystemWindows = true; setBackgroundColor(android.graphics.Color.WHITE); addView(root) })
        useLightSystemBars()

        scope.launch {
            val detail = try {
                InspectorStore.withStore(this@ObjectActivity) { store, _ -> Inspect.detail(store, id) }
            } catch (error: Exception) {
                root.removeAllViews()
                root.addView(text("Could not read the offline area: ${error.message}", color = Colors.ERROR))
                return@launch
            }
            render(root, detail)
        }
    }

    private fun render(root: LinearLayout, detail: ObjectDetail) {
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
