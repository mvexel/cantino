# Concepts

## Area

An **area** is everything Cantino keeps on the device for one rectangle of
the world, under an ID your app chooses (`"city"`, `"trip-2026"`: 1 to 64
characters of `[A-Za-z0-9_-]`).

| Part | File (`context.filesDir/cantino-areas/`) | API |
| --- | --- | --- |
| OSM data: every node, way and relation of the extract, with raw tags | `<areaId>.sqlite` | `AreaInfo.dataFile` → `OsmStore.open` |
| Basemap (optional): vector tiles for the same bbox | `<areaId>.pmtiles` | `AreaInfo.basemapFile`, `AreaInfo.pmtilesUrl` |
| Metadata: bbox, data age, import report, basemap stats | `<areaId>.json` | `AreaInfo.metadata` |

Each ID has at most one **published** area. `AreaManager.publishedArea(id)`
returns it (or null).

The OSM data is a **raw extract** from [SliceOSM](https://slice.openstreetmap.us/):
whole objects as they are in OpenStreetMap, not a rendering-oriented
simplification. Ways keep their ordered node lists; relations keep their
members and roles. Objects that cross the area's edge are included, but the
nodes or members outside it may be missing.

## Snapshot

A download captures OpenStreetMap at one moment: `AreaMetadata.snapshotTimestamp`
(SliceOSM's replication time). Show it to users as the data's age. The data
never changes after that; to get newer data you **refresh**.

## Refresh = full replace

A refresh is just another `AreaManager.download(id, …)` for the same ID.
There are no incremental updates: every part (data and basemap) is
downloaded and built again, then swapped in. Requesting `BasemapSource.None`
on a refresh removes an earlier basemap, because the published area always
describes one download.

## Atomic publish

```text
download ──> staging dir (.staging/<areaId>/<run>/)   nothing visible yet
                   │ data imported, basemap built
                   ▼
            commit point: journal written  ──>  data + basemap + sidecar renamed into place
```

- Before the commit point, any failure, cancellation or process death leaves
  the previous area exactly as it was.
- After it, the new area is published completely; if the process dies during
  the few milliseconds of renames, the next access finishes the job.
- Readers using `AreaManager` see either the complete old area or the
  complete new one, never a mix.

## An open store keeps its snapshot

An `OsmStore` reads the file it opened. When a refresh publishes a new file,
an already-open store keeps reading the old one (the old file stays alive
while it is open). To see new data, close the store and open
`publishedArea(id).dataFile` again. A practical rule: remember
`AreaMetadata.workId` (it changes with every refresh) and reopen when it
differs. The café app's [`CafeStore`](../../android/cafe-app/src/main/java/lol/osm/cantino/cafe/CafeStore.kt)
does exactly that.

## Capabilities

Cantino aims to provide composable capabilities for apps that work with OSM
data offline. Today they ship together, one library per platform; each is a
separate layer in the code:

| Capability | Core (Rust) | C ABI | Android / iOS |
| --- | --- | --- | --- |
| Snapshot store and queries | `store`, `schema`, `encoding`, `import` | `cantino_open`, `get`, `get_many`, `query`, `way_coordinates`, `import` | `OsmStore`, `AsyncOsmStore` |
| Area acquisition | `slice`, `area_storage`, `failure` | `cantino_slice_*`, `cantino_area_*`, `cantino_classify_failure` | `AreaManager` (WorkManager / URLSession) |
| Basemap acquisition | `basemap` | `cantino_basemap_*` | `BasemapSource`, `PmtilesInfo` |
| Rendering | — | — | your app, with MapLibre Native and the basemap |

Acquisition and basemap code does not depend on the store, so these layers
can later become separate build options without a rewrite. Editing is
outside the current implementation: an editor keeps its own edit layer and
uses a snapshot as base data ([Editing apps](editing-apps.md)).

## What Cantino is not

- Not a renderer: draw the basemap with MapLibre Native and your own data on
  top (GeoJSON sources, for example).
- Not an editor (yet): the current implementation is read-only.
- Not exact geometry: bbox queries return **candidates** (see [Querying](querying.md)).
- Not an interpreter of tags: `opening_hours`, `outdoor_seating` and the like
  are raw strings; their meaning is your app's business.
