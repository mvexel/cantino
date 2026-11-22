# Cantino

**Cantino — raw OpenStreetMap snapshots for offline, domain-specific Android
and iOS apps.**

Cantino gives a mobile app an immutable snapshot of raw OpenStreetMap data for
one bounded area. Use it when the app needs arbitrary tags, ordered way nodes,
relation members or object metadata offline—not just the rendering-oriented
features present in vector tiles, and not a network query to a remote service.

The shared Rust core imports the snapshot into a local SQLite file and answers
ID, tag and bounding-box queries. The managed download flow fetches an extract
from [SliceOSM](https://slice.openstreetmap.us/), imports it, and publishes it
with crash recovery; apps may instead import a local OSM PBF or XML file. An
optional, separate [PMTiles](https://docs.protomaps.com/pmtiles/) basemap can
be rendered with [MapLibre Native](https://maplibre.org/).

Cantino is a data component, not a general offline map or navigation engine.
The app owns its workflow, tag interpretation, rendering and any findings or
edits.

*In 1502 Alberto Cantino smuggled a copy of Portugal's secret master map out
of Lisbon: a copy of the master map you carry away.*

<p>
  <img src="docs/screenshots/2026-10-03-cafe-map-airplane.png" width="270" alt="Café reference app: offline basemap with cafés coloured by opening state, in airplane mode">
  <img src="docs/screenshots/2026-10-03-cafe-detail-node.png" width="270" alt="Café reference app: raw tags and metadata of one OpenStreetMap node">
</p>

*The café reference app ([`android/cafe-app`](android/cafe-app)) in airplane
mode: offline basemap, cafés from a tag + bbox query, and the raw object.*

## Where it fits

| Need | Use |
| --- | --- |
| Render an offline map | MapLibre Native with PMTiles or another tile source |
| Query raw OSM objects and uncommon tags offline in a bounded area | Cantino |
| Build a complete navigation app | A routing, geocoding and map engine such as OsmAnd or CoMaps |
| Run large or live semantic queries | Overpass or an app-specific backend |
| Edit OSM directly | An established editor such as Every Door, StreetComplete, Vespucci, iD or JOSM |

The best fit is a domain-specific app: a field reference or audit tool, an OSM
inspector, or a specialized POI guide whose workflow depends on source tags
that a generic map does not expose. The
[Inspector example](docs/guide/inspector-app.md) demonstrates the underlying
query and object-navigation mechanics.

## Capabilities

| Capability | Status |
| --- | --- |
| Snapshot store and queries (lookup, tags, bbox candidates) | Available, Android and iOS |
| Area acquisition (download, import, refresh, crash-safe publication) | Available, Android and iOS |
| Basemap acquisition (PMTiles file or on-device extract), with an area | Available, Android and iOS |
| Basemap-only acquisition (a map without the OSM data) | Available, Android and iOS (`downloadBasemap`) |
| App-owned field findings or edit overlay | Build in the app; Cantino supplies immutable base objects and versions |
| Upload to the OSM API | Not part of Cantino; hand reviewed findings to an established editor or task queue |

The capabilities ship together as one library per platform; they are separate
layers in the code (see
[Concepts](docs/guide/concepts.md#capabilities)). Separate artifacts would add
four-platform surface without helping a current consumer.

## What Cantino provides

- **A read-only snapshot store:** raw tags, ordered way nodes, relation
  members and roles, and object metadata. Untagged-node metadata is optional.
- **A query API:** lookup by typed OSM ID, ANDed tag filters, bounding-box
  spatial candidates, keyset pagination, and missing-reference reporting.
- **An area download lifecycle:** progress, retries, cancellation, staged
  import, and journaled publication. Refresh replaces the whole snapshot;
  an already-open store continues to read its old snapshot.
- **Optional import profiles:** retain matching objects and their available
  dependencies. Filtering reduces local storage; the managed flow still
  downloads the full extract.
- **An optional basemap companion:** download a ready PMTiles file or extract
  one from a remote archive. Apps can use their existing renderer and omit
  this part entirely. The raw data and basemap have independent sources and
  may represent different OSM snapshot times.

## What Cantino does not provide

These are product boundaries, not a backlog.

- **OSM editing, synchronization or upload.** There is no mutable object
  graph, pending-edits layer, OAuth, upload or conflict resolution. A field
  app keeps findings in its own database and can hand reviewed changes to
  MapRoulette, iD or JOSM; see
  [Field findings and editing apps](docs/guide/editing-apps.md).
- **A complete offline map or navigation engine.** Cantino does not render
  maps, generate vector tiles, route, geocode or provide address search.
- **A complete OSM graph or exact geometry engine.** Extracts can omit nodes
  and relation members beyond their boundary. Bounding-box queries return
  candidates, not exact intersections; missing geometry can also cause
  objects to be missed. Multipolygon assembly is outside the scope.
- **A country-scale or continuously updated data platform.** The supported
  use case is one bounded area per app. Overlapping-area reconciliation,
  incremental updates and country-scale operation are outside the scope.
- **A hosted-service guarantee.** Initial downloads and refreshes need a
  network connection. Production apps must plan their extract and basemap
  hosting, capacity and privacy requirements; see
  [SliceOSM in production](docs/guide/sliceosm.md) and
  [Basemap hosting](docs/guide/basemaps.md#hosting-demo-vs-production).

## Platform and release status

Version 0.4.0 established Android/iOS parity: the adapters wrap the same core,
run the same behavioral corpus, and both café apps passed the first-run
download, offline relaunch and interrupted-download recovery flow. The
2026-10-03 feature freeze is therefore lifted. Current verification and the
intentional platform differences are recorded in
[Android and iOS parity](docs/guide/platform-parity.md).

New capabilities remain parity-first: they land on Android and iOS with tests
on both. Cantino still has no external consumers, so API, ABI and file formats
may change without compatibility layers; adopting it today means budgeting
for integration changes and re-importing development data.

## Install

Development artifacts use a static Maven repository on GitHub Pages.
For the current checkout, [build and publish locally](docs/guide/building.md);
the API below may differ from previously published artifacts.

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
    implementation("lol.osm:cantino:0.4.0")
    // Only to show the offline basemap.
    implementation("org.maplibre.gl:android-sdk:13.6.1")
}
```

The library adds `INTERNET` and `ACCESS_NETWORK_STATE` to your manifest
(WorkManager adds its own). Nothing else is required; long downloads can opt
into a [foreground service](docs/guide/downloading.md#foreground-mode).

**iOS:** no published package yet. Build the core with
`scripts/build-xcframework.sh` (macOS, Xcode, Rust; see
[Building](docs/guide/building.md)) and add [`swift/`](swift) as a local
Swift package; the [Swift adapter README](swift/README.md) has the API and
an example.

## Quickstart

Download a ~10×10 km area once, list its cafés offline, show the basemap.
A complete activity (no layout file needed):

```kotlin
// app/src/main/java/com/example/cafes/MainActivity.kt
package com.example.cafes

import android.app.Activity
import android.os.Bundle
import android.util.Log
import lol.osm.cantino.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

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
                OsmStore.open(checkNotNull(area.dataFile)).use { store ->
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
        val bbox = Bbox.around(lat, lon, widthKm = 10.0)
        val runId = areas.download(AREA, bbox, basemap = BasemapSource.Extract(latestDemoBasemap(), maxZoom = 15))
        // state() follows the area, not one run: match runId to wait for *this* download.
        val end = areas.state(AREA)
            .onEach { Log.i(TAG, "$it") } // Queued, Submitting, Slicing, Downloading, Importing, Basemap, Ready
            .first { it.runId == runId && it.isTerminal }
        return (end as? AreaState.Ready)?.area ?: error("download ended: $end")
    }

    // Demo only: production apps supply a URL they host (with HTTP range support).
    private suspend fun latestDemoBasemap(): String = withContext(Dispatchers.IO) {
        val today = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
        for (age in 0L..7L) {
            val date = today.minusDays(age).format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
            val url = "https://build.protomaps.com/$date.pmtiles"
            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            try {
                connection.requestMethod = "HEAD"
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                when (val status = connection.responseCode) {
                    200 -> return@withContext url
                    404 -> Unit
                    else -> throw java.io.IOException("Basemap lookup failed: HTTP $status")
                }
            } finally {
                connection.disconnect()
            }
        }
        throw java.io.IOException("No recent demo basemap found")
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
  querying, performance, building from source, the C ABI, and walkthroughs of
  the café and Inspector apps. Production topics:
  [SliceOSM in production](docs/guide/sliceosm.md),
  [opening hours](docs/guide/opening-hours.md),
  [field findings and editing apps](docs/guide/editing-apps.md), and the
  [roadmap and maintenance](docs/guide/roadmap.md).
- **[API reference](https://mvexel.github.io/cantino/api/)** (Dokka; generate
  locally with `cd android && ./gradlew :cantino:dokkaGeneratePublicationHtml`).
- **Samples**: [`android/cafe-app`](android/cafe-app) (the full offline flow:
  first-run download, map, filters, raw object inspector) and
  [`android/sample-app`](android/sample-app) (a bundled PMTiles basemap in
  MapLibre, no network at all); [`ios/cafe-app`](ios/cafe-app) is the café app on
  iOS. For OSM developers and mappers: Inspector
  ([`android/inspector-app`](android/inspector-app),
  [`ios/inspector-app`](ios/inspector-app)), a tag query bar with checks,
  tap-anywhere inspection and an object navigator over a full import
  ([walkthrough](docs/guide/inspector-app.md)).
- **[Swift adapter](swift/README.md)**: the iOS/macOS API and how it differs
  from Kotlin.
- **[CHANGELOG](CHANGELOG.md)**, development history.

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
