# Demo apps through the eyes of a developer getting started

*Review, 2026-10-04, branch `basemap-only` at f55f0d5. Persona: an Android
or iOS developer who wants to add Cantino to an app and reads the examples
to learn how. Path taken: `README.md` → `docs/guide/README.md` →
`concepts.md`, `downloading.md`, `cafe-app.md`, `inspector-app.md` → code
of `android/cafe-app`, `ios/cafe-app`, `android/inspector-app`,
`ios/inspector-app`, `android/sample-app`. This is a review only. No app or
SDK code was changed. Line numbers refer to f55f0d5.*

## Verdict

The examples are honest, carefully commented and at parity. The two
walkthroughs map almost every row to a real symbol (spot-checked: all
Inspector symbols and all café symbols resolve). A newcomer finds the
**area size in under a minute** (`AreaRadius`, two constants at the top,
and the guide says so) and the **import profile quickly** (`CafeProfile`,
named in the guide). The **tag interpretation** is findable through guide
§5. The **filters themselves** (the Any/Yes/No/Unknown choices and how a
choice matches a café) are not. The guide never points to them, and on
Android they are private members of the map *view* class with no tests.

The **minimal Cantino integration** is all there, but it takes 10–15
minutes to assemble, not a few. It is spread over four files per app, and
the largest one interleaves it with location permission, view
construction, phase timing, persisted retry state and debug launch
options. About 60 of the 530 lines of the Android `MainActivity.kt` call
Cantino. The cheapest high-value fix is a "Cantino calls in this app"
index (see "Start here" below).

Two pieces of documentation are stale enough to mislead: the "profile reads back
as null" known limit (fixed in the core on this branch) and the README's iOS
status paragraph.

## Answers to the six questions

1. **Filters, profile, radius.** Radius: yes, easily (`AreaRadius.kt:21-24`,
   `AreaRadius.swift:17-20`, guide `cafe-app.md:28-48`). Profile: yes
   (`Cafes.kt:95-118`, `Cafes.swift:78-108`, guide `cafe-app.md:22,55-67`).
   Tag interpretation: yes via guide §5 (`OutdoorSeating.of` at
   `Cafes.kt:43`, `OpeningHours.kt`). Filter definitions and matching: no.
   See finding 2.
2. **Minimal integration.** It is findable but buried. The Cantino lines are
   listed per file in "Start here". Everything else in `MainActivity.kt` /
   `AppModel.swift` is app logic.
3. **Naming and layout.** Grab-bag files (`Cafes.kt`, `Location.kt`,
   `QueryBar.kt`, `Geometry.kt`), a misleading `Settings`, a `sample-app` that
   uses no Cantino, and four copied plumbing files per app pair. Debug launch
   plumbing is threaded through the main classes. See findings 5–7.
4. **Docs ↔ code.** References resolve. Two stale passages, one Android-first
   walkthrough, and a few Inspector symbols named without their file. See
   findings 3 and 10.
5. **Earlier trip-ups.** All five are confirmed, with nuances: finding 8
   (scripts), finding 4 (size), finding 7 (`auto_download`) and finding 9
   (`AreaRadius.of`).
6. **Platform parity of structure.** Concepts sit in same-named files for the
   most part. The exceptions are listed in finding 11.

## Findings, ranked by impact on a newcomer

### 1. No map of the Cantino calls; the integration is spread out and interleaved (high)

**Problem.** The first question a developer has ("which lines do I copy?") has
no direct answer. The README quickstart (`README.md:159-262`) is a good
standalone Android sample, and `swift/README.md:14-37` has a Swift snippet.
Neither links to the corresponding lines in the real apps, and the walkthrough
tables are organised by *screen*, not by *SDK call*.

