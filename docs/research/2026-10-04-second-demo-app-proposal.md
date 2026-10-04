# A second demo app: proposal

*Research note, 2026-10-04. A proposal, not a commitment and not a backlog
item. Checked against the 0.4.0 code on `basemap-only` (`58ae00f`).
Revised the same day after the maintainer's answers (§5).*

**Recommendation:** one example app per audience.

- **Café finder** (exists): for generic app developers. It shows one query
  answered well: download, offline map, filters, interpretation in the app.
- **Inspector** (new, separate app): for OSM developers and mappers. It shows
  the query layer itself: a tag query bar, tap-anywhere inspection and object
  geometry drawn from the store.

The Inspector MVP needs no SDK changes. It is app code over today's
`AreaManager`, `query`, `get` and `wayCoordinates`. Name search and a full
"raw data" layer come later, and only after measurement.

## 1. What the café app already shows

| Capability | Café app |
| --- | --- |
| Area download: progress, failure/retry, refresh, relaunch mid-download, crash-safe publish | Yes, the full lifecycle |
| Basemap `Extract` from a Protomaps build, offline "light" style, `pmtiles://` | Yes |
| One `Equals` tag query over the whole area, paged by 500 | Yes (`amenity=cafe`) |
| `wayCoordinates`, batch `get` | Yes, but only to place a marker anchor |
| Object detail: raw tags, metadata, `locationVersion`, way nodes, relation members, "not in this area" | Yes |
| Tap a marker → detail | Yes, for café dots only |
| App-side tag interpretation (opening hours, outdoor seating) | Yes |

**No demo exercises these yet:**

- query features: `Exists` and `NotExists` filters, ANDed filters on several
  keys, bbox queries without a tag filter, the `maxCandidates` error path;
- geometry: way geometry drawn as lines, and partial geometry (null
  coordinates at the area edge);
- options and metadata: import profiles, `preserveUntaggedMetadata`,
  `PmtilesInfo`, `BasemapSource.Url`;
- basemap-only areas, which are in progress.

The core's `dependencies`, `missing_references`, `counts` and `scan` exist in
Rust, but the C ABI and the adapters do not expose them. Apps get the same
results from batch `get` (nulls are missing references) and from
`ImportReport.counts`.

## 2. The ideas, evaluated

| Idea | Works today? | Cost and risks |
| --- | --- | --- |
| **Tag query bar** (`amenity=bench`, `shop=*`, `!name`) | App-only. The parser is about 50 lines per platform | Low. Matching is exact and case-sensitive, and a lone `!key` needs a bbox. The UI must say both |
| **Tap anywhere to query** | App-only: bbox query of about ±15 m around the tap, then `wayCoordinates` per candidate way and a point-to-segment distance | Medium. Untagged nodes are never returned, so a bare vertex cannot be tapped. Big relations are bbox candidates and must be labeled "near", not "hit". There is no point-in-polygon test |
| **Name search** | App-side only: page `Exists("name")` once per `workId` (tens of thousands of objects in a city, seconds through JNI), then match in memory | Medium. Memory and first-run latency need measuring. A core FTS index means a format bump, a new ABI and two adapters. Not justified by a demo |
| **"Nerd map": draw everything** | App-only, zoom-gated: viewport bbox at z≥17, a GeoJSON source rebuilt on camera idle | High. A 10×10 km city has about 123k ways, and a 300 m viewport 1–3k, at one native call per way. Measure first |
| **Different basemap style** | `basemap-assets.sh` only builds "light". A flavor argument is a script change | Low. Optional; a strong highlight color on the light style is enough for the MVP |
| **Import profile picker** | App-only (`AreaConfig`) | Conflicts with an inspector: a filtered area hides the objects it exists to show. Fits the café app instead (§4) |
| **Parent ways of a node** | App-only: bbox around the node, ways among the candidates, check `nodeIds` (there has been no `node_way` index since format 2) | Low. It can miss ways at the area edge and must say so |

## 3. Inspector

**Pitch.** Inspector shows what is actually in the OpenStreetMap data under
your feet, with no network. Ask "every `amenity` without `opening_hours`" or
"every `highway=crossing` without `crossing`". Tap anywhere to see which
objects are there. Follow a way to its nodes, a relation to its members, or a
node to the ways that use it.

**Target user.** OSM developers and mappers. Generic app developers stay with
the café app.

### MVP screens

