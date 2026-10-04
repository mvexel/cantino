# Basemaps

Cantino does not render OSM data or generate tiles on the device. For a map
under your data, it downloads a **PMTiles** vector basemap for the area's
bbox next to the OSM data, and you render it with **MapLibre Native**.

## Choosing a source

Pass a `BasemapSource` to `AreaManager.download`:

| Source | What it does | Use when |
| --- | --- | --- |
| `BasemapSource.None` (default) | OSM data only | You don't show a map, or bring your own |
| `BasemapSource.Url(url)` | Downloads a ready PMTiles file as is, then validates it (PMTiles v3) | You pre-cut a file per city/region on your server |
| `BasemapSource.Extract(planetUrl, maxZoom = 15, overfetch = 0.05)` | Cuts the bbox out of a large remote PMTiles archive on the device, with HTTP range requests (same output as `pmtiles extract`) | You host one big archive (planet or region) and let each device take its piece |

The basemap is part of the area: `Ready` means data **and** basemap are
published, a refresh replaces both, and a refresh with `None` removes the old
basemap. Size for 10×10 km at z0–15: about 6.5 MB.

## Hosting: demo vs production

```kotlin
// Demo only: HEADs build.protomaps.com for today's build, then up to 7 days back.
val planet = ProtomapsBuilds.latestUrl() // suspend; throws IOException if none
```

`ProtomapsBuilds.latestUrl()` is for demos and tests. Protomaps' daily builds
expire after about a week and Protomaps asks apps not to hotlink them. For
production, **mirror a build** to storage you control and pass its URL:

1. Download a build once (`https://build.protomaps.com/YYYYMMDD.pmtiles`,
   over 100 GB for the planet), or cut a region with
   `pmtiles extract <build-url> region.pmtiles --bbox=…`.
2. Upload it to any host that supports HTTP range requests: Cloudflare R2
   (no egress fees, a good fit), S3 + CloudFront, or a plain web server.
3. Use `BasemapSource.Extract("https://tiles.example.com/planet.pmtiles")`.

The server must answer range requests with `206 Partial Content`; a server
that ignores `Range` (answers `200`) fails the download with a non-retryable
error rather than streaming the whole archive.

## Showing it in MapLibre

`AreaInfo.pmtilesUrl` is a ready MapLibre source URL
(`pmtiles://file:///data/…/<areaId>.pmtiles`; needs a MapLibre Native Android with PMTiles support;
Cantino is tested with 13.6.1):

```kotlin
val area = areas.publishedArea("city") ?: return  // background thread
val styleJson = assets.open("style.json").bufferedReader().use { it.readText() }
    .replace("__PMTILES_URL__", area.pmtilesUrl!!)
mapView.getMapAsync { map ->
    map.setStyle(Style.Builder().fromJson(styleJson))
    area.metadata?.bbox?.let { b ->
        // The basemap covers only the area: keep the camera over it.
        map.setLatLngBoundsForCameraTarget(LatLngBounds.from(b.north, b.east, b.south, b.west))
    }
}
```

Add your own data as GeoJSON sources on top (the café app builds one from a
`Query`; see [`MapScreen.kt`](../../android/cafe-app/src/main/java/io/github/mvexel/cantino/cafe/MapScreen.kt)).

## A full offline style

A complete style needs three things besides the tiles, all of which must be
on the device to work offline:

| Asset | Where it goes | Source |
| --- | --- | --- |
| Style JSON (layers) | app assets, `style.json` | [`@protomaps/basemaps`](https://github.com/protomaps/basemaps) (layers match the Protomaps v4 tile schema) |
| Glyphs (fonts for labels) | app assets, `asset://glyphs/{fontstack}/{range}.pbf` | [`protomaps/basemaps-assets`](https://github.com/protomaps/basemaps-assets) |
| Sprites (icons) | app assets, `asset://sprites/light` | same |

[`scripts/basemap-assets.sh`](../../scripts/basemap-assets.sh) generates all
three (Protomaps "light" flavor, English labels, fonts subset to Latin,
Greek and Cyrillic ranges: 1.7 MB) into `android/sample-app/build-assets/`,
with the source URL as the placeholder `__PMTILES_URL__`. Copy `style.json`,
`glyphs/` and `sprites/` into your app's assets (the café app does this with a
Gradle `Sync` task) and replace the placeholder at runtime as above.

Without glyphs, keep to layers without text (the quickstart's three-layer
style). Without the style, MapLibre has nothing to draw.

## Checking a PMTiles file

```kotlin
val info = PmtilesInfo.read(file) // CantinoException.InvalidFile if not PMTiles v3, .Io if unreadable
Log.i(TAG, "z${info.minZoom}-${info.maxZoom}, ${info.addressedTiles} tiles, bounds ${info.bounds}")
```

Useful for basemaps your app ships or downloads itself, and to fit the camera
to `info.bounds`. `AreaMetadata.basemap` already records the same facts for
downloaded basemaps (plus requests and bytes transferred).

## Attribution

Protomaps basemaps are made from OpenStreetMap data (ODbL). Your map must show
**"© OpenStreetMap contributors"**, and credit Protomaps when using its builds.
Put it in the source's `attribution` field and keep MapLibre's attribution
control visible (`"attribution": "© OpenStreetMap contributors, Protomaps"`),
or show it in your own UI.
