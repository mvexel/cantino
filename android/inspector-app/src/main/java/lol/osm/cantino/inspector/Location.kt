package lol.osm.cantino.inspector

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import lol.osm.cantino.OsmId
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/*
 * Where the user is, without Google Play Services, and the debug launch
 * options for automated runs. DebugLocation and DeviceLocation are copied
 * from android/cafe-app Location.kt (preference names changed).
 */

/** Where the area centre came from (shown on the offer screen). */
enum class LocationSource(val label: String) {
    DEBUG_OVERRIDE("debug location"),
    DEVICE("your location"),
    MANUAL("chosen location"),
}

private fun Context.debuggable() = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

/**
 * Debug location override, debuggable builds only:
 *
 *     adb shell am start -n lol.osm.cantino.inspector/.MainActivity --ef lat 40.7608 --ef lon -111.8910
 *
 * Sticky (saved in preferences), so a relaunch without extras still never
 * asks the device for its real location; clear it with
 * `--ez clear_debug_location true`. It replaces the device location as the
 * first-run area centre.
 */
object DebugLocation {
    private const val PREFS = "debug"

    fun read(context: Context, intent: Intent?): LatLon? {
        if (!context.debuggable()) return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (intent?.getBooleanExtra("clear_debug_location", false) == true) prefs.edit().clear().apply()
        if (intent != null && intent.hasExtra("lat") && intent.hasExtra("lon")) {
            val lat = intent.getFloatExtra("lat", Float.NaN).toDouble()
            val lon = intent.getFloatExtra("lon", Float.NaN).toDouble()
            if (lat in -85.0..85.0 && lon in -180.0..180.0) {
                prefs.edit().putFloat("lat", lat.toFloat()).putFloat("lon", lon.toFloat()).apply()
            }
        }
        if (!prefs.contains("lat")) return null
        return LatLon(prefs.getFloat("lat", 0f).toDouble(), prefs.getFloat("lon", 0f).toDouble())
    }
}

/**
 * Launch options that stand in for taps in automated runs (debuggable builds
 * only, not sticky). The same options exist on iOS as launch arguments
 * (`-query "…"`); see docs/guide/inspector-app.md.
 *
 *     --ez auto_download true          accept the download offer
 *     --es query 'amenity=* !opening_hours' [--ez view_only true]   run a query
 *     --es tap 40.76080,-111.89100 [--ef radius 15]   tap there (candidates sheet)
 *     --ei select 0                    then select the candidate at that index
 *     --es object way/123              open the object navigator
 *     --ez about true                  open "About this area"
 *     --ez counts true                 count the acceptance queries (logcat tag InspectorCounts)
 *     --ef zoom 17                     camera zoom (centre: the tap, else the area centre)
 */
data class DebugLaunch(
    val autoDownload: Boolean = false,
    val query: String? = null,
    val viewOnly: Boolean = false,
    val tap: LatLon? = null,
    val radius: Double? = null,
    val select: Int? = null,
    val obj: OsmId? = null,
    val about: Boolean = false,
    val counts: Boolean = false,
    val zoom: Double? = null,
) {
    companion object {
        fun read(context: Context, intent: Intent?): DebugLaunch {
            if (intent == null || !context.debuggable()) return DebugLaunch()
            return DebugLaunch(
                autoDownload = intent.getBooleanExtra("auto_download", false),
                query = intent.getStringExtra("query"),
                viewOnly = intent.getBooleanExtra("view_only", false),
                tap = intent.getStringExtra("tap")?.let(::parseLatLon),
                radius = intent.getFloatExtra("radius", Float.NaN).toDouble().takeIf { !it.isNaN() && it > 0 },
                select = intent.getIntExtra("select", -1).takeIf { it >= 0 },
                obj = intent.getStringExtra("object")?.let(Labels::parsePath),
                about = intent.getBooleanExtra("about", false),
                counts = intent.getBooleanExtra("counts", false),
                zoom = intent.getFloatExtra("zoom", Float.NaN).toDouble().takeIf { !it.isNaN() },
            )
        }
    }
}

/** "lat,lon" in degrees, or null. */
fun parseLatLon(input: String): LatLon? {
    val parts = input.split(',').map { it.trim().toDoubleOrNull() }
    if (parts.size != 2 || parts.any { it == null }) return null
    val (lat, lon) = parts.map { it!! }
    return if (lat in -85.0..85.0 && lon in -180.0..180.0) LatLon(lat, lon) else null
}

object DeviceLocation {
    val PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun hasPermission(context: Context) =
        PERMISSIONS.any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** A recent last-known fix (no radio use), or null. */
    @SuppressLint("MissingPermission")
    private fun lastKnown(context: Context, maxAgeMillis: Long): LatLon? {
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        return manager.getProviders(true)
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .filter { now - it.time <= maxAgeMillis }
            .minByOrNull { it.accuracy }
            ?.let { LatLon(it.latitude, it.longitude) }
    }

    /**
     * A current fix: a fresh last-known location if there is one, otherwise
     * one new fix from the best enabled provider, waiting at most
     * [timeoutMillis]. Null when location is off, denied, or no fix arrives.
     */
    @SuppressLint("MissingPermission")
    suspend fun current(context: Context, timeoutMillis: Long = 30_000): LatLon? {
        if (!hasPermission(context)) return null
        lastKnown(context, maxAgeMillis = 2 * 60_000)?.let { return it }
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val enabled = manager.getProviders(true)
        val provider = listOf(FUSED, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { it in enabled } ?: return null
        val fix: Location? = withTimeoutOrNull(timeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val signal = CancellationSignal()
                    continuation.invokeOnCancellation { signal.cancel() }
                    manager.getCurrentLocation(provider, signal, context.mainExecutor) { location ->
                        if (continuation.isActive) continuation.resume(location)
                    }
                } else {
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            if (continuation.isActive) continuation.resume(location)
                        }
                    }
                    continuation.invokeOnCancellation { manager.removeUpdates(listener) }
                    @Suppress("DEPRECATION")
                    manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                }
            }
        }
        return fix?.let { LatLon(it.latitude, it.longitude) }
    }

    private const val FUSED = "fused" // LocationManager.FUSED_PROVIDER, API 31+
}
