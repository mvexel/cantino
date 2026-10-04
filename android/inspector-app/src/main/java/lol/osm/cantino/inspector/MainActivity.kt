package lol.osm.cantino.inspector

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import lol.osm.cantino.AreaInfo
import lol.osm.cantino.AreaManager
import lol.osm.cantino.AreaState
import lol.osm.cantino.BasemapSource
import lol.osm.cantino.Bbox
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Entry point and area flow, trimmed from android/cafe-app MainActivity.kt
 * (the café app demonstrates the download lifecycle in detail; Inspector
 * shows one progress line):
 *
 *   location (or debug override, or typed "lat,lon") → offer
 *     → AreaManager.download(…, BasemapSource.Extract(latest Protomaps build, z15))
 *       a full import (no import profile: Inspector exists to show everything)
 *     → Ready → map ([MapScreen]).
 *
 * A published area goes straight to the map, fully offline. "Refresh" and
 * "Download another area" on the map re-run the download (full replace;
 * one area per app).
 */
class MainActivity : Activity() {
    private val scope = MainScope()
    private lateinit var areas: AreaManager
    private var debugLocation: LatLon? = null
    private var debugLaunch = DebugLaunch()

    private var mapScreen: MapScreen? = null
    private var started = false
    private var resumed = false
    private var progressLine: ProgressLine? = null
    private var locateJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        areas = AreaManager(this)
        debugLocation = DebugLocation.read(this, intent)
        debugLaunch = DebugLaunch.read(this, intent)
        setContentView(vertical(children = arrayOf(text("Opening…"))))

