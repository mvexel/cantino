package io.github.mvexel.cantino.sample

import android.app.Activity
import android.os.Bundle
import java.io.File
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * Renders the offline Protomaps basemap from app assets.
 *
 * MapLibre's PMTiles source range-reads its file, which it cannot do on an
 * `asset://` URL inside the APK, so the archive is copied to filesDir once and
 * referenced as `pmtiles://file://<abs path>`. Glyphs and sprites stay in
 * assets (`asset://`), which MapLibre reads whole.
 *
 * Debug camera override: `am start -n <pkg>/.MainActivity --ef zoom 15`
 * (also `--ef lat`, `--ef lon`).
 */
class MainActivity : Activity() {
    private lateinit var mapView: MapView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        mapView = MapView(this)
        setContentView(mapView)
        mapView.onCreate(savedInstanceState)

        val pmtiles = installAsset(PMTILES_ASSET)
        val styleJson = assets.open(STYLE_ASSET).bufferedReader().use { it.readText() }
            .replace(PMTILES_PLACEHOLDER, "pmtiles://file://${pmtiles.absolutePath}")
        val camera = CameraPosition.Builder()
            .target(
                LatLng(
                    intent.getFloatExtra("lat", 40.7608f).toDouble(),
                    intent.getFloatExtra("lon", -111.8910f).toDouble(),
                ),
            )
            .zoom(intent.getFloatExtra("zoom", 13f).toDouble())
            .build()

        mapView.getMapAsync { map ->
            map.cameraPosition = camera
            map.setStyle(Style.Builder().fromJson(styleJson))
        }
    }

    /**
     * Copies an (uncompressed) asset to filesDir unless a copy of the same
     * length is already there. Writes to a temp file and renames, so a killed
     * first launch never leaves a truncated archive that looks complete.
     */
    private fun installAsset(name: String): File {
        val target = File(filesDir, name)
        val length = assets.openFd(name).use { it.length }
        if (target.length() == length) return target
        val tmp = File(filesDir, "$name.part")
        assets.open(name).use { input -> tmp.outputStream().use { input.copyTo(it) } }
        check(tmp.renameTo(target)) { "rename $tmp -> $target failed" }
        return target
    }

    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { mapView.onPause(); super.onPause() }
    override fun onStop() { mapView.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onDestroy() { mapView.onDestroy(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    private companion object {
        const val PMTILES_ASSET = "slc.pmtiles"
        const val STYLE_ASSET = "style.json"
        const val PMTILES_PLACEHOLDER = "__PMTILES_URL__"
    }
}