**Evidence.** Android café: `AreaManager` at `MainActivity.kt:80`, routing on
the first state at `:87-98`, `download` at `:231-236`, state handling at
`:240-266` plus `ProgressScreen.update` at `:421-451`, `cancel` at `:387`,
refresh at `:319-330`. The open/reopen logic is in `CafeStore.kt:47-66`, the
query in `Cafes.kt:125-135`, the store use in `MapScreen.kt:122` and
`DetailActivity.kt:70`, and the basemap style in `MapScreen.kt:175-178`.
Between these sit location permission (`:112-137`), view building
(`:139-209`), phase timing (`:393-419`, `:485-489`), retry state persisted as
`Settings` (`:507-530`) and debug overrides (`:81-84`, `:104-107`, `:113`).
The iOS picture is the same: `AppModel.swift:55,70,75-90,172-175,191,194-228,285`,
`CafeStore.swift:47-75`, `Cafes.swift:116-127`, `MapScreen.swift:53-55,301-308`.

**Proposed change.** Add a short "Cantino calls in this app" block at the top
of `MainActivity.kt` and `AppModel.swift` (each app). It lists the six to eight call
sites by symbol, in lifecycle order: create, observe, download, cancel,
publishedArea, open store, query, basemap URL. Add a guide section
"Cantino in 30 lines" (see "Start here") that quotes them. Optionally move
`ProgressScreen` and `Settings` out of `MainActivity.kt` so the file reads as
flow plus SDK calls. **Effort S** for the index and section, **M** for the
file split.

### 2. The café filters are not where a developer looks (high; Martijn's question)

**Problem.** There are three layers, and the guide names only the first two:
(a) tag interpretation (`OutdoorSeating.of`, `OpeningHours.evaluate`),
(b) the query that defines "a café", and (c) the filter choices and matching
rules that the UI binds to. Layer (c) is the "filter" a developer wants to
change ("add a Wi-Fi filter").

**Evidence.**
- Guide §5 (`cafe-app.md:106-118`) points to `Cafes.kt: OutdoorSeating.of`
  and `OpeningHours.kt`, which is correct for (a).
- (b) is an inline literal, `TagFilter.Equals("amenity", "cafe")` at
  `Cafes.kt:129` / `Cafes.swift:119`, with no named constant. Guide §4 does
  name `CafeLoader.load`.
- (c) on Android: `private enum class OutdoorFilter`/`OpenFilter` at
  `MapScreen.kt:78-79`, matching at `:257-272`, counts at `:229-234`. All of it
  is private in the view class, and no test covers it (`android/cafe-app/src/test`
  has no filter test).
- (c) on iOS: `MapModel.OutdoorFilter`/`OpenFilter` at `MapScreen.swift:20-28`,
  matching at `:102-118`, tested in `CafeTests.swift:137-142`. It is a
  separate model, but it lives in a file named after the view.
- The guide's §4 row "Dots on the map … `MapScreen.applyFilters`" is the
  only hint, and it describes drawing.

**Proposed change.** Move (b) and (c) into `CafeFilters.kt` / `CafeFilters.swift`:
`CAFE_QUERY` (the tag filter), the two filter enums, `matches` and the
per-choice counts. Keep `OutdoorSeating` and `OpeningHours` where they are.
Port the iOS filter test to a JVM test. Add a row to guide §5:
"Filter choices and matching | `CafeFilters.kt` / `.swift`". **Effort S.**

### 3. Stale docs that contradict the code (high, cheap)

**Evidence and change.**
- `cafe-app.md:76-79` says "`AreaMetadata.report.profile` currently reads back
  as null … the core's sidecar parser drops it". The same claim is in
  `Cafes.kt:108-111` and `Cafes.swift:89-92`. It was fixed on this branch
  (18a5f8a "Core: keep the import profile in the area sidecar report", e51790f
  adapter tests, CHANGELOG "Fix: …"). A newcomer reading the guide will
  believe the status line can never show the profile. Delete the known limit
  and the comment sentence, and re-check the status line on a fresh download
  (`MapScreen.kt:243`, `MapScreen.swift:132`).
- `README.md:102-109`: "The iOS café demo is being built … New features are
  paused until iOS and Android are at parity". Both are untrue at 0.4.0
  (CLAUDE.md status, `platform-parity.md`). Replace this with one sentence
  about parity.
- `README.md` "Samples" (`:285-293`) lists `android/sample-app` second, before
  the iOS apps. `sample-app` does not use Cantino at all (`sample-app/.../MainActivity.kt`
  imports only MapLibre). It is a bundled-PMTiles MapLibre check and the
  source of the style assets. Move it last, with "no Cantino API; renders
  a bundled basemap", or rename it (finding 6).
