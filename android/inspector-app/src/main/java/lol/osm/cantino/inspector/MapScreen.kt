package lol.osm.cantino.inspector

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import lol.osm.cantino.AreaInfo
import lol.osm.cantino.Bbox
import lol.osm.cantino.CantinoException
import lol.osm.cantino.OsmId
import lol.osm.cantino.OsmObject
import lol.osm.cantino.PmtilesInfo
import lol.osm.cantino.TagFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import java.time.Instant

/**
 * The map screen: the offline basemap, the query bar, tap-to-inspect and the
 * selected object's geometry.
 *
 * - Query bar ([QueryBar]): terms ANDed, optional "this view only" (the
 *   visible bbox), pages of [Queries.PAGE] with "Load more" (keyset `after`),
 *   "Count all". Results are drawn (nodes as dots, ways as lines; relations are
 *   listed only) and listed. Errors are shown verbatim, Cantino's included.
 * - Tap: [Inspect.tap] lists every bbox candidate around the tap, hit or near.
 * - Selecting a row highlights its geometry, broken where it leaves the area;
 *   "Inspect" opens the object navigator ([ObjectActivity]).
 *
 * All store work runs in [InspectorStore.withStore] (the store's thread);
 * this class only holds plain values. Mirrors ios/inspector-app MapScreen.swift.
 */
