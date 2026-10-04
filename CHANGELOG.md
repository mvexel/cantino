# Changelog

All notable changes to Cantino (formerly osm-framework). Versions follow semantic versioning;
before 1.0 a minor version may break the API.

## 0.2.0 (unreleased)

- **`AsyncOsmStore`**: a coroutine wrapper that owns one dedicated thread,
  opens the `OsmStore` on it and runs every call there (`suspend` `get`,
  `query`, `close`, plus `withStore { }` to reach any `OsmStore` method).
  Removes the thread-confinement trap of using an `OsmStore` from
  `Dispatchers.IO`. The café app uses it instead of its own worker.
- **`Bbox.around(lat, lon, widthKm, heightKm = widthKm)`**: a box around a
  point (flat-earth approximation). Latitude clamps to ±85, longitude to ±180;
  a box that would cross the antimeridian is cut at it, not wrapped.
- **armeabi-v7a**: the AAR now also packages `armeabi-v7a/libcantino.so`
  (32-bit ARM devices); `scripts/build-android.sh` builds it by default.

## 0.1.0 — first release

Android library `io.github.mvexel:cantino:0.1.0` (AAR, minSdk 26,
arm64-v8a and x86_64) over a Rust core with a C ABI (`include/cantino.h`, prefix `cantino_`).

- **Offline OSM store** (`OsmStore`): import an OSM PBF or XML extract into a
  SQLite area database, published atomically (`open` and `importArea` take
  `File`s or path strings); lookup by ID; index-backed tag
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
  WorkManager's 10-minute limit. The library manifest does **not** declare
  the foreground-service entries (they make Google Play ask for a
  foreground-service declaration), so an app that enables foreground mode
  adds them to its own manifest:

  ```xml
  <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
  <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
  <application>
      <service
          android:name="androidx.work.impl.foreground.SystemForegroundService"
          android:foregroundServiceType="dataSync"
          tools:node="merge" />
  </application>
  ```

  Without them a foreground-mode run logs a warning (tag
  `AreaDownloadWorker`) and runs as ordinary background work.
- **Manifest**: the library adds `INTERNET` and `ACCESS_NETWORK_STATE`;
  WorkManager adds `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED` and
  `FOREGROUND_SERVICE` (no service type, so no Play declaration). Apps that
  never download may remove them with `tools:node="remove"`.
- **Area metadata** (`AreaInfo.metadata`): SliceOSM snapshot timestamp as
  `java.time.Instant` (data age; null if absent or not RFC 3339, the
  sidecar file keeps the server's string), bbox, import report, basemap
  source and transfer statistics. `AreaState.Ready` carries only the
  published `AreaInfo`.
- Kotlin explicit API mode: the public surface is deliberate and documented
  (KDoc on every public symbol); `Cantino.VERSION`; errors are `CantinoException`.
- **Compatibility policy** (semver; before 1.0 a minor version may break the
  API, a patch version does not):
  - Types Cantino *returns* are regular classes with value equality, not
    data classes, and apps cannot construct them (internal constructors):
    `AreaInfo`, `AreaMetadata`, `BasemapMetadata`, `ImportReport`,
    `ObjectCounts`, `PmtilesInfo`, the `AreaState` subtypes,
    `OsmObject.Node`/`Way`/`Relation`, `OsmObject.Member`, `ObjectMetadata`.
    New properties can be added to them in a minor version without breaking
    compiled apps. There is no `copy()` or destructuring on them.
  - Types apps *construct* stay data classes for `copy()` convenience:
    `Query`, `Bbox`, `TagFilter.*`, `OsmId`, `ImportOptions`, `AreaConfig`,
    `ForegroundConfig`, `BasemapSource.*`. Their constructors (with default
    arguments) and properties are covered by the policy above; their
    generated `copy()` and `componentN()` are **not** covered before 1.0:
    a new property may change their signatures, so apps using them should
    expect to recompile on a minor upgrade. Use named arguments.
- **Area file format**: SQLite `application_id` 0x434E544E ("CNTN"),
  `user_version` (FORMAT_VERSION) 1. Pre-release builds wrote "OSMF"
  (0x4F534D46); those files are rejected and must be re-imported.
- Café reference app (`android/cafe-app`) demonstrating the full offline flow
  in airplane mode.

- **Distribution and docs**: static Maven repository at
  `https://mvexel.github.io/cantino/maven` (POM with license, developer and
  SCM), API reference (Dokka) at `https://mvexel.github.io/cantino/api/`,
  guide in `docs/guide/`. `AreaManager.state()` returns a `Flow` but
  kotlinx-coroutines is not an API dependency in 0.1.0: apps declare it
  themselves (see the README's Install section).

Not in this release: iOS adapter, edits/upload, incremental refresh,
overlapping areas, byte-range resume of downloads.
