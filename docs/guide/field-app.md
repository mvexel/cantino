# Android field app

Cantino Field is the minimum accessibility-audit app used to validate Cantino
outside its SDK tests. It downloads one bounded district, works offline after
the download, exposes source OSM objects and records observations without
modifying the immutable Cantino area.

The implementation extends [`android/inspector-app`](../../android/inspector-app)
and keeps its application ID, `lol.osm.cantino.inspector`. The installed label
is **Cantino Field**. The existing iOS Inspector remains a data-inspection
reference; the findings workflow is an Android product experiment, not a new
SDK capability.

## Field workflow

1. Choose the device location or enter `lat,lon`, select a 1 / 2.5 / 5 / 10 km
   radius, then download the full OSM extract and PMTiles basemap.
2. Work offline. Run an accessibility check or tap the map, select an object,
   then choose **Inspect / record**.
3. Review raw tags, object metadata and the area's snapshot time. Record
   proposed `key=value` tags, a note and evidence such as a measurement, sign
   or photo reference.
4. Keep incomplete observations as drafts. Mark a finding **Ready for review**
   only after checking it.
5. Open **Findings** and save reviewed findings as GeoJSON or CSV through
   Android's document picker.
6. Send the file to a review queue such as MapRoulette, or inspect each object
   in iD/JOSM before uploading a normal OSM changeset.

There is deliberately no OSM login, direct upload or unattended edit path.

## Accessibility checks

**Audit checks** supplies editable missing-data queries for:

- entrances, shops and public facilities without `wheelchair`;
- toilets without `toilets:wheelchair`;
- crossings without `tactile_paving` or `crossing`;
- kerbs without `kerb`;
- steps without `ramp`, `handrail` or `step_count`;
- elevators without `wheelchair`;
- parking without `capacity:disabled`.

Queries use the Inspector syntax (`k=v`, `k=*`, `!k`, ANDed). Results are
candidates; selecting an object and inspecting it is part of the workflow.

## Findings overlay and exports

`FindingsRepository` stores `files/field-findings.json`, separate from the
Cantino area. An atomic replacement preserves one current finding per OSM
object. Each record contains:

- OSM kind and ID;
- the base version and complete base tag set;
- area snapshot time and observation time;
- an anchor point derived from available object geometry;
- proposed tags, note, evidence and draft/reviewed state.

Area refresh and replacement do not delete findings. That is intentional:
base version, tags and snapshot time remain attached to the observation for a
later stale-data check. The app never opens the Cantino SQLite file for writes.

Exports contain reviewed findings only. GeoJSON keeps base and proposed tags
as structured properties; CSV uses one row per finding and RFC 4180 quoting.
Both include the OSM object URL. Saving a file is a handoff, not an OSM upload.
See [Field findings and editing apps](editing-apps.md) for the review boundary.

Key files:

- `MapScreen.kt`: offline map, accessibility checks and Findings entry point.
- `ObjectActivity.kt`: raw object inspection and finding form.
- `Findings.kt`: model, file repository, provenance and GeoJSON/CSV codecs.
- `FindingsActivity.kt`: review state, deletion and document export.
- `FindingsTest.kt`: parsing, persistence, provenance, geometry anchor and
  export escaping.

## Build and run

```sh
scripts/basemap-assets.sh
scripts/build-android.sh
cd android
mise exec -- ./gradlew :inspector-app:testDebugUnitTest :inspector-app:assembleDebug
mise exec -- ./gradlew :inspector-app:installDebug
```

Debug launch at a known location:

```sh
adb shell am start -n lol.osm.cantino.inspector/.MainActivity \
  --ef lat 40.7608 --ef lon -111.8910
```

After the first download, relaunch with networking disabled and run a check,
inspect an object, save a draft, mark it reviewed and export both formats.

## MVP limits

- One offline area and one finding per OSM object.
- Evidence is text or an external photo reference; the app does not capture or
  package media.
- No campaign assignment, multi-user synchronization or MapRoulette API
  client; GeoJSON/CSV is the integration boundary.
- No live OSM version check. A reviewer must compare `base_version` with the
  current object before applying proposed tags.
- No geometry edits, new objects, deletions, OAuth or changeset upload.
