package io.github.mvexel.cantino.cafe

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import io.github.mvexel.cantino.AreaInfo
import io.github.mvexel.cantino.AreaManager
import io.github.mvexel.cantino.AreaState
import io.github.mvexel.cantino.Bbox
import io.github.mvexel.cantino.BasemapPhase
import io.github.mvexel.cantino.BasemapSource
import io.github.mvexel.cantino.ProtomapsBuilds
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Entry point and first-run flow (TODO.md §6, Martijn's flow):
 *
 *   location permission → current location (LocationManager)
 *     → offer "download ~10×10 km around you (map data + basemap)?"
 *     → AreaManager.download(…, BasemapSource.Extract(latest Protomaps build, z15))
 *       with honest progress (phase, bytes, fraction when known)
 *     → Ready → map ([MapScreen]).
 *
 * Fallbacks: permission denied or no fix → type "lat,lon" or pick the Salt
 * Lake City downtown preset. Download failure → retry (the previous area, if
 * any, is kept by the framework). "Refresh area" on the map re-runs the
 * download for the same area (full replace).
 *
 * Every later launch with a published area goes straight to the map, which
 * works fully offline (airplane mode). The download state is observed for
 * the activity's lifetime, so a relaunch during a download shows its progress.
 *
 * Debug location override (debuggable builds): see [DebugLocation].
 */
class MainActivity : Activity() {
    private val scope = MainScope()
    private lateinit var areas: AreaManager
    private var debugLocation: LatLon? = null

    private var mapScreen: MapScreen? = null
    private var started = false
    private var resumed = false

    /** Non-null while the progress screen is showing (our download, or one found running). */
    private var progress: ProgressScreen? = null
        set(value) {
            if (field !== value) field?.close() // stop the old screen's ticker
            field = value
        }
    private var locateJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        areas = AreaManager(this)
        debugLocation = DebugLocation.read(this, intent)
        DebugShowWhenLocked.read(this, intent)
        DebugShowWhenLocked.apply(this)
        setContentView(vertical(children = arrayOf(text("Opening…"))))

        scope.launch {
            val stateFlow = areas.state(CafeStore.AREA_ID)
            val published = withContext(Dispatchers.IO) { areas.publishedArea(CafeStore.AREA_ID) }
            // Route on the first observed state, then keep following it.
            val first = stateFlow.first()
            when {
                first.isRunning() -> showProgress(requested = Settings.requested(this@MainActivity))
                published != null -> showMap()
                first is AreaState.Failed -> showFailure(first.message, first.retryable)
                else -> startFirstRun()
            }
            stateFlow.collect { onAreaState(it) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        debugLocation = DebugLocation.read(this, intent)
        DebugShowWhenLocked.read(this, intent)
        DebugShowWhenLocked.apply(this)
    }

    // ---- first run: location → offer ---------------------------------------

    private fun startFirstRun() {
        debugLocation?.let { return offerDownload(it, LocationSource.DEBUG_OVERRIDE) }
        if (DeviceLocation.hasPermission(this)) {
            locate()
        } else {
            showMessage(
                "Offline cafés",
                "To download an offline area around you, the app needs your location once.",
            )
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

    /** Fallback when there is no location: type coordinates or use a preset. */
    private fun showLocationChooser(reason: String) {
        val input = EditText(this).apply {
            hint = "lat,lon (e.g. 40.7608,-111.8910)"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val error = text("", color = Colors.CLOSED)
        setScreen(
            vertical(
                children = arrayOf(
                    text("Choose an area", 22f, bold = true),
                    text("$reason Pick the centre of the area to download instead.".trim()),
                    input,
                    error,
                    button("Use these coordinates") {
                        val point = parseLatLon(input.text.toString())
                        if (point == null) error.text = "Enter latitude,longitude in degrees." else offerDownload(point, LocationSource.MANUAL)
                    },
                    button("Use Salt Lake City downtown") { offerDownload(SLC_DOWNTOWN, LocationSource.MANUAL) },
                    button("Try my location again") { startFirstRun() },
                ),
            ),
        )
    }

    /** The offer, with the one-line privacy note. */
    private fun offerDownload(center: LatLon, source: LocationSource) {
        val km = Geo.AREA_SIZE_KM.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }
        showMessage("Offline cafés", "Area centre ($center, ${source.label}).")
        AlertDialog.Builder(this)
            .setTitle("Download an offline area?")
            .setMessage(
                "About $km × $km km around $center: map data (to find cafés) and a basemap, " +
                    "so the app works without a connection.\n\n" +
                    "Privacy: the area's bounds (≈ your location) are sent to SliceOSM and the Protomaps tile host.",
            )
            .setPositiveButton("Download") { _, _ -> startDownload(center) }
            .setNegativeButton("Choose another place") { _, _ -> showLocationChooser("") }
            .setCancelable(false)
            .show()
    }

    // ---- download -----------------------------------------------------------

    private fun startDownload(center: LatLon) {
        Settings.setRequested(this, center)
        val screen = showProgress(center)
        screen.phase("Finding the newest basemap build")
        scope.launch {
            // Network lookup (HEAD requests); demo only, see ProtomapsBuilds.
            val planet = try {
                ProtomapsBuilds.latestUrl()
            } catch (error: IOException) {
                Log.w(TAG, "basemap build lookup failed", error)
                return@launch showFailure("Could not reach the basemap host: ${error.message}", retryable = true)
            }
            Log.i(TAG, "basemap source $planet")
            areas.download(
                CafeStore.AREA_ID,
                Bbox.around(center.lat, center.lon, Geo.AREA_SIZE_KM),
                name = "cafes %.4f,%.4f".format(center.lat, center.lon),
                basemap = BasemapSource.Extract(planet, maxZoom = BASEMAP_MAX_ZOOM),
            )
        }
    }

    private fun onAreaState(state: AreaState) {
        val screen = progress
        Log.d(TAG, "state $state")
        if (screen == null) {
            // A download we did not start from this screen (e.g. still running
            // from before a relaunch): show it.
            if (state.isRunning()) showProgress(Settings.requested(this)).update(state)
            return
        }
        screen.update(state)
        when (state) {
            is AreaState.Ready -> {
                screen.finish(state.area)
                progress = null
                showMap()
            }
            is AreaState.Failed -> showFailure(state.message, state.retryable)
            AreaState.Cancelled -> {
                progress = null
                scope.launch {
                    val published = withContext(Dispatchers.IO) { areas.publishedArea(CafeStore.AREA_ID) }
                    if (published != null) showMap() else showLocationChooser("Download cancelled.")
                }
            }
            else -> Unit
        }
    }

    private fun showProgress(requested: LatLon?): ProgressScreen {
        mapScreen?.destroy(started, resumed)
        mapScreen = null
        val screen = ProgressScreen(requested)
        progress = screen
        setScreen(screen.view)
        return screen
    }

    private fun showFailure(message: String, retryable: Boolean) {
        progress = null
        scope.launch {
            val published = withContext(Dispatchers.IO) { areas.publishedArea(CafeStore.AREA_ID) }
            val requested = Settings.requested(this@MainActivity)
            val buttons = listOfNotNull(
                requested?.let { button("Retry") { startDownload(it) } },
                button("Choose another place") { showLocationChooser("") },
                published?.let { button("Back to the map (previous area is kept)") { showMap() } },
            )
            setScreen(
                vertical(
                    children = arrayOf(
                        text("Download failed", 22f, bold = true),
                        text(message),
                        text(
                            if (retryable) "This looks temporary (network or server). Retrying later may work."
                            else "The request or the data was rejected; retrying the same area may fail again.",
                            13f, color = Colors.MUTED,
                        ),
                        *buttons.toTypedArray(),
                    ),
                ),
            )
        }
    }

    // ---- map ------------------------------------------------------------------

    private fun showMap() {
        progress = null
        mapScreen?.destroy(started, resumed)
        val screen = MapScreen(this, scope, debugLocation, onRefresh = ::confirmRefresh)
        mapScreen = screen
        setScreen(screen.view)
        screen.create()
        if (started) screen.mapView.onStart()
        if (resumed) screen.mapView.onResume()
    }

    /** Refresh = full re-download of the same area (same bbox), after the same privacy note. */
    private fun confirmRefresh(area: AreaInfo) {
        val center = area.metadata?.bbox?.let(Geo::center) ?: Settings.requested(this) ?: return
        AlertDialog.Builder(this)
            .setTitle("Refresh the offline area?")
            .setMessage(
                "Downloads fresh map data and basemap for the same area. The current area stays usable until the new one is complete.\n\n" +
                    "Privacy: the area's bounds are sent to SliceOSM and the Protomaps tile host.",
            )
            .setPositiveButton("Refresh") { _, _ -> startDownload(center) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- plumbing ---------------------------------------------------------------

    private fun showMessage(title: String, body: String) {
        setScreen(vertical(children = arrayOf(text(title, 22f, bold = true), text(body))))
    }

    private fun setScreen(view: View) {
        // targetSdk 36 draws edge to edge. A wrapper takes the system-bar
        // insets as its padding, so the screen's own padding is kept.
        useLightSystemBars()
        setContentView(android.widget.FrameLayout(this).apply {
            fitsSystemWindows = true
            setBackgroundColor(android.graphics.Color.WHITE)
            addView(view)
        })
    }

    override fun onStart() { super.onStart(); started = true; mapScreen?.mapView?.onStart() }
    override fun onResume() { super.onResume(); resumed = true; mapScreen?.mapView?.onResume(); mapScreen?.refreshStatuses() }
    override fun onPause() { resumed = false; mapScreen?.mapView?.onPause(); super.onPause() }
    override fun onStop() { started = false; mapScreen?.mapView?.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapScreen?.mapView?.onLowMemory() }
    override fun onDestroy() {
        mapScreen?.destroy(started, resumed)
        scope.cancel()
        super.onDestroy()
    }

    /**
     * The download progress screen. Shows the current phase with what is
     * actually known (a fraction only when the server reports one, bytes
     * with a total only when there is a total) and how long each finished
     * phase took. Phase timings also go to logcat (tag CafeDownload).
     */
    private inner class ProgressScreen(requested: LatLon?) {
        private val phaseText = text("", 17f, bold = true)
        private val detail = text("")
        private val bar = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            isIndeterminate = true
        }
        private val log = text("", 13f, color = Colors.MUTED)
        val view: View = ScrollView(this@MainActivity).apply {
            addView(
                vertical(
                    children = arrayOf(
                        text("Downloading your offline area", 22f, bold = true),
                        text(requested?.let { "${Geo.AREA_SIZE_KM.toInt()} × ${Geo.AREA_SIZE_KM.toInt()} km around $it" } ?: "", 13f, color = Colors.MUTED),
                        phaseText,
                        bar,
                        detail,
                        log,
                        button("Cancel download") { areas.cancel(CafeStore.AREA_ID) },
                    ),
                ),
            )
        }

        private val startedAt = SystemClock.elapsedRealtime()
        private var current: String? = null
        private var currentStart = startedAt
        private val finished = mutableListOf<String>()
        private val ticker = scope.launch {
            while (isActive) {
                delay(500)
                renderLog()
            }
        }

        fun close() = ticker.cancel()

        /** Starts a new named phase, closing the previous one. */
        fun phase(name: String) {
            if (name == current) return
            val now = SystemClock.elapsedRealtime()
            current?.let { previous ->
                val took = now - currentStart
                finished += "✓ $previous — %.1f s".format(took / 1000.0)
                Log.i(TIMING_TAG, "phase \"$previous\" took $took ms")
            }
            current = name
            currentStart = now
            phaseText.text = name
            renderLog()
        }

        fun update(state: AreaState) {
            when (state) {
                is AreaState.Queued -> {
                    phase(if (state.previousRuns == 0) "Waiting for network" else "Waiting to retry (attempt ${state.previousRuns + 1})")
                    indeterminate("Queued in WorkManager (needs a connection and free storage).")
                }
                AreaState.Submitting -> { phase("Requesting the extract (SliceOSM)"); indeterminate("") }
                is AreaState.Slicing -> {
                    phase("SliceOSM is cutting the extract")
                    val fraction = state.fraction
                    if (fraction == null) indeterminate("Waiting for the server to report progress.")
                    else determinate(fraction, "%.0f %% sliced".format(fraction * 100))
                }
                is AreaState.Downloading -> {
                    phase("Downloading OSM data")
                    bytes(state.bytes, state.totalBytes)
                }
                AreaState.Importing -> { phase("Importing into the offline database"); indeterminate("Building indexes on the device.") }
                is AreaState.Basemap -> {
                    phase(
                        when (state.phase) {
                            BasemapPhase.DOWNLOAD -> "Downloading the basemap"
                            BasemapPhase.DIRECTORIES -> "Basemap: reading tile directories"
                            BasemapPhase.TILES -> "Basemap: downloading tiles"
                        },
                    )
                    bytes(state.bytes, state.totalBytes)
                }
                is AreaState.Ready, is AreaState.Failed, AreaState.Cancelled, is AreaState.Idle -> Unit
            }
        }

        /** Ready: close the last phase and log the totals (sizes for the acceptance record). */
        fun finish(area: AreaInfo) {
            phase("Ready")
            ticker.cancel()
            val total = SystemClock.elapsedRealtime() - startedAt
            val metadata = area.metadata
            Log.i(
                TIMING_TAG,
                "ready in $total ms; data db ${area.dataFile.length()} B; pmtiles ${area.basemapFile?.length()} B; " +
                    "counts ${metadata?.report?.counts}; basemap ${metadata?.basemap}; snapshot ${metadata?.snapshotTimestamp}; bbox ${metadata?.bbox}",
            )
        }

        private fun bytes(bytes: Long, total: Long?) {
            if (total != null && total > 0) {
                determinate(bytes.toDouble() / total, "${formatBytes(bytes)} of ${formatBytes(total)}")
            } else {
                indeterminate("${formatBytes(bytes)} (total not known yet)")
            }
        }

        private fun determinate(fraction: Double, message: String) {
            bar.isIndeterminate = false
            bar.progress = (fraction.coerceIn(0.0, 1.0) * 1000).toInt()
            detail.text = message
        }

        private fun indeterminate(message: String) {
            bar.isIndeterminate = true
            detail.text = message
        }

        private fun renderLog() {
            val running = current?.let { "… $it — %.1f s".format((SystemClock.elapsedRealtime() - currentStart) / 1000.0) }
            val total = "Total %.1f s".format((SystemClock.elapsedRealtime() - startedAt) / 1000.0)
            log.text = (finished + listOfNotNull(running, total)).joinToString("\n")
        }
    }

    private fun AreaState.isRunning() = when (this) {
        is AreaState.Queued, AreaState.Submitting, is AreaState.Slicing, is AreaState.Downloading,
        AreaState.Importing, is AreaState.Basemap -> true
        else -> false
    }

    private fun parseLatLon(input: String): LatLon? {
        val parts = input.split(',').map { it.trim().toDoubleOrNull() }
        if (parts.size != 2 || parts.any { it == null }) return null
        val (lat, lon) = parts.map { it!! }
        return if (lat in -85.0..85.0 && lon in -180.0..180.0) LatLon(lat, lon) else null
    }

    companion object {
        private const val TAG = "CafeApp"
        private const val TIMING_TAG = "CafeDownload"
        private const val REQUEST_LOCATION = 1
        private const val BASEMAP_MAX_ZOOM = 15
        val SLC_DOWNTOWN = LatLon(40.7608, -111.8910)
    }
}

/** The centre of the last requested download, for Retry and for a relaunch mid-download. */
object Settings {
    private const val PREFS = "cafe"

    fun requested(context: android.content.Context): LatLon? {
        val prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        if (!prefs.contains("requested_lat")) return null
        return LatLon(prefs.getFloat("requested_lat", 0f).toDouble(), prefs.getFloat("requested_lon", 0f).toDouble())
    }

    fun setRequested(context: android.content.Context, point: LatLon) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit()
            .putFloat("requested_lat", point.lat.toFloat())
            .putFloat("requested_lon", point.lon.toFloat())
            .apply()
    }
}