- `docs/guide/README.md:22`: "Maintenance and roadmap | … 0.2, what is next".
  The version is out of date. Check the page itself.

**Effort S.**

### 4. No size guard on the offer screen; Inspector's 10 km choice is large (medium-high)

**Problem.** Confirmed: neither app caps or warns. The offer shows the box
size (`MainActivity.kt:171`, `FirstRunViews.swift:74`), which is a defensible
choice, but nothing flags the largest radius. For the café app the effect is
mild, because the POI profile keeps the file small. The full extract is still
downloaded, though (`downloading.md:237-239`). For Inspector it is real. A
full import at 10 km radius is a 20 × 20 km box, 16 × the 5 × 5 km numbers in
`inspector-app.md:116-117`: roughly 380 MB in Salt Lake City and 720 MB in
Zürich, before the basemap. The SliceOSM server also has a node limit per job
(`sliceosm.md:45-47`).

**Proposed change.** Pick one: (a) drop 10 km from Inspector's
`CHOICES_KM` (`inspector-app/.../AreaRadius.kt:23`, `AreaRadius.swift`), or
(b) add a one-line caution under the picker when the box exceeds a constant
(for example "large area: may take minutes and hundreds of MB"). The caution
should be an app constant next to `DEFAULT_KM`, not an SDK cap. Do not
promise a megabyte estimate. Mention the rule in `downloading.md#area-size`
as advice for apps. **Effort S.**

### 5. Copied plumbing: four copies of the store holder, two implementations (medium)

**Problem.** The examples are self-contained on purpose, which is good for
copy-paste. But a newcomer sees the same "store holder" in four places and
cannot tell whether it is an SDK concept (`CafeStore` reads like a store of
cafés) or something every app must write.

**Evidence.**
- `CafeStore.kt` ↔ `InspectorStore.kt`: identical except for names and the
  test hook (diff of 47 lines). `CafeStore.swift` ↔ `InspectorStore.swift` is
  the same pattern, implemented as an actor plus a hand-written FIFO
  `SerialGate` (`CafeStore.swift:93-115`).
- `ProtomapsBuilds` (copied unchanged, both platforms), `Location`
  (`LocationManager` / `CLLocationManager` plus debug overrides), `AreaRadius`
  (the default differs, and the café version adds `of`), and `Ui` / `Palette`.
- The `AreaState.isRunning` extension is written four times
  (`MainActivity.kt:492`, `inspector/MainActivity.kt:360`, `AppModel.swift:291`
  in both iOS apps). The SDK already has `isTerminal` and `runId`.
- The copies say where they come from (good). Only Android `InspectorStore`
  and `AreaRadius` and iOS `ProtomapsBuilds` say "copied". The café originals
  don't say they have a twin.

