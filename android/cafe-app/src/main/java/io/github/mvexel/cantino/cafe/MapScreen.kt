package io.github.mvexel.cantino.cafe

import android.app.Activity
import android.content.Intent
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import io.github.mvexel.cantino.AreaInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The map screen: offline basemap from the area's PMTiles, cafés from the
 * offline OSM store as a GeoJSON overlay, filters, and a "nearby" list.
 *
 * Data flow: [CafeStore.withStore] (store thread) → [CafeLoader] → plain
 * [Cafe] values on the main thread → filtered in Kotlin → GeoJSON string for
 * MapLibre and rows for the list. Opening hours are evaluated at the moment
 * of filtering (and again on resume), so "open now" means now.
 *
 * Filters keep unknown separate: "Open" never includes Unknown and "Closed"
 * never includes Unknown; the Unknown choice shows exactly the cafés we cannot
 * decide about.
 */
class MapScreen(
    private val activity: Activity,
    private val scope: CoroutineScope,
    private val debugLocation: LatLon?,
    private val onRefresh: (AreaInfo) -> Unit,
) {
    val mapView: MapView
    val view: View

    private val title = activity.text("Cafés", 20f, bold = true)
    private val status = activity.text("Loading cafés from the offline area…", 12f, color = Colors.MUTED)
    private val list = ListView(activity)
    private val toggle = activity.button("List") { toggleList() }
    private val outdoorGroup = RadioGroup(activity)
    private val openGroup = RadioGroup(activity)

    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var area: AreaInfo? = null
    private var cafes: List<Cafe> = emptyList()
    private var reference: ReferenceLocation? = null
    private var outdoorFilter = OutdoorFilter.ANY
    private var openFilter = OpenFilter.ANY
    private var rows: List<Row> = emptyList()

    private enum class OutdoorFilter(val label: String) { ANY("Any"), YES("Yes"), NO("No"), UNKNOWN("Unknown") }
    private enum class OpenFilter(val label: String) { ANY("Any"), OPEN("Open"), CLOSED("Closed"), UNKNOWN("Unknown") }

    /** A café with everything evaluated for display at one instant. */
    private data class Row(val cafe: Cafe, val open: OpenState, val outdoor: OutdoorSeating, val distance: Double?)

    init {
        MapLibre.getInstance(activity)
        mapView = MapView(activity)
        list.visibility = View.GONE
        list.setOnItemClickListener { _, _, position, _ -> openDetail(rows[position].cafe) }

        buildFilterGroup(outdoorGroup, "Outdoor", OutdoorFilter.entries.map { it.label }) {
            outdoorFilter = OutdoorFilter.entries[it]; applyFilters()
        }
        buildFilterGroup(openGroup, "Now", OpenFilter.entries.map { it.label }) {
            openFilter = OpenFilter.entries[it]; applyFilters()
        }

        val header = activity.horizontal(
            title.apply { layoutParams = weighted() },
            toggle,
            activity.button("Refresh area") { area?.let(onRefresh) },
        ).apply { gravity = Gravity.CENTER_VERTICAL }
        val legend = activity.text(legendText(), 12f)
        val content = FrameLayout(activity).apply {
            addView(mapView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            list.setBackgroundColor(android.graphics.Color.WHITE)
        }
        view = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = activity.dp(8)
            addView(activity.vertical(8, header, status, outdoorGroup, openGroup, legend).apply { setPadding(pad, 0, pad, 0) })
            addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    /** Call once the view is attached: loads the area and the map. */
    fun create() {
        list.adapter = adapter // declared below init, so attached here
        mapView.onCreate(null)
        scope.launch {
            val loaded = try {
                CafeStore.withStore(activity) { store, area -> area to area.metadata?.bbox?.let { CafeLoader.load(store, it) } }
            } catch (error: Exception) {
                Log.e(TAG, "loading cafés failed", error)
                status.text = "Could not open the offline area: ${error.message}"
                return@launch
            }
            val (area, loadedCafes) = loaded
            this@MapScreen.area = area
            cafes = loadedCafes.orEmpty()
            if (loadedCafes == null) status.text = "The area has no metadata (bbox unknown); cannot search it."
            reference = withContext(Dispatchers.IO) { referenceLocation(area) }
            setupMap(area)
            applyFilters()
        }
    }

    /** "Nearby" origin: debug override, else a fresh device fix inside the area, else the area centre. */
    private fun referenceLocation(area: AreaInfo): ReferenceLocation? {
        debugLocation?.let { return ReferenceLocation(it, LocationSource.DEBUG_OVERRIDE) }
        val bbox = area.metadata?.bbox ?: return null
        DeviceLocation.lastKnown(activity)?.takeIf { Geo.contains(bbox, it) }?.let { return ReferenceLocation(it, LocationSource.DEVICE) }
        return ReferenceLocation(Geo.center(bbox), LocationSource.AREA_CENTRE)
    }

    private fun setupMap(area: AreaInfo) {
        val bbox = area.metadata?.bbox
        val center = reference?.point ?: bbox?.let(Geo::center) ?: LatLon(0.0, 0.0)
        mapView.getMapAsync { map ->
            this.map = map
            map.cameraPosition = CameraPosition.Builder().target(LatLng(center.lat, center.lon)).zoom(14.5).build()
            bbox?.let {
                // The basemap covers only the area: keep the camera over it.
                map.setLatLngBoundsForCameraTarget(LatLngBounds.from(it.north, it.east, it.south, it.west))
                map.setMinZoomPreference(10.0)
            }
            map.setStyle(Style.Builder().fromJson(styleJson(area))) { style ->
                this.style = style
                addOverlay(style)
                applyFilters()
            }
            map.addOnMapClickListener { point ->
                val screen = map.projection.toScreenLocation(point)
                val slop = activity.dp(14).toFloat()
                val hit = map.queryRenderedFeatures(RectF(screen.x - slop, screen.y - slop, screen.x + slop, screen.y + slop), CAFE_LAYER)
                    .firstOrNull() ?: return@addOnMapClickListener false
                val key = hit.getStringProperty("key")
                cafes.firstOrNull { keyOf(it) == key }?.let(::openDetail)
                true
            }
        }
    }

    /** The bundled Protomaps style, pointed at this area's PMTiles (or a blank style without a basemap). */
    private fun styleJson(area: AreaInfo): String {
        val url = area.pmtilesUrl ?: return """{"version":8,"glyphs":"asset://glyphs/{fontstack}/{range}.pbf","sources":{},"layers":[{"id":"bg","type":"background","paint":{"background-color":"#f2efe9"}}]}"""
        return activity.assets.open("style.json").bufferedReader().use { it.readText() }.replace("__PMTILES_URL__", url)
    }

    private fun addOverlay(style: Style) {
        style.addSource(GeoJsonSource(CAFE_SOURCE, EMPTY))
        style.addSource(GeoJsonSource(ME_SOURCE, EMPTY))
        style.addLayer(
            CircleLayer(ME_LAYER, ME_SOURCE).withProperties(
                PropertyFactory.circleRadius(7f),
                PropertyFactory.circleColor(Colors.hex(Colors.ME)),
                PropertyFactory.circleStrokeColor("#ffffff"),
                PropertyFactory.circleStrokeWidth(3f),
            ),
        )
        style.addLayer(
            CircleLayer(CAFE_LAYER, CAFE_SOURCE).withProperties(
                PropertyFactory.circleRadius(6.5f),
                PropertyFactory.circleColor(
                    Expression.match(
                        Expression.get("open"),
                        Expression.color(Colors.UNKNOWN),
                        Expression.stop("open", Expression.color(Colors.OPEN)),
                        Expression.stop("closed", Expression.color(Colors.CLOSED)),
                    ),
                ),
                PropertyFactory.circleStrokeColor("#ffffff"),
                PropertyFactory.circleStrokeWidth(2f),
            ),
        )
        style.addLayer(
            SymbolLayer(LABEL_LAYER, CAFE_SOURCE).withProperties(
                PropertyFactory.textField(Expression.get("name")),
                PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
                PropertyFactory.textSize(11f),
                PropertyFactory.textOffset(arrayOf(0f, 1.1f)),
                PropertyFactory.textAnchor("top"),
                PropertyFactory.textOptional(true),
                PropertyFactory.textHaloColor("#ffffff"),
                PropertyFactory.textHaloWidth(1.5f),
            ).apply { minZoom = 14f },
        )
    }

    /** Re-evaluates "open now" (call on resume) and redraws. */
    fun refreshStatuses() = applyFilters()

    private fun applyFilters() {
        val now = now()
        val origin = reference?.point
        val all = cafes.map { cafe ->
            Row(cafe, cafe.openState(now), cafe.outdoorSeating, if (origin != null && cafe.location != null) Geo.distanceMeters(origin, cafe.location) else null)
        }
        val byOutdoor = all.filter { matchesOutdoor(it.outdoor) }
        rows = byOutdoor.filter { matchesOpen(it.open) }.sortedWith(compareBy(nullsLast()) { it.distance })

        // Counts per choice (given the other filter), so Unknown is visible as a number.
        updateCounts(outdoorGroup, OutdoorFilter.entries.map { f -> all.filter { matchesOpen(it.open) }.count { outdoorMatches(f, it.outdoor) } })
        updateCounts(openGroup, OpenFilter.entries.map { f -> byOutdoor.count { openMatches(f, it.open) } })

        title.text = "Cafés · ${rows.size} of ${all.size}"
        val area = area
        if (area != null) {
            val snapshot = area.metadata?.snapshotTimestamp?.let(SNAPSHOT_FORMAT::format) ?: "unknown"
            val ref = reference?.let { "distances from ${it.source.label}" } ?: "no reference location"
            status.text = "Offline area · OSM data as of $snapshot UTC · $ref"
        }
        adapter.notifyDataSetChanged()

        val style = style ?: return
        (style.getSource(CAFE_SOURCE) as? GeoJsonSource)?.setGeoJson(geoJson(rows))
        reference?.let { ref ->
            (style.getSource(ME_SOURCE) as? GeoJsonSource)?.setGeoJson(
                JSONObject().put("type", "FeatureCollection").put("features", JSONArray().put(point(ref.point, JSONObject()))).toString(),
            )
        }
    }

    private fun matchesOutdoor(value: OutdoorSeating) = outdoorMatches(outdoorFilter, value)
    private fun matchesOpen(value: OpenState) = openMatches(openFilter, value)

    private fun outdoorMatches(filter: OutdoorFilter, value: OutdoorSeating) = when (filter) {
        OutdoorFilter.ANY -> true
        OutdoorFilter.YES -> value == OutdoorSeating.Yes
        OutdoorFilter.NO -> value == OutdoorSeating.No
        OutdoorFilter.UNKNOWN -> value is OutdoorSeating.Unknown
    }

    private fun openMatches(filter: OpenFilter, value: OpenState) = when (filter) {
        OpenFilter.ANY -> true
        OpenFilter.OPEN -> value == OpenState.Open
        OpenFilter.CLOSED -> value == OpenState.Closed
        OpenFilter.UNKNOWN -> value is OpenState.Unknown
    }

    private fun geoJson(rows: List<Row>): String {
        val features = JSONArray()
        rows.forEach { row ->
            val location = row.cafe.location ?: return@forEach
            val properties = JSONObject()
                .put("key", keyOf(row.cafe))
                .put("name", row.cafe.name)
                .put("open", when (row.open) { OpenState.Open -> "open"; OpenState.Closed -> "closed"; is OpenState.Unknown -> "unknown" })
            features.put(point(location, properties))
        }
        return JSONObject().put("type", "FeatureCollection").put("features", features).toString()
    }

    private fun point(at: LatLon, properties: JSONObject) = JSONObject()
        .put("type", "Feature")
        .put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray().put(at.lon).put(at.lat)))
        .put("properties", properties)

    private fun keyOf(cafe: Cafe) = "${cafe.id.kind.name.lowercase()}/${cafe.id.id}"

    private fun openDetail(cafe: Cafe) {
        activity.startActivity(
            Intent(activity, DetailActivity::class.java)
                .putExtra(DetailActivity.EXTRA_KIND, cafe.id.kind.name)
                .putExtra(DetailActivity.EXTRA_ID, cafe.id.id),
        )
    }

    private fun toggleList() {
        val showList = list.visibility != View.VISIBLE
        list.visibility = if (showList) View.VISIBLE else View.GONE
        toggle.text = if (showList) "Map" else "List"
    }

    private fun buildFilterGroup(group: RadioGroup, label: String, choices: List<String>, onChoice: (Int) -> Unit) {
        group.orientation = RadioGroup.HORIZONTAL
        group.gravity = Gravity.CENTER_VERTICAL
        group.addView(activity.text("$label:", 13f, bold = true).apply { minWidth = activity.dp(62) })
        choices.forEachIndexed { index, choice ->
            group.addView(
                RadioButton(activity).apply {
                    id = View.generateViewId()
                    text = choice
                    tag = choice
                    textSize = 12f
                    isChecked = index == 0
                    setOnClickListener { onChoice(index) }
                },
            )
        }
    }

    private fun updateCounts(group: RadioGroup, counts: List<Int>) {
        var index = 0
        for (i in 0 until group.childCount) {
            val button = group.getChildAt(i) as? RadioButton ?: continue
            button.text = "${button.tag} ${counts[index++]}"
        }
    }

    private fun legendText(): CharSequence = android.text.SpannableStringBuilder().apply {
        listOf(OpenState.Open, OpenState.Closed, OpenState.Unknown("")).forEach {
            append(coloredStatus(it))
            append("   ")
        }
        append(android.text.SpannableString("● you").apply {
            setSpan(android.text.style.ForegroundColorSpan(Colors.ME), 0, 1, 0)
        })
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = rows[position]
            val name = activity.text(row.cafe.name, 16f, bold = true)
            val distance = row.distance?.let(Geo::formatDistance) ?: "no location in area"
            val line = activity.text(
                android.text.SpannableStringBuilder()
                    .append("$distance · ")
                    .append(coloredStatus(row.open))
                    .append(" · ${row.outdoor.label()}"),
                13f,
            )
            val hours = row.cafe.tags["opening_hours"]
            val raw = activity.text(
                when {
                    hours == null -> "no opening_hours"
                    row.open is OpenState.Unknown -> "opening_hours=$hours (${(row.open as OpenState.Unknown).reason})"
                    else -> "opening_hours=$hours"
                },
                12f, color = Colors.MUTED,
            ).apply { setTypeface(Typeface.MONOSPACE) }
            return activity.vertical(10, name, line, raw)
        }
    }

    /** Tears the map down, unwinding only the lifecycle steps the map actually went through. */
    fun destroy(started: Boolean, resumed: Boolean) {
        if (resumed) mapView.onPause()
        if (started) mapView.onStop()
        mapView.onDestroy()
    }

    private companion object {
        const val TAG = "CafeMap"
        const val CAFE_SOURCE = "cafes"
        const val CAFE_LAYER = "cafe-points"
        const val LABEL_LAYER = "cafe-labels"
        const val ME_SOURCE = "me"
        const val ME_LAYER = "me-point"
        const val EMPTY = """{"type":"FeatureCollection","features":[]}"""

        /** Snapshot age as shown in the status line ("2026-10-03 20:30", UTC). */
        val SNAPSHOT_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC)
    }
}
