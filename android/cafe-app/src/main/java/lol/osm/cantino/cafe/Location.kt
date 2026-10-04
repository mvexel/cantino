package lol.osm.cantino.cafe

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
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/*
 * Where the user is, without Google Play Services: the platform
 * LocationManager only. Also the DEBUG location override used for every
 * automated run, so tests never send the tester's real position to SliceOSM
 * or the PMTiles host.
 */

/** Where a reference location came from (shown in the UI next to distances). */
enum class LocationSource(val label: String) {
    DEBUG_OVERRIDE("debug location"),
    DEVICE("your location"),
    MANUAL("chosen location"),
    AREA_CENTRE("area centre"),
}

data class ReferenceLocation(val point: LatLon, val source: LocationSource)

/**
 * Debug override, debuggable builds only:
 *
 *     adb shell am start -n lol.osm.cantino.cafe/.MainActivity \
 *         --ef lat 40.7608 --ef lon -111.8910
 *
 * The override is sticky (saved in preferences) so that a relaunch without
 * extras still never asks the device for its real location; clear it with
 * `--ez clear_debug_location true`. When set, it replaces the device
 * location everywhere: the first-run area centre and the "nearby" origin.
 */
object DebugLocation {
    private const val PREFS = "debug"

    fun read(context: Context, intent: Intent?): LatLon? {
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (!debuggable) return null
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
 * The offer screen's preselected radius, debuggable builds only, not sticky:
 *
 *     adb shell am start -n lol.osm.cantino.cafe/.MainActivity --ef radius 2.5
 *
 * Must be one of [AreaRadius.CHOICES_KM] (km, half the side of the square);
 * anything else is ignored. The same option exists on iOS (`-radius 2.5`)
 * and in the Inspector app.
 */
object DebugRadius {
    fun read(context: Context, intent: Intent?): Double? {
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (!debuggable || intent == null || !intent.hasExtra("radius")) return null
        return AreaRadius.choice(intent.getFloatExtra("radius", Float.NaN).toDouble())
    }
}

/** "lat,lon" in degrees (the location chooser), or null. */
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

    /** A recent last-known fix (no radio use), or null. Works in airplane mode. */
    @SuppressLint("MissingPermission")
    fun lastKnown(context: Context, maxAgeMillis: Long = 30 * 60_000): LatLon? {
        if (!hasPermission(context)) return null
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
     * one new fix from the best enabled provider (fused on API 31+, then
     * network, then GPS), waiting at most [timeoutMillis]. Null when location
     * is off, denied, or no fix arrives in time.
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

/**
 * Test hook, debuggable builds only: `--ez show_when_locked true` lets the
 * app's screens show over a secure lock screen, so automated runs (adb
 * screenshots on a phone that locked itself mid-run) can proceed without
 * someone unlocking the device. Sticky for the process; never on in release.
 */
object DebugShowWhenLocked {
    @Volatile
    private var enabled = false

    fun read(context: Context, intent: Intent?) {
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (debuggable && intent?.getBooleanExtra("show_when_locked", false) == true) enabled = true
    }

    fun apply(activity: android.app.Activity) {
        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            activity.setShowWhenLocked(true)
            activity.setTurnScreenOn(true)
        }
    }
}
