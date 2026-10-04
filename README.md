# Cantino

**Cantino — an offline OpenStreetMap SDK.**

Cantino gives an Android app the raw OpenStreetMap data of an area the app
chooses, on the device, with no network. Your app picks a bounding box;
Cantino fetches a fresh extract from [SliceOSM](https://slice.openstreetmap.us/),
imports it into a compact SQLite file, optionally cuts a matching
[PMTiles](https://docs.protomaps.com/pmtiles/) basemap for
[MapLibre Native](https://maplibre.org/), and publishes both atomically. Then
you look up objects by ID and query by tag and bounding box: offline, in
milliseconds, from a Rust core shared by every platform.

*In 1502 Alberto Cantino smuggled a copy of Portugal's secret master map out
of Lisbon: a copy of the master map you carry away.*

<p>
  <img src="docs/screenshots/2026-10-03-cafe-map-airplane.png" width="270" alt="Café reference app: offline basemap with cafés coloured by opening state, in airplane mode">
  <img src="docs/screenshots/2026-10-03-cafe-detail-node.png" width="270" alt="Café reference app: raw tags and metadata of one OpenStreetMap node">
</p>

*The café reference app ([`android/cafe-app`](android/cafe-app)) in airplane
mode: offline basemap, cafés from a tag + bbox query, and the raw object.*

## What 0.1.0 does

| Does | Does not (yet) |
| --- | --- |
| Download an app-defined area (OSM data from SliceOSM) in WorkManager, with progress, retries, cancellation | Editing, uploading changes, sync or conflict handling |
| Optional offline basemap: a ready PMTiles file, or an on-device extract from a remote PMTiles archive | Render OSM data itself or generate vector tiles (use the basemap + MapLibre) |
| Atomic refresh: a failed or cancelled download never touches the published area | Incremental updates (a refresh re-downloads the whole area) |
| Lookup by ID; ANDed tag filters; bbox spatial candidates; keyset pagination | Exact geometry operations, routing, geocoding |
| Raw tags, ordered way nodes and relation members, per-object metadata | Overlapping areas or country-scale extracts |
| Android (arm64-v8a, x86_64), minSdk 26 | iOS (planned; the C ABI is ready for it) |

## Install

Cantino is served from a static Maven repository on GitHub Pages.

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://mvexel.github.io/cantino/maven") }
    }
}
```

```kotlin
// app/build.gradle.kts
android {
    defaultConfig {
        minSdk = 26
        // Cantino ships native code for these two ABIs only (phones and x86_64 emulators).
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
}

dependencies {
    implementation("io.github.mvexel:cantino:0.1.0")
    // Only to show the offline basemap.
    implementation("org.maplibre.gl:android-sdk:13.6.1")
}
```

The library adds `INTERNET` and `ACCESS_NETWORK_STATE` to your manifest
(WorkManager adds its own). Nothing else is required; long downloads can opt
into a [foreground service](docs/guide/downloading.md#foreground-mode).

## Quickstart

Download a ~10×10 km area once, list its cafés offline, show the basemap.
A complete activity (no layout file needed):

```kotlin
// app/src/main/java/com/example/cafes/MainActivity.kt
package com.example.cafes

import android.app.Activity
import android.os.Bundle
import android.util.Log
import io.github.mvexel.cantino.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import kotlin.math.cos

class MainActivity : Activity() {
    private val scope = MainScope()
    private lateinit var mapView: MapView
    private val lat = 40.7608 // use the user's location
    private val lon = -111.8910

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        mapView = MapView(this).also { setContentView(it); it.onCreate(savedInstanceState) }
        scope.launch {
            val area = publishedOrDownload()
            // An OsmStore belongs to the thread that opened it: open, query and close in one block.
            val cafes = withContext(Dispatchers.IO) {
                OsmStore.open(area.dataFile).use { store ->
                    store.query(Query(tags = listOf(TagFilter.Equals("amenity", "cafe")), limit = 1000))
                }
            }
            Log.i(TAG, "${cafes.size} cafés, e.g. ${cafes.firstOrNull()?.tags?.get("name")}")
            showBasemap(area)
        }
    }

    /** The published area, or a fresh download of ~10×10 km around (lat, lon). */
    private suspend fun publishedOrDownload(): AreaInfo {
        val areas = AreaManager(this)
        areas.loadPublishedArea(AREA)?.let { return it }
        val dLat = 5.0 / 111.32
        val dLon = dLat / cos(Math.toRadians(lat))
        val bbox = Bbox(west = lon - dLon, south = lat - dLat, east = lon + dLon, north = lat + dLat)
        // Demo basemap: today's Protomaps build. Production apps use their own mirror (see the guide).
        val runId = areas.download(AREA, bbox, basemap = BasemapSource.Extract(ProtomapsBuilds.latestUrl(), maxZoom = 15))
        // state() follows the area, not one run: match runId to wait for *this* download.
        val end = areas.state(AREA)
            .onEach { Log.i(TAG, "$it") } // Queued, Submitting, Slicing, Downloading, Importing, Basemap, Ready
            .first { it.runId == runId && it.isTerminal }
        return (end as? AreaState.Ready)?.area ?: error("download ended: $end")
    }

    private fun showBasemap(area: AreaInfo) = mapView.getMapAsync { map ->
        map.cameraPosition = CameraPosition.Builder().target(LatLng(lat, lon)).zoom(13.0).build()
        map.setStyle(Style.Builder().fromJson("""{"version": 8,
            "sources": {"osm": {"type": "vector", "url": "${area.pmtilesUrl}",
                                "attribution": "© OpenStreetMap contributors, Protomaps"}},
            "layers": [
              {"id": "earth", "type": "fill", "source": "osm", "source-layer": "earth", "paint": {"fill-color": "#f2efe9"}},
              {"id": "water", "type": "fill", "source": "osm", "source-layer": "water", "filter": ["==", "${'$'}type", "Polygon"],
               "paint": {"fill-color": "#a6cbe3"}},
              {"id": "roads", "type": "line", "source": "osm", "source-layer": "roads", "paint": {"line-color": "#9e9e9e"}}]}"""))
    }

    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { mapView.onPause(); super.onPause() }
    override fun onStop() { mapView.onStop(); super.onStop() }
    override fun onDestroy() { scope.cancel(); mapView.onDestroy(); super.onDestroy() }

    private companion object {
        const val TAG = "Cantino"
        const val AREA = "city" // your name for the area: 1-64 of [A-Za-z0-9_-]
    }
}
```

Run it once online (about 30 s for 10×10 km of a city), then again in airplane
mode: the cafés and the map come from the device. The three-layer style keeps
the example self-contained; for labels and the full Protomaps look, see
[Basemaps](docs/guide/basemaps.md#a-full-offline-style).

Things the quickstart glosses over, each covered in the guide: showing
progress and failures to the user, refreshing an area, keeping one long-lived
store on its own thread, paging through large results, and the privacy note
(the bbox is sent to SliceOSM and the basemap host).

## Documentation

- **[Guide](docs/guide/README.md)**: concepts, downloading areas, basemaps,
  querying, performance, building from source, the C ABI, and a walkthrough
  of the café app.
- **[API reference](https://mvexel.github.io/cantino/api/)** (Dokka; generate
  locally with `cd android && ./gradlew :cantino:dokkaGeneratePublicationHtml`).
- **Samples**: [`android/cafe-app`](android/cafe-app) (the full offline flow:
  first-run download, map, filters, raw object inspector) and
  [`android/sample-app`](android/sample-app) (a bundled PMTiles basemap in
  MapLibre, no network at all).
- **[CHANGELOG](CHANGELOG.md)**, including the compatibility policy.

## License and attribution

Cantino is licensed under the [Apache License 2.0](LICENSE) ([NOTICE](NOTICE)).

The data is not. OpenStreetMap data is © OpenStreetMap contributors and
available under the [Open Database License](https://www.openstreetmap.org/copyright)
(ODbL). **An app that shows OpenStreetMap data, or a basemap made from it, must
show "© OpenStreetMap contributors"** (and credit Protomaps when it uses a
Protomaps basemap build); see the [OSMF attribution
guidelines](https://osmfoundation.org/wiki/Licence/Attribution_Guidelines).
MapLibre shows the `attribution` of your style's sources; an app with its own
UI must show it itself.

"OpenStreetMap" is used here only to describe the data Cantino works with.
Cantino is not affiliated with or endorsed by the OpenStreetMap Foundation.