class MapScreen(
    private val activity: Activity,
    private val scope: CoroutineScope,
    private var debug: DebugLaunch,
    private val onRefresh: (AreaInfo) -> Unit,
    private val onAnotherArea: () -> Unit,
) {
    val mapView: MapView
    val view: View

    private val input = EditText(activity)
    private val viewOnly = CheckBox(activity)
    private val status = activity.text("Loading the offline area…", 12f, color = Colors.MUTED)
    private val panelTitle = activity.text("", 14f, bold = true)
    private val inspectButton = activity.button("Inspect") { selected?.let(::openObject) }
    private val list = ListView(activity)
    private val loadMore = activity.button("Load more") { loadPage() }
    private val countAll = activity.button("Count all") { countAll() }
    private val footer = activity.horizontal(loadMore, countAll)
    private val panel = LinearLayout(activity)
    private val panelHeight = (activity.resources.displayMetrics.heightPixels * 0.42).toInt()

    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var area: AreaInfo? = null

    /** The current query: filters, bbox (view only), loaded results, the next page's `after`. */
    private var filters: List<TagFilter> = emptyList()
    private var queryBbox: Bbox? = null
    private var results: List<OsmObject> = emptyList()
    private var resultsShape = Shape()
    private var after: OsmId? = null
    private var hasMore = false
    private var tap: TapResult? = null
    private var selected: OsmId? = null
    private var mode = Mode.NONE

    private enum class Mode { NONE, RESULTS, TAP }

    /** A list row: what it shows and what it selects. */
    private data class Row(val id: OsmId, val title: String, val detail: String)
    private var rows: List<Row> = emptyList()

    init {
        MapLibre.getInstance(activity)
        mapView = MapView(activity)
        input.apply {
            hint = "amenity=cafe  shop=*  !opening_hours"
            setSingleLine()
            textSize = 15f
            typeface = android.graphics.Typeface.MONOSPACE
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, action, event ->
                if (action == EditorInfo.IME_ACTION_SEARCH || event?.keyCode == KeyEvent.KEYCODE_ENTER) { runQuery(); true } else false
            }
        }
        viewOnly.text = "This view only"
        viewOnly.textSize = 13f
        list.setOnItemClickListener { _, _, position, _ -> select(rows[position].id, moveCamera = false) }

        panel.apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.WHITE)
            elevation = activity.dp(8).toFloat()
            val pad = activity.dp(8)
            setPadding(pad, pad / 2, pad, 0)
            addView(
                activity.horizontal(
                    panelTitle.apply { layoutParams = weighted() },
                    inspectButton,
                    activity.button("×") { closePanel() },
                ).apply { gravity = Gravity.CENTER_VERTICAL },
            )
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(footer)
            visibility = View.GONE
        }

        val bar = activity.horizontal(
            input.apply { layoutParams = weighted() },
            activity.button("Run") { runQuery() },
        ).apply { gravity = Gravity.CENTER_VERTICAL }
        val tools = activity.horizontal(
            viewOnly.apply { layoutParams = weighted() },
            activity.button("Checks") { showChecks() },
            activity.button("Area") { showAreaMenu() },
        ).apply { gravity = Gravity.CENTER_VERTICAL }
        val content = FrameLayout(activity).apply {
            addView(mapView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, panelHeight, Gravity.BOTTOM))
        }
        view = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = activity.dp(8)
            addView(activity.vertical(0, bar, tools, status).apply { setPadding(pad, 0, pad, pad / 2) })
            addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    /** Call once the view is attached: opens the area and the map. */
    fun create() {
        list.adapter = adapter // declared below init, so attached here
        mapView.onCreate(null)
        scope.launch {
            val loaded = try {
                InspectorStore.withStore(activity) { _, area -> area }
            } catch (error: Exception) {
                Log.e(TAG, "opening the area failed", error)
                return@launch showError("Could not open the offline area: ${error.message}")
            }
            area = loaded
            status.text = idleStatus()
            setupMap(loaded)
        }
    }

    private fun idleStatus(): String {
        val snapshot = area?.metadata?.snapshotTimestamp?.let(::formatInstant) ?: "unknown"
        return "Offline · OSM data as of $snapshot · tap the map to inspect"
    }

    private fun setupMap(area: AreaInfo) {
        val bbox = area.metadata?.bbox
        val center = debug.tap ?: bbox?.let(Geo::center) ?: LatLon(0.0, 0.0)
        mapView.getMapAsync { map ->
            this.map = map
            // The bottom panel covers part of the map: centre the camera in the part above it.
            map.cameraPosition = CameraPosition.Builder().target(LatLng(center.lat, center.lon))
                .zoom(debug.zoom ?: if (debug.tap != null) 18.0 else 15.0)
                .padding(0.0, 0.0, 0.0, panelHeight.toDouble()).build()
            bbox?.let {
                // The basemap covers only the area: keep the camera over it.
                map.setLatLngBoundsForCameraTarget(LatLngBounds.from(it.north, it.east, it.south, it.west))
                map.setMinZoomPreference(11.0)
            }
            map.setStyle(Style.Builder().fromJson(styleJson(area))) { style ->
                this.style = style
                addOverlay(style)
                redraw()
                runDebugLaunch()
            }
            map.addOnMapClickListener { point ->
                // About 20 dp around the finger, in metres at this zoom (bounded for very low and high zooms).
                val radius = (map.projection.getMetersPerPixelAtLatitude(point.latitude) * activity.dp(20)).coerceIn(3.0, 60.0)
                runTap(LatLon(point.latitude, point.longitude), radius)
                true
            }
        }
    }

    /** The automation options (see [DebugLaunch]), once the map is ready. */
    private fun runDebugLaunch() {
        val launch = debug
        debug = DebugLaunch() // once per launch
        launch.query?.let { input.setText(it); viewOnly.isChecked = launch.viewOnly; runQuery() }
        launch.tap?.let { runTap(it, launch.tapRadius ?: HitTest.DEFAULT_RADIUS_M, selectIndex = launch.select) }
        launch.obj?.let(::openObject)
        if (launch.about) showAbout()
        if (launch.counts) runCounts()
    }

    /** The bundled Protomaps style, pointed at this area's PMTiles (blank without a basemap). */
    private fun styleJson(area: AreaInfo): String {
        val url = area.pmtilesUrl ?: return """{"version":8,"glyphs":"asset://glyphs/{fontstack}/{range}.pbf","sources":{},"layers":[{"id":"bg","type":"background","paint":{"background-color":"#f2efe9"}}]}"""
        return activity.assets.open("style.json").bufferedReader().use { it.readText() }.replace("__PMTILES_URL__", url)
    }

    private fun addOverlay(style: Style) {
        listOf(RESULTS, HIGHLIGHT, TAP).forEach { style.addSource(GeoJsonSource(it, EMPTY)) }
        val isPoint = Expression.eq(Expression.geometryType(), Expression.literal("Point"))
        val isLine = Expression.eq(Expression.geometryType(), Expression.literal("LineString"))
        style.addLayer(
            LineLayer("$RESULTS-lines", RESULTS).withProperties(
                PropertyFactory.lineColor(Colors.hex(Colors.RESULT)),
                PropertyFactory.lineWidth(3f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            ).withFilter(isLine),
        )
        style.addLayer(
            CircleLayer("$RESULTS-points", RESULTS).withProperties(
                PropertyFactory.circleRadius(5f),
                PropertyFactory.circleColor(Colors.hex(Colors.RESULT)),
                PropertyFactory.circleStrokeColor("#ffffff"),
                PropertyFactory.circleStrokeWidth(1.5f),
            ).withFilter(isPoint),
        )
        style.addLayer(
            LineLayer("$HIGHLIGHT-lines", HIGHLIGHT).withProperties(
                PropertyFactory.lineColor(Colors.hex(Colors.HIGHLIGHT)),
                PropertyFactory.lineWidth(6f),
                PropertyFactory.lineOpacity(0.85f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ).withFilter(isLine),
        )
        style.addLayer(
            CircleLayer("$HIGHLIGHT-points", HIGHLIGHT).withProperties(
                PropertyFactory.circleRadius(8f),
                PropertyFactory.circleColor(Colors.hex(Colors.HIGHLIGHT)),
                PropertyFactory.circleStrokeColor("#ffffff"),
                PropertyFactory.circleStrokeWidth(2f),
            ).withFilter(isPoint),
        )
        style.addLayer(
            CircleLayer(TAP, TAP).withProperties(
                PropertyFactory.circleRadius(4f),
                PropertyFactory.circleColor("#212121"),
                PropertyFactory.circleStrokeColor("#ffffff"),
                PropertyFactory.circleStrokeWidth(2f),
            ),
        )
    }

    // ---- query --------------------------------------------------------------

    private fun runQuery() {
        hideKeyboard()
        val parsed = try {
            QueryBar.parse(input.text.toString())
        } catch (error: QuerySyntaxException) {
            return showError("Query: ${error.message}")
        }
        filters = parsed
        queryBbox = if (viewOnly.isChecked) visibleBbox() else null
        results = emptyList()
        resultsShape = Shape()
        after = null
        hasMore = false
        tap = null
        mode = Mode.RESULTS
        loadPage()
    }

    private fun loadPage() {
        val filters = filters
        val bbox = queryBbox
        val after = after
        status.text = "Querying…"
        scope.launch {
            val (page, shape) = try {
                InspectorStore.withStore(activity) { store, _ ->
                    val page = Queries.page(store, filters, bbox, after)
                    page to Inspect.shapes(store, page)
                }
            } catch (error: Exception) {
                return@launch showError(verbatim(error))
            }
            results = results + page
            resultsShape += shape
            hasMore = page.size == Queries.PAGE
            this@MapScreen.after = page.lastOrNull()?.id ?: after
            status.text = "${QueryBar.format(filters).ifEmpty { "(no filter)" }}${if (bbox != null) " · this view" else " · whole area"}"
            showResults()
        }
    }

    private fun showResults() {
        mode = Mode.RESULTS
        rows = results.map { Row(it.id, title(it.tags, it.id), "${Labels.kindId(it.id)} · ${Labels.primaryTag(it.tags)}") }
        val more = if (hasMore) "+" else ""
        panelTitle.text = "${results.size}$more results" + (selected?.let { " · selected ${Labels.kindId(it)}" } ?: "")
        footer.visibility = View.VISIBLE
        loadMore.isEnabled = hasMore
        openPanel()
        redraw()
    }

    private fun countAll() {
        val filters = filters
        val bbox = queryBbox
        scope.launch {
            val counts = try {
                InspectorStore.withStore(activity) { store, _ -> Queries.count(store, filters, bbox) }
            } catch (error: Exception) {
                return@launch showError(verbatim(error))
            }
            status.text = "${QueryBar.format(filters).ifEmpty { "(no filter)" }}: $counts"
        }
    }

    private fun showChecks() {
        val checks = Checks.ALL
        AlertDialog.Builder(activity)
            .setTitle("Checks")
            .setItems(checks.map { "${it.title}\n${it.query}" }.toTypedArray()) { _, which ->
                input.setText(checks[which].query)
                runQuery()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Counts every acceptance query over the whole area (debug `counts`), logged for the record. */
    private fun runCounts() {
        status.text = "Counting the acceptance queries…"
        scope.launch {
            val lines = try {
                InspectorStore.withStore(activity) { store, area ->
                    listOf("snapshot ${area.metadata?.snapshotTimestamp}") +
                        Checks.ACCEPTANCE.map { q -> "$q\t${Queries.count(store, QueryBar.parse(q), null)}" }
                }
            } catch (error: Exception) {
                return@launch showError(verbatim(error))
            }
            lines.forEach { Log.i(COUNTS_TAG, it) }
            status.text = idleStatus()
            showTextDialog("Acceptance counts", lines.joinToString("\n"))
        }
    }

    // ---- tap and selection ----------------------------------------------------

    private fun runTap(at: LatLon, radiusMeters: Double, selectIndex: Int? = null) {
        scope.launch {
            val result = try {
                InspectorStore.withStore(activity) { store, _ -> Inspect.tap(store, at, radiusMeters) }
            } catch (error: Exception) {
                return@launch showError(verbatim(error))
            }
            tap = result
            mode = Mode.TAP
            selected = null
            highlight(Shape())
            result.candidates.forEach { Log.i(TAG, "tap ${result.at} r=${result.radiusMeters}: ${it.match} ${Labels.kindId(it.id)} ${it.distanceMeters} ${Labels.primaryTag(it.tags)}") }
            showTap()
            selectIndex?.let { result.candidates.getOrNull(it) }?.let { select(it.id, moveCamera = false) }
        }
    }

    private fun showTap() {
        val result = tap ?: return
        rows = result.candidates.map { c ->
            val distance = c.distanceMeters?.let(Geo::formatDistance) ?: "distance unknown"
            Row(c.id, title(c.tags, c.id), "${c.match.label} · $distance · ${Labels.kindId(c.id)} · ${Labels.primaryTag(c.tags)}")
        }
        val hits = result.candidates.count { it.match == Match.HIT }
        panelTitle.text = "${result.candidates.size}${if (result.truncated) "+" else ""} here: $hits hit, " +
            "${result.candidates.size - hits} near (r ${result.radiusMeters.toInt()} m)" +
            (selected?.let { " · selected ${Labels.kindId(it)}" } ?: "")
        status.text = "Tap at ${result.at}: candidates from a bbox query; \"hit\" = on the node or way line"
        footer.visibility = View.GONE
        openPanel()
        redraw()
    }

    /** Highlights [id]'s geometry (gaps where vertices are outside the area); optionally moves the camera to it. */
    fun select(id: OsmId, moveCamera: Boolean) {
        scope.launch {
            val shape = try {
                InspectorStore.withStore(activity) { store, _ -> store.get(id)?.let { Inspect.shape(store, it) } }
            } catch (error: Exception) {
                return@launch showError(verbatim(error))
            }
            selected = id
            if (mode == Mode.NONE) { rows = emptyList(); panelTitle.text = "" }
            highlight(shape ?: Shape())
            when (mode) {
                Mode.TAP -> showTap()
                Mode.RESULTS -> showResults()
                Mode.NONE -> { panelTitle.text = "Selected ${Labels.kindId(id)}"; footer.visibility = View.GONE; openPanel() }
            }
            if (shape == null) status.text = "${Labels.kindId(id)} is not in this area"
            if (moveCamera && shape != null) moveTo(shape)
        }
    }

    private fun moveTo(shape: Shape) {
        val map = map ?: return
        val points = shape.allPoints()
        if (points.isEmpty()) return
        if (points.distinct().size == 1) {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(points[0].lat, points[0].lon), 18.0))
        } else {
            val bounds = LatLngBounds.Builder().includes(points.map { LatLng(it.lat, it.lon) }).build()
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, activity.dp(48)))
        }
    }

    private fun highlight(shape: Shape) {
        (style?.getSource(HIGHLIGHT) as? GeoJsonSource)?.setGeoJson(geoJson(shape))
    }

    private fun redraw() {
        val style = style ?: return
        (style.getSource(RESULTS) as? GeoJsonSource)?.setGeoJson(geoJson(if (mode == Mode.RESULTS) resultsShape else Shape()))
        val tapShape = if (mode == Mode.TAP) tap?.let { Shape(points = listOf(it.at)) } ?: Shape() else Shape()
        (style.getSource(TAP) as? GeoJsonSource)?.setGeoJson(geoJson(tapShape))
        adapter.notifyDataSetChanged()
        inspectButton.visibility = if (selected != null) View.VISIBLE else View.GONE
    }

    private fun openPanel() { panel.visibility = View.VISIBLE; inspectButton.visibility = if (selected != null) View.VISIBLE else View.GONE }

    private fun closePanel() {
        panel.visibility = View.GONE
        mode = Mode.NONE
        selected = null
        tap = null
        highlight(Shape())
        redraw()
        status.text = idleStatus()
    }

    private fun openObject(id: OsmId) {
        activity.startActivity(
            Intent(activity, ObjectActivity::class.java).putExtra(ObjectActivity.EXTRA_ID, Labels.path(id)),
        )
    }

    // ---- area -----------------------------------------------------------------

    private fun showAreaMenu() {
        AlertDialog.Builder(activity)
            .setItems(arrayOf("About this area", "Refresh area", "Download another area")) { _, which ->
                when (which) {
                    0 -> showAbout()
                    1 -> area?.let(onRefresh)
                    2 -> onAnotherArea()
                }
            }
            .show()
    }

    private fun showAbout() {
        val area = area ?: return
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                val info = area.basemapFile?.let { f -> runCatching { PmtilesInfo.read(f) }.getOrNull() }
                About.text(area, info, Instant.now())
            }
            showTextDialog("About this area", text)
        }
    }

    private fun showTextDialog(title: String, body: String) {
        val view = ScrollView(activity).apply { addView(activity.vertical(16, activity.mono(body))) }
        AlertDialog.Builder(activity).setTitle(title).setView(view).setPositiveButton("Close", null).show()
    }

    // ---- helpers ----------------------------------------------------------------

    private fun visibleBbox(): Bbox? {
        val bounds = map?.projection?.visibleRegion?.latLngBounds ?: return null
        return Bbox(bounds.longitudeWest, bounds.latitudeSouth, bounds.longitudeEast, bounds.latitudeNorth)
    }

    private fun showError(message: String) {
        status.text = message
        status.setTextColor(Colors.ERROR)
        status.postDelayed({ status.setTextColor(Colors.MUTED) }, 8_000)
        Log.w(TAG, message)
    }

    /** Errors as they come: Cantino's category and message, unedited. */
    private fun verbatim(error: Exception): String = when (error) {
        is CantinoException -> "Cantino ${error::class.simpleName}: ${error.message}"
        else -> "${error::class.simpleName}: ${error.message}"
    }

    private fun hideKeyboard() {
        activity.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
    }

    private fun title(tags: Map<String, String>, id: OsmId) = tags["name"] ?: if (tags.isEmpty()) Labels.kindId(id) else Labels.primaryTag(tags)

    private fun geoJson(shape: Shape): String {
        val features = JSONArray()
        fun coords(p: LatLon) = JSONArray().put(p.lon).put(p.lat)
        fun feature(geometry: JSONObject) = JSONObject().put("type", "Feature").put("properties", JSONObject()).put("geometry", geometry)
        shape.lines.forEach { line ->
            // A run of one vertex (its neighbours are outside the area) is drawn as a point.
            features.put(
                if (line.size == 1) feature(JSONObject().put("type", "Point").put("coordinates", coords(line[0])))
                else feature(JSONObject().put("type", "LineString").put("coordinates", JSONArray().apply { line.forEach { put(coords(it)) } })),
            )
        }
        shape.points.forEach { features.put(feature(JSONObject().put("type", "Point").put("coordinates", coords(it)))) }
        return JSONObject().put("type", "FeatureCollection").put("features", features).toString()
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = rows[position]
            val chosen = row.id == selected
            return activity.vertical(
                6,
                activity.text(row.title, 15f, bold = true, color = if (chosen) Colors.HIGHLIGHT else Colors.TEXT),
                activity.text(row.detail, 12f, color = if (row.detail.startsWith("hit")) Colors.HIT else Colors.MUTED),
            )
        }
    }

    /** Tears the map down, unwinding only the lifecycle steps the map actually went through. */
    fun destroy(started: Boolean, resumed: Boolean) {
        if (resumed) mapView.onPause()
        if (started) mapView.onStop()
        mapView.onDestroy()
    }

    private companion object {
        const val TAG = "InspectorMap"
        const val COUNTS_TAG = "InspectorCounts"
        const val RESULTS = "results"
        const val HIGHLIGHT = "highlight"
        const val TAP = "tap"
        const val EMPTY = """{"type":"FeatureCollection","features":[]}"""
    }
}
