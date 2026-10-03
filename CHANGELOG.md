# Changelog

All notable changes to osm-framework. Versions follow semantic versioning;
before 1.0 a minor version may break the API.

## 0.1.0 — first release

Android library `io.github.mvexel:osm-framework:0.1.0` (AAR, minSdk 26,
arm64-v8a and x86_64) over a Rust core with a C ABI (`include/osm_framework.h`).

- **Offline OSM store** (`OsmStore`): import an OSM PBF or XML extract into a
  SQLite area database, published atomically; lookup by ID; index-backed tag
  queries (`TagFilter.Exists` / `Equals`, ANDed); bbox spatial candidates
  (tagged nodes exact, ways and relations by bounding box); keyset pagination;
  candidate cap that fails instead of truncating. Raw tags, ordered way node
  and relation member references, per-object metadata.
- **Area downloads** (`AreaManager`): download an app-named area for a bbox
  from SliceOSM in WorkManager (survives process death, waits for network,
  inline and backoff retries, resumes the submitted job), import on the
  device, and publish data, basemap and metadata together through a
  roll-forward commit journal. A failed or cancelled refresh never touches
  the previously published area. Progress as `Flow<AreaState>`.
- **Offline basemap** (`BasemapSource`): opt-in per download, either a
  ready-made PMTiles file (`Url`, validated as PMTiles v3) or an on-device
  extract of the area's bbox from a remote PMTiles archive with HTTP range
  requests (`Extract`, byte-identical to go-pmtiles' extract). Published next
  to the data; `AreaInfo.pmtilesUrl` plugs into MapLibre Native.
  `ProtomapsBuilds.latestUrl()` finds a daily Protomaps build for demos;
  `PmtilesInfo.read()` validates and describes a local PMTiles file.
- **Foreground mode** (`AreaConfig.foreground`, opt-in): runs large downloads
  as a `dataSync` foreground service with a progress notification, beyond
  WorkManager's 10-minute limit.
- **Area metadata**: SliceOSM snapshot timestamp (data age), bbox, import
  report, basemap source and transfer statistics.
- Kotlin explicit API mode: the public surface is deliberate and documented
  (KDoc on every public symbol); `OsmFramework.VERSION`.
- Café reference app (`android/cafe-app`) demonstrating the full offline flow
  in airplane mode.

Not in this release: iOS adapter, edits/upload, incremental refresh,
overlapping areas, byte-range resume of downloads.