        scope.launch {
            val stateFlow = areas.state(InspectorStore.AREA_ID)
            val published = withContext(Dispatchers.IO) { areas.publishedArea(InspectorStore.AREA_ID) }
            val first = stateFlow.first()
            when {
                first.isRunning() -> showProgress()
                published != null -> showMap()
                first is AreaState.Failed -> showFailure(first.message, first.retryable)
                else -> startFirstRun()
            }
            stateFlow.collect { onAreaState(it) }
        }
    }

    /** "Show on map" from the object navigator comes back here (CLEAR_TOP | SINGLE_TOP). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_SHOW)?.let(Labels::parsePath)?.let { mapScreen?.select(it, moveCamera = true) }
    }

    // ---- first run: location → offer ---------------------------------------

    private fun startFirstRun() {
        debugLocation?.let { return offerDownload(it, LocationSource.DEBUG_OVERRIDE) }
        if (DeviceLocation.hasPermission(this)) {
            locate()
        } else {
            showMessage("Inspector", "To download an offline area around you, the app needs your location once.")
            requestPermissions(DeviceLocation.PERMISSIONS, REQUEST_LOCATION)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != REQUEST_LOCATION) return
        if (DeviceLocation.hasPermission(this)) locate() else showLocationChooser("Location permission was not granted.")
    }

    private fun locate() {
        showMessage("Finding your location…", "Using the device's location service (up to 30 s).")
        locateJob?.cancel()
        locateJob = scope.launch {
            val here = DeviceLocation.current(this@MainActivity)
            if (here != null) offerDownload(here, LocationSource.DEVICE) else showLocationChooser("No location fix was available.")
        }
    }

    /** Type coordinates or use a preset (also "Download another area" from the map). */
    private fun showLocationChooser(reason: String) {
        mapScreen?.destroy(started, resumed)
        mapScreen = null
        val input = EditText(this).apply {
            hint = "lat,lon (e.g. 40.7608,-111.8910)"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val error = text("", color = Colors.ERROR)
        setScreen(
            vertical(
                children = arrayOf(
                    text("Choose an area", 22f, bold = true),
                    text("$reason Pick the centre of the area to download.".trim()),
                    input,
                    error,
                    button("Use these coordinates") {
                        val point = parseLatLon(input.text.toString())
                        if (point == null) error.text = "Enter latitude,longitude in degrees." else offerDownload(point, LocationSource.MANUAL)
                    },
                    button("Salt Lake City downtown") { offerDownload(SLC_DOWNTOWN, LocationSource.MANUAL) },
                    button("Zürich centre") { offerDownload(ZURICH_CENTRE, LocationSource.MANUAL) },
                    button("Try my location again") { startFirstRun() },
                ),
            ),
        )
    }

    /** The offer, with the one-line privacy note. */
    private fun offerDownload(center: LatLon, source: LocationSource) {
        val km = Geo.AREA_SIZE_KM.toInt()
        val body = "About $km × $km km around $center: every OpenStreetMap object (a full import) and a basemap, " +
            "so you can inspect the data without a connection.\n\n" +
            "Privacy: the area's bounds (≈ your location) are sent to SliceOSM and the Protomaps tile host."
        showMessage("Inspector", "Area centre ($center, ${source.label}).")
        if (debugLaunch.autoDownload) {
            showMessage("Download an offline area?", body)
            scope.launch { kotlinx.coroutines.delay(1000); startDownload(center) } // time to see (and screenshot) the offer
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Download an offline area?")
            .setMessage(body)
            .setPositiveButton("Download") { _, _ -> startDownload(center) }
            .setNegativeButton("Choose another place") { _, _ -> showLocationChooser("") }
            .setCancelable(false)
            .show()
    }

    // ---- download -----------------------------------------------------------

    private fun startDownload(center: LatLon, bbox: Bbox? = null) {
        val line = showProgress()
        line.set("Finding the newest basemap build", null)
        scope.launch {
            val planet = try {
                ProtomapsBuilds.latestUrl() // network lookup (HEAD requests); demo only
            } catch (error: IOException) {
                Log.w(TAG, "basemap build lookup failed", error)
                return@launch showFailure("Could not reach the basemap host: ${error.message}", retryable = true)
            }
            Log.i(TAG, "basemap source $planet")
            areas.download(
                InspectorStore.AREA_ID,
                bbox ?: Bbox.around(center.lat, center.lon, Geo.AREA_SIZE_KM),
                name = "inspector %.4f,%.4f".format(java.util.Locale.ROOT, center.lat, center.lon),
                basemap = BasemapSource.Extract(planet, maxZoom = BASEMAP_MAX_ZOOM),
            )
        }
    }

    private fun onAreaState(state: AreaState) {
        Log.d(TAG, "state $state")
        val line = progressLine ?: if (state.isRunning()) showProgress() else return
        line.update(state)
        when (state) {
            is AreaState.Ready -> {
                Log.i(
                    TAG,
                    "ready: data ${state.area.dataFile?.length()} B, basemap ${state.area.basemapFile?.length()} B, " +
                        "counts ${state.area.metadata?.report?.counts}, snapshot ${state.area.metadata?.snapshotTimestamp}",
                )
                showMap()
            }
            is AreaState.Failed -> showFailure(state.message, state.retryable)
            is AreaState.Cancelled -> scope.launch {
                val published = withContext(Dispatchers.IO) { areas.publishedArea(InspectorStore.AREA_ID) }
                if (published != null) showMap() else showLocationChooser("Download cancelled.")
            }
            else -> Unit
        }
    }

    private fun showProgress(): ProgressLine {
        mapScreen?.destroy(started, resumed)
        mapScreen = null
        val line = ProgressLine()
        progressLine = line
        setScreen(line.view)
        return line
    }

    private fun showFailure(message: String, retryable: Boolean) {
        progressLine = null
        scope.launch {
            val published = withContext(Dispatchers.IO) { areas.publishedArea(InspectorStore.AREA_ID) }
            setScreen(
                vertical(
                    children = listOfNotNull(
                        text("Download failed", 22f, bold = true),
                        text(message),
                        text(
                            if (retryable) "This looks temporary (network or server). Retrying later may work."
                            else "The request or the data was rejected; retrying the same area may fail again.",
                            13f, color = Colors.MUTED,
                        ),
                        button("Choose an area") { showLocationChooser("") },
                        published?.let { button("Back to the map (previous area is kept)") { showMap() } },
                    ).toTypedArray(),
                ),
            )
        }
    }

    // ---- map ------------------------------------------------------------------

    private fun showMap() {
        progressLine = null
        mapScreen?.destroy(started, resumed)
        val screen = MapScreen(this, scope, debugLaunch, onRefresh = ::confirmRefresh, onAnotherArea = { showLocationChooser("") })
        debugLaunch = debugLaunch.copy(autoDownload = false)
        mapScreen = screen
        setScreen(screen.view)
        screen.create()
        if (started) screen.mapView.onStart()
        if (resumed) screen.mapView.onResume()
    }

    /** Refresh = full re-download of the same bbox, after the same privacy note. */
    private fun confirmRefresh(area: AreaInfo) {
        val bbox = area.metadata?.bbox ?: return
        AlertDialog.Builder(this)
            .setTitle("Refresh the offline area?")
            .setMessage(
                "Downloads fresh data and basemap for the same area. The current area stays usable until the new one is complete.\n\n" +
                    "Privacy: the area's bounds are sent to SliceOSM and the Protomaps tile host.",
            )
            .setPositiveButton("Refresh") { _, _ -> startDownload(Geo.center(bbox), bbox) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- plumbing ---------------------------------------------------------------

    private fun showMessage(title: String, body: String) {
        setScreen(vertical(children = arrayOf(text(title, 22f, bold = true), text(body))))
    }

    private fun setScreen(view: View) {
        // targetSdk 36 draws edge to edge: a wrapper takes the system-bar insets as padding.
        useLightSystemBars()
        setContentView(android.widget.FrameLayout(this).apply {
            fitsSystemWindows = true
            setBackgroundColor(android.graphics.Color.WHITE)
            addView(view)
        })
    }

    override fun onStart() { super.onStart(); started = true; mapScreen?.mapView?.onStart() }
    override fun onResume() { super.onResume(); resumed = true; mapScreen?.mapView?.onResume() }
    override fun onPause() { resumed = false; mapScreen?.mapView?.onPause(); super.onPause() }
    override fun onStop() { started = false; mapScreen?.mapView?.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapScreen?.mapView?.onLowMemory() }
    override fun onDestroy() {
        mapScreen?.destroy(started, resumed)
        scope.cancel()
        super.onDestroy()
    }

    /** One progress line: the phase, and bytes or a fraction when known. */
    private inner class ProgressLine {
        private val line = text("", 16f)
        private val bar = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            isIndeterminate = true
        }
        val view: View = vertical(
            children = arrayOf(
                text("Downloading the offline area", 22f, bold = true),
                line,
                bar,
                button("Cancel download") { areas.cancel(InspectorStore.AREA_ID) },
            ),
        )

        fun set(message: String, fraction: Double?) {
            line.text = message
            bar.isIndeterminate = fraction == null
            if (fraction != null) bar.progress = (fraction.coerceIn(0.0, 1.0) * 1000).toInt()
        }

        fun update(state: AreaState) = when (state) {
            is AreaState.Queued -> set("Waiting for network (attempt ${state.previousRuns + 1})", null)
            is AreaState.Submitting -> set("Requesting the extract", null)
            is AreaState.Slicing -> set("SliceOSM is cutting the extract" + (state.fraction?.let { " · %.0f %%".format(it * 100) } ?: ""), state.fraction)
            is AreaState.Downloading -> set("OSM data · " + bytes(state.bytes, state.totalBytes), state.totalBytes?.let { state.bytes.toDouble() / it })
            is AreaState.Importing -> set("Importing (full import, building indexes)", null)
            is AreaState.Basemap -> set("Basemap ${state.phase.name.lowercase()} · " + bytes(state.bytes, state.totalBytes), state.totalBytes?.let { state.bytes.toDouble() / it })
            is AreaState.Ready -> set("Ready", 1.0)
            is AreaState.Failed, is AreaState.Cancelled, is AreaState.Idle -> Unit
        }

        private fun bytes(bytes: Long, total: Long?) = if (total != null && total > 0) "${formatBytes(bytes)} of ${formatBytes(total)}" else formatBytes(bytes)
    }

    private fun AreaState.isRunning() = when (this) {
        is AreaState.Queued, is AreaState.Submitting, is AreaState.Slicing, is AreaState.Downloading,
        is AreaState.Importing, is AreaState.Basemap -> true
        else -> false
    }

    companion object {
        private const val TAG = "Inspector"
        private const val REQUEST_LOCATION = 1
        private const val BASEMAP_MAX_ZOOM = 15
        const val EXTRA_SHOW = "show"
        val SLC_DOWNTOWN = LatLon(40.7608, -111.8910)
        val ZURICH_CENTRE = LatLon(47.3744, 8.5410)
    }
}
