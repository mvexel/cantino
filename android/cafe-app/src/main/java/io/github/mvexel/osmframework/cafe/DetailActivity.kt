package io.github.mvexel.osmframework.cafe

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.mvexel.osmframework.Metadata
import io.github.mvexel.osmframework.OsmId
import io.github.mvexel.osmframework.OsmKind
import io.github.mvexel.osmframework.OsmObject
import java.time.Instant
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Inspector for one OSM object of the offline area: any kind, not only cafés.
 *
 * Shows, as stored by the framework (nothing interpreted except the clearly
 * labelled "App interpretation" block for cafés):
 * - every raw tag, sorted by key;
 * - metadata (version, timestamp, changeset, user), or "not stored" for
 *   untagged nodes imported without metadata (only their location version);
 * - the underlying object: a node's coordinate; a way's node list in order,
 *   repeats included (a closed way repeats its first node), each tappable;
 *   a relation's members (role, kind, id), each tappable, and marked
 *   "not in this area" when [io.github.mvexel.osmframework.OsmStore.get]
 *   returns null (extracts are clipped at the area edge).
 *
 * All store access goes through [StoreWorker] (its one thread). Opened with
 * extras [EXTRA_KIND] (an [OsmKind] name) and [EXTRA_ID].
 */
class DetailActivity : Activity() {
    private val scope = MainScope()

    /** What the store thread hands to the UI: plain values only. */
    private data class Model(val obj: OsmObject?, val nodeRefs: List<Pair<Long, LatLon?>>, val members: List<MemberRow>, val point: LatLon?)
    private data class MemberRow(val member: OsmObject.Member, val present: Boolean, val name: String?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugShowWhenLocked.apply(this)
        val id = OsmId(OsmKind.valueOf(intent.getStringExtra(EXTRA_KIND) ?: "NODE"), intent.getLongExtra(EXTRA_ID, 0))
        title = "${id.kind.name.lowercase()} ${id.id}"
        val root = vertical(16, text("Loading ${title}…"))
        setContentView(ScrollView(this).apply { fitsSystemWindows = true; setBackgroundColor(android.graphics.Color.WHITE); addView(root) })
        useLightSystemBars()

        scope.launch {
            val model = try {
                StoreWorker.withStore(this@DetailActivity) { store, _ ->
                    val obj = store.get(id)
                    // Resolve node coordinates / member presence here, on the
                    // store thread, so the UI thread only renders.
                    val refs = (obj as? OsmObject.Way)?.nodes?.map { ref ->
                        ref to (store.get(OsmId(OsmKind.NODE, ref)) as? OsmObject.Node)?.let { LatLon(it.lat, it.lon) }
                    }.orEmpty()
                    val members = (obj as? OsmObject.Relation)?.members?.map { member ->
                        val target = store.get(member.id)
                        MemberRow(member, target != null, target?.tags?.get("name"))
                    }.orEmpty()
                    Model(obj, refs, members, obj?.let { CafeLoader.representativePoint(store, it) })
                }
            } catch (error: Exception) {
                root.removeAllViews()
                root.addView(text("Could not read the offline area: ${error.message}", color = Colors.CLOSED))
                return@launch
            }
            render(root, id, model)
        }
    }

    private fun render(root: LinearLayout, id: OsmId, model: Model) {
        root.removeAllViews()
        val obj = model.obj
        val kind = id.kind.name.lowercase()
        if (obj == null) {
            root.addView(text("$kind ${id.id}", 22f, bold = true))
            root.addView(text("Not in this area: the offline extract does not contain this object (it lies outside the area, or was clipped at its edge)."))
            return
        }
        val name = obj.tags["name"]
        root.addView(text(name ?: "$kind ${id.id}", 22f, bold = true))
        if (name != null) root.addView(text("$kind ${id.id}", 13f, color = Colors.MUTED))

        // App interpretation (cafés only), clearly separated from the raw data.
        if (obj.tags["amenity"] == "cafe") {
            section(root, "App interpretation")
            val open = OpeningHours.evaluate(obj.tags["opening_hours"], now())
            root.addView(text(coloredStatus(open)))
            if (open is OpenState.Unknown) root.addView(text("Why unknown: ${open.reason}", 13f, color = Colors.MUTED))
            root.addView(mono("opening_hours = ${obj.tags["opening_hours"] ?: "(missing)"}"))
            root.addView(text(OutdoorSeating.of(obj.tags).label()))
            model.point?.let {
                root.addView(text("Map point: $it" + if (obj !is OsmObject.Node) " (mean of its nodes)" else "", 13f, color = Colors.MUTED))
            }
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
                root.addView(mono("lat %.7f\nlon %.7f".format(obj.lat, obj.lon)))
            }
            is OsmObject.Way -> {
                val distinct = obj.nodes.distinct().size
                val closed = obj.nodes.size > 1 && obj.nodes.first() == obj.nodes.last()
                section(root, "Way nodes (${obj.nodes.size} refs, $distinct distinct${if (closed) ", closed" else ""})")
                model.nodeRefs.forEachIndexed { index, (ref, location) ->
                    val label = "${index + 1}. node $ref  " + (location?.toString() ?: "not in this area")
                    root.addView(if (location != null) link(label, OsmId(OsmKind.NODE, ref)) else mono(label, Colors.MUTED))
                }
            }
            is OsmObject.Relation -> {
                section(root, "Relation members (${obj.members.size})")
                model.members.forEachIndexed { index, row ->
                    val role = row.member.role.ifEmpty { "(no role)" }
                    val target = "${row.member.id.kind.name.lowercase()} ${row.member.id.id}"
                    val label = "${index + 1}. $role · $target" +
                        (row.name?.let { " · $it" } ?: "") + if (row.present) "" else " · not in this area"
                    root.addView(if (row.present) link(label, row.member.id) else mono(label, Colors.MUTED))
                }
            }
        }
    }

    private fun describe(m: Metadata): String = buildString {
        // Absent source fields are stored as 0/"" by the core: show them as unknown, not as real values.
        appendLine("version   ${m.version.takeIf { it > 0 } ?: "unknown"}")
        appendLine("timestamp ${if (m.timestamp > 0) Instant.ofEpochSecond(m.timestamp).toString() else "unknown"}")
        appendLine("changeset ${m.changeset.takeIf { it > 0 } ?: "unknown"}")
        append("user      ${m.user.ifEmpty { "unknown" }}${if (m.uid > 0) " (uid ${m.uid})" else ""}")
    }

    private fun section(root: LinearLayout, title: String) {
        root.addView(text(title, 17f, bold = true).apply { setPadding(0, dp(16), 0, dp(4)) })
    }

    private fun mono(value: String, color: Int = Colors.TEXT): TextView =
        text(value, 13f, color = color).apply { setTypeface(Typeface.MONOSPACE); setTextIsSelectable(true) }

    /** A row that opens the inspector for [target]. */
    private fun link(label: String, target: OsmId): View =
        text(SpannableStringBuilder(label), 13f, color = Colors.LINK).apply {
            setTypeface(Typeface.MONOSPACE)
            setPadding(0, dp(6), 0, dp(6)) // a finger-sized target
            setOnClickListener {
                startActivity(
                    Intent(this@DetailActivity, DetailActivity::class.java)
                        .putExtra(EXTRA_KIND, target.kind.name)
                        .putExtra(EXTRA_ID, target.id),
                )
            }
        }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_KIND = "kind"
        const val EXTRA_ID = "id"
    }
}