| Screen | Interactions | Showcases |
| --- | --- | --- |
| **Area** | Pick a bbox (location or typed `lat,lon`), download a full import, one progress line. After publish: snapshot age, counts, database bytes, basemap zoom and tile count | `ImportReport`, `PmtilesInfo`, `AreaMetadata.basemap` (the lifecycle is reused, not re-demonstrated) |
| **Map** | Tap anywhere: a sheet lists candidates by distance, with nodes and ways marked *hit* and large ways and relations marked *near (bbox)*. Selecting one highlights its geometry, broken where it leaves the area | Bbox-only queries, candidate semantics, `wayCoordinates` as lines, partial geometry |
| **Query** | `k=v`, `k=*` and `!k` terms ANDed, a "this view only" toggle, and validator-style presets (amenities without `opening_hours`, crossings without `crossing`, sidewalks without `surface`). Results go on the map and in a list, with "load more" via `after`. Errors are shown verbatim | All `TagFilter` kinds, AND, bbox, keyset paging, `InvalidArgument` paths |
| **Object** | Tags, metadata, way nodes, relation members, a missing-reference count, "ways using this node" (best effort), and an osm.org link marked as online-only | Batch `get` nulls as missing references, graph navigation without a reverse index |

**Not repeated from the café app:** tag interpretation, list and filter chips,
and the detailed progress screen.

### Later, only if wanted and measured

1. **Name search**, app-side, with an index built once per `workId`.
2. **Raw layer** at z≥17, after measuring per-way `wayCoordinates` on a Pixel
   8 and an iPhone.
3. A `preserveUntaggedMetadata` toggle, for vertex metadata at the cost of a
   larger file.
4. A grayscale or dark flavor via a `basemap-assets.sh` argument.

### Needs SDK changes

- **MVP: none.**
- Later, on measured need only: a batch `wayCoordinates` (Rust, C ABI,
  Kotlin, Swift, tests on both platforms; about the size of batch `get`) for
  the raw layer. A full-text index is not recommended.

### Shared code

Inspector needs the same plumbing as the café app: the store wrapper,
`ProtomapsBuilds`, the location/first-run flow and the style asset copy. That
is about 400 lines per platform. Copy it: each example stays readable on its
own. Extract a shared module only if the copies drift. Expect about
1,000–1,300 new lines per platform, plus tests on both platforms for the
query parser, hit testing and the parent-way lookup.

### Acceptance

An airplane-mode run on both platforms, with the same results on each:

- **Amenities:** `amenity=cafe`, `restaurant`, `bench`, `drinking_water`,
  `toilets`, `bicycle_parking`; `amenity=* !opening_hours`.
- **Pedestrian infrastructure:**
  - `highway=footway footway=sidewalk`
  - `footway=crossing`
  - `highway=crossing crossing=*`
  - `highway=crossing !crossing`
  - `barrier=kerb kerb=*`
  - `highway=steps`
  - `highway=pedestrian`
- **Taps:** a crossing node (a hit); a sidewalk way (a hit); the inside of a
  large park or a multipolygon (near only); the untagged middle of a sidewalk,
  which must find the way, not a vertex.

Record the counts per area as in `docs/bench/`. A count that differs between
Android and iOS fails the run.

## 4. Where import profiles and basemap-only go

There is no third example app. The Android `sample-app` is an 83-line
bundled-basemap renderer with no iOS counterpart, so putting demos there would
mean building one more app per platform.

- **Import profiles → the café app.** It needs only POIs, so a POI profile
  is the realistic choice for a generic app, and it is measured at 7% of a
  full import on SLC. Kept ways keep their nodes, so the detail screen still
  works. Inspector stays a full import.
- **Basemap-only → no demo app.** Neither app has a natural use for a map
  without data. Cover it with adapter tests on both platforms and a guide
  snippet. The modular assessment's P2 exit criterion ("a basemap-only sample
  on both platforms") needs rewording to match.

## 5. Decisions and open questions

The maintainer decided on 2026-10-04: separate example apps per audience
(OSM developers, generic app developers); no third example app; acceptance
covers several kinds of amenities and pedestrian infrastructure, not only
café counts.

Open:

1. Should the café app switch to a POI import profile (§4)?
2. Is it acceptable that no demo shows basemap-only, with the P2 exit
   criterion reworded?
3. Which acceptance areas? SLC plus one area with dense pedestrian mapping
   (sidewalks and crossings mapped as separate ways)?
4. App name: "Inspector", or something else?

## Decisions (Martijn, 2026-10-04)

1. The café app switches to a POI import profile; it keeps showing the
   basemap (the profile only filters the OSM data import).
2. No example app shows basemap-only; adapter tests and the guide cover it.
3. Inspector acceptance areas: Salt Lake City (sidewalks and crossings are
   mapped as separate ways there) and Zürich, Switzerland as the second area.
4. The name "Inspector" stays.

Both apps are built and changed on both platforms.