**Proposed change.** Short term (S): name the holder for what it is
(`AreaStoreHolder`, or keep the name with a first line "Not an SDK type:
the app's holder of one `AsyncOsmStore`, reopened after a refresh"), and add a
"copied in …" line to every original. Medium term (M, needs a design note): by
the modular-SDK rule ("removes substantial repeated work across apps and
has a consumer"), four copies across two apps is evidence for two small SDK
additions. The first is `AreaState.isRunning`/`isActive`. The second is a
published-area store that reopens on `workId` change: the part that
needed a `SerialGate` on iOS and a refresh-race test on both platforms.
`ProtomapsBuilds` should stay example code.

### 6. Misleading names and grab-bag files (medium)

| Name / file | What it actually holds | Proposal |
| --- | --- | --- |
| `Settings` (`MainActivity.kt:508`, `AppModel.swift:301`) | The last requested centre and radius, for Retry and relaunch | `LastRequest` |
| `Cafes.kt` / `Cafes.swift` | `LatLon`, `OutdoorSeating`, `Cafe`, `CafeProfile`, `CafeLoader`, `Geo` (plus `RelationMemberRow`, labels, `formatBytes` on iOS) | `CafeProfile` → `CafeProfile.kt`; `LatLon` + `Geo` → `Geo.kt` (as Inspector's `Geometry.kt`); filters → finding 2 |
| `CafeStore.AREA_ID` | The area ID used by the download flow too (`MainActivity.kt:88,232`) | Keep it, but say in the index (finding 1) that the area ID lives there |
| `Location.kt` (café) | Also `DebugRadius`, `DebugShowWhenLocked` | Debug options in their own file (finding 7) |
| `RelationDetail.kt` | Also `notFoundText`, the profile-aware "not in this area" text | Fine on Android; on iOS the same lives in `Cafes.swift` (`CafeProfile.notFoundText`). Align |
| `QueryBar.kt` (Inspector) | Also `Checks` and `Queries` (store paging and counting) | `Queries.kt`, or note it in the walkthrough |
| `Geometry.kt` (Inspector) | Also `Labels` (osm.org URL, `kind/id` parsing) | Note it in the walkthrough |
| `MapScreen.swift` (both iOS apps) | `MapModel` (state, filters, queries) plus the view plus the MapLibre bridge | `MapModel.swift` |
| `FirstRunViews.swift` | Also `ProgressScreen` and `FailureView` (refresh uses them too) | `AreaFlowViews.swift` |
| `android/sample-app` | A MapLibre-only bundled-basemap demo and the generator of the style assets every app copies | `basemap-sample`, or document it as the asset build |

**Effort S** each. Renames are cheap now, with no external consumers.

### 7. Debug launch plumbing obscures the main path and differs per app (medium)

**Problem.** Every app supports automation launch options (rightly: no real
GPS in automated runs). But the options are read in the entry class and threaded
into the main types (`MapScreen(…, debugLocation, …)` at `MainActivity.kt:310`;
`MapScreen(…, debugLaunch, …)` at `inspector/MainActivity.kt:274`;
`AppModel.debugLaunch` used at `AppModel.swift:123-128,149-154,270-274` and
`MapScreen.swift:177-183`; `DebugLaunchRunner` inside iOS Inspector's
`MapScreen.swift:393`). The grep count of "debug" is 10–13 per main file. Their placement also differs:

| | Android café | iOS café | Android Inspector | iOS Inspector |
| --- | --- | --- | --- | --- |
| File | `Location.kt` (`DebugLocation`, `DebugRadius`, `DebugShowWhenLocked`) | `Location.swift` + `DebugLaunch.swift` | `Location.kt` (`DebugLaunch`) | `Location.swift` (`DebugLaunch`) + `MapScreen.swift` (`DebugLaunchRunner`) |
| Options | lat/lon, radius, show_when_locked | lat/lon, radius, auto_download, list, outdoor, now, object, refresh, cancel_after | lat/lon, radius, auto_download, query, view_only, tap, tap_radius, select, object, about, counts, zoom | same as Android Inspector |

**Confirmed trip-up:** the Android café app has no `auto_download` (nor
`list`/`outdoor`/`now`/`object`/`refresh`/`cancel_after`). The walkthrough
documents only what exists (`cafe-app.md:137-139`), so it is a parity gap and
not a doc bug: automated Android café runs still need `adb input tap`.

**Proposed change.** One `DebugLaunch.kt` / `DebugLaunch.swift` per app, holding
the location override too, with a header line: "Automation only, debug builds;
your app does not need this file". Apply it in one place (an `applyDebugLaunch()`
called once from the entry class). Give the Android café the iOS café's
option set. **Effort M** (the Android café options are the bulk).

### 8. Build scripts (medium for first contact)

All three reported issues are confirmed.
- `scripts/build-ios-cafe.sh` has no `SIMULATOR_ID` (compare
  `build-ios-inspector.sh:22-29`). `install` runs `xcrun simctl install booted`
  (`build-ios-cafe.sh:39`), which is ambiguous with more than one booted
  simulator and can target a different device than the `-destination` name it
  built for. The two scripts also default to different simulators
  ("iPhone 18 Pro" vs "iPhone 17 Pro"). A newcomer without that runtime gets
  an `xcodebuild` destination error and no hint. The Inspector walkthrough then
  launches with `booted` even after `SIMULATOR_ID` (`inspector-app.md:177-182`).
  Proposal: port the `SIMULATOR_ID` block, use `$device` for install and the
  printed launch line, and use the same default in both. Better: one
  `build-ios-example.sh <cafe|inspector>`. **S.**
- `scripts/build-android.sh:34-37` calls bare `cargo`. The committed
  `mise.toml` pins only Java, so in a non-interactive shell without
  `~/.cargo/bin` on `PATH` (the situation here) the script fails while every
  Gradle line in the guides uses `mise exec --`. The main checkout has an
  uncommitted `rust = "1.99.0"` line in `mise.toml` that would make
  `mise exec -- scripts/build-android.sh` work. Proposal: commit that line and
  write the guide commands as `mise exec -- scripts/build-android.sh`
  (`cafe-app.md:134`, `inspector-app.md:166`, `building.md`). **S.**
- The iOS apps' style assets come from `android/sample-app/build-assets`
  (`ios/cafe-app/copy-basemap-assets.sh:12`, `build-ios-cafe.sh:22-25`). An iOS
  developer has to run an Android-named path's generator. The error message
  names the fix, which is good. Long term, generate into a neutral
  `assets/basemap/` directory. **S–M.**

### 9. `AreaRadius.of(bbox)` duplicates the SDK's private constant (low)

Confirmed: `KM_PER_DEGREE_LAT = 111.32` in `cafe/AreaRadius.kt:48` and
`AreaRadius.swift:51` mirrors the private `KM_PER_DEGREE` in
`Models.kt:275` / `kmPerDegree` in `Models.swift:285`. The impact is smaller
than it sounds. `of` is used only on refresh (`MainActivity.kt:327`,
`AppModel.swift:281`) to label the progress screen and to persist a radius for
Retry, and the refresh downloads the exact bbox. One side effect: **Retry after a failed
refresh** rebuilds a box from the centre and the derived radius
(`MainActivity.kt:284`, `AppModel.swift:253-256`) instead of reusing the
published bbox. This is the approximation the CHANGELOG says refresh no longer
makes (and Android also stores the centre as `Float`, `MainActivity.kt:525-527`).
Proposal: persist the requested bbox (not centre and radius) for Retry, and
drop `of`. The refresh label can say "same area". If the label must show a
size, add `Bbox.widthKm`/`heightKm` to both adapters next to `around` rather
than copying the constant. Inspector has no `of` and no Retry. **Effort S.**

### 10. Docs pointing (low–medium)

- `cafe-app.md` is Android-first. The paths are Android paths
  (`cafe-app.md:13`), and the iOS mapping is a table at the end (`:163-174`).
  An iOS developer has to translate each row. Proposal: add a second "Code"
  column for iOS in each table, or link the iOS table from the top. **S.**
- `inspector-app.md` names symbols without files where the file is not obvious:
  `Queries.page`, `Checks.ALL` (in `QueryBar.kt`); `HitTest.*`, `Geo.*`,
  `Labels.osmOrgUrl` (in `Geometry.kt`); `ObjectDetail.missingReferences` (in
  `Inspect.kt`). iOS spells `HitTest.ORDER` as `HitTest.order(_:)`
  (`Geometry.swift:150`). Proposal: add the file in each cell. **S.**
- Good: every symbol I looked up exists. The walkthroughs' "Cantino API"
  column is the most useful thing in the docs for a newcomer.

### 11. Platform parity of structure (low)

The same concept is in the same-named file, with these exceptions:

| Concept | Android | iOS |
| --- | --- | --- |
| Flow and state machine | `MainActivity.kt` (activity, views, progress, `Settings`) | `AppModel.swift` (model, `ProgressModel`, `Settings`) + `FirstRunViews.swift` (views) |
| Café filters | private in `MapScreen.kt` (view class) | `MapModel` in `MapScreen.swift` |
| "Not in this area" text | `RelationDetail.kt` | `Cafes.swift` (`CafeProfile.notFoundText`) |
| Run filtering on the state stream | none (`MainActivity.kt:240-266`) | `currentRun` filtering at `AppModel.swift:204-213` |
| Debug options | see finding 7 | see finding 7 |

The run-filtering difference deserves a comment. The README quickstart teaches
`runId` matching (`README.md:207-210`), iOS does it, and Android doesn't. A
developer copying the Android app cannot tell whether that is safe (for
example, because Android's `StateFlow` doesn't replay an old run's terminal state)
or an omission. Proposal: a one-line comment in `MainActivity.onAreaState`
saying why, or align it with iOS. **S.**

Minor: the Android café formats coordinates with the default locale
(`Cafes.kt:28`, `MainActivity.kt:234`, so a German phone shows
`40,76080, -111,89100`), while Android Inspector uses `Locale.ROOT`
(`Geometry.kt:37`). **S.**

## Proposed "start here" path (not applied)

**1. A top-of-file index** in `android/cafe-app/.../MainActivity.kt` and
`ios/cafe-app/CafeApp/AppModel.swift` (and the Inspector equivalents),
for example:

```kotlin
/*
 * Cantino calls in this app, in lifecycle order (everything else is UI):
 *   AreaManager(context, CafeProfile.AREA_CONFIG)   MainActivity.onCreate
 *   areas.state(AREA_ID) / publishedArea(AREA_ID)    MainActivity.onCreate (routing), onAreaState
 *   areas.download(AREA_ID, bbox, basemap = …)       MainActivity.startDownload
 *   areas.cancel(AREA_ID)                            ProgressScreen
 *   AsyncOsmStore.open(area.dataFile)                CafeStore.withStore (reopens after a refresh)
 *   store.query(Query(tags, bbox, after, limit))     CafeLoader.load
 *   store.wayCoordinates / store.get                 CafeLoader.representativePoint, DetailActivity
 *   area.pmtilesUrl → MapLibre style                 MapScreen.styleJson
 * App policy, not Cantino: CafeProfile (what is imported), CafeFilters (what is shown),
 * OpeningHours, AreaRadius (area size). Automation only: DebugLaunch.
 */
```

**2. A guide section "Cantino in 30 lines"** at the top of `cafe-app.md`,
before the screenshots. It would have one short code block per platform
assembled from the real call sites above (create → download → observe →
open → query → basemap URL), each line linked to its file, and a sentence
saying that the rest of the app is UI and policy. It complements the README quickstart: the
quickstart is a standalone activity, while this section points into the real app.

**3. A "change this" box** in the same section:
"Area size → `AreaRadius` (two constants). What is downloaded →
`CafeProfile`. Which cafés are shown → `CafeFilters` and `OutdoorSeating.of`
/ `OpeningHours`. Which objects count as cafés → `CafeFilters.CAFE_QUERY`."
This box answers Martijn's question directly.

## Input for the iOS café redesign (task 13)

The redesign (bottom sheet, toolbar filter menus, `List` detail, semantic
colours, onboarding cover) touches exactly the files above. To avoid
re-burying things:

- **Filters (finding 2):** create `CafeFilters.swift` first. Toolbar filter
  menus should bind to `MapModel` state backed by it, not to enums nested in a
  view file. Keep the per-choice counts (they make Unknown visible) and the
  `CafeTests` filter test.
- **Model and view split (findings 6, 11):** move `MapModel` to `MapModel.swift`
  before building the bottom sheet, so the sheet and the map share one model
  and the view file stays UI.
- **Onboarding cover (findings 1, 4):** present the cover from `AppModel.Screen`
  routing (keep the Cantino calls in `AppModel`, not in cover views). It is the
  natural place for the area-size caution and the privacy line. Rename
  `FirstRunViews.swift` as part of it.
- **Semantic colours:** `Palette` (`Ui.swift:10-24`) copies Android RGB values.
  Replace it with semantic/asset colours. Keep Open/Closed/Unknown distinct in
  both light and dark mode, since Unknown must never read as Closed.
- **Debug options (finding 7):** `-list`, `-outdoor`, `-now`, `-object`,
  `-refresh` stand in for taps in the acceptance runs. Re-wire them to the
  new toolbar menus and the `List` detail in one `applyDebugLaunch()`, and
  keep them out of the views.
- **Stale comment (finding 3):** drop the "profile reads back as null"
  sentence in `Cafes.swift:89-92` while touching the status line. With the core fix
  the redesigned header can show "points of interest only".
- **"Start here" index:** add it to `AppModel.swift` as part of the redesign so
  the index matches the new structure.
