# Cantino 0.1.0 through the eyes of an offline-hiking-app developer

*Persona review, 2026-10-03. Sources: the public site (https://mvexel.github.io/cantino/), its Dokka API reference, and the public GitHub README, guide and CHANGELOG. No local checkout or memory used.*

**The app:** the user picks a park or a multi-day trip region at home on Wi-Fi, then hikes for days without signal. The map shows trails coloured by difficulty, huts, water, viewpoints, the "you are here" dot and elevation. Tapping a trail shows its tags and length.

**Bottom line:** Cantino gives me a solid, honest offline store of raw trail data plus a matching vector basemap, with a download lifecycle (WorkManager, atomic publish, a failed refresh never breaks the old area) I'd otherwise spend weeks building. But the hiking-specific half is all mine: assembling geometry for trails and route relations, elevation and terrain, and routing. The size and scale story is tuned for a 10×10 km city, not a 50×50 km national park. I'd use it for the data layer if the scale questions below get good answers.

## 1. Missing features (for my use case)

| Gap | Why it hurts a hiking app |
|---|---|
| **No geometry assembly.** A way is a list of node IDs, and a `route=hiking` relation is a member list | Drawing a trail means resolving every node myself. Drawing a named route means ordering member ways, handling `forward`/`backward` roles and joining segments. That's the core of a hiking map, and every hiking app will rewrite it. |
| **No batch `get`.** One JNI call per ID, about 0.08 ms per object decoded | A route with 5,000 vertices costs 5,000 calls before I draw a line. I'd want `get(ids)` or "way with resolved coordinates". |
| **No elevation or terrain.** One PMTiles basemap per area, vector only | Hikers expect hillshade, contours and an elevation profile. I'd need a second (raster DEM) PMTiles per area and can't attach it to the area's atomic lifecycle. |
| **No routing** (stated as out of scope) | "How far to the hut?" needs a trail graph. I'd build my own graph from the raw ways. That's feasible, but every app pays that cost. |
| **Areas are bboxes only** | A long-distance trail is a corridor. A bbox around a 200 km diagonal route is mostly useless data. No polygon or corridor areas. |
| **Scale is city-tuned.** Country scale is a non-goal, and the 10-minute WorkManager limit applies | A national park can be 50–100 km across. Foreground mode helps, but Android 15+ caps `dataSync` at about 6 h a day, and import memory "grows with the area". I have no numbers for rural areas. |
| **No byte-range resume.** "Downloads restart from byte 0 on a retry" | Users often start a download on flaky trailhead or campground Wi-Fi. A large area restarting from zero is painful. |
| **No size estimate before downloading** | I need to tell users "this area will take ~400 MB" before they commit their storage and data plan. |
| **No tag-filtered import** | I want paths, POIs, water and huts, not every building outline in the towns at the park edge. Disk is about 10× the PBF. |
| **Bbox queries return candidates by bounding box** | Long trails have huge bounding boxes, so a viewport query returns many trails that don't cross it, and `maxCandidates` can throw on dense areas. Correct and documented, but I'll need my own clipping. |
| **Edge clipping** | A route relation crossing the area's edge has missing members, and its bounding box is computed only from the members present. I need to show "this route continues outside your area". |
| **No iOS yet; arm64-v8a and x86_64 only** | Outdoor apps need both platforms, and budget rugged phones are often 32-bit. |

Not Cantino's job, and I wouldn't ask for it: GPS tracking, track recording, GPX import/export, search and geocoding.

## 2. Developer experience and docs

### Strong

- The download lifecycle is exactly what a "download before the trip" app needs, and the guarantees are explicit: the old area stays intact until `Ready`, data and basemap are published together, and a process kill is recovered. `AreaMetadata.snapshotTimestamp` gives me a "data as of" label for free.
- `AreaState` is sealed and well documented, so building a progress UI is straightforward. The guide shows a complete `render(state)`.
- Honest semantics everywhere: candidates rather than exact matches, dangling references called out ("Handle it"), and edge clipping explained.
- Measured numbers with device and date. The rule of thumb (about 10× the PBF, about 75 MB per 10×10 km of dense city) lets me make a rough estimate.
- The basemap guide covers the important production point: Protomaps builds can't be hotlinked, so mirror one to R2/S3. `BasemapSource.Extract` matches `pmtiles extract` byte-for-byte.
- Attribution and privacy are covered, which matters for a store listing.

### Weak

- **All examples are urban.** It's cafés, 10×10 km and downtown Salt Lake City throughout. There are no numbers for a sparse rural area or a big one, and nothing on how forest and landuse polygons affect size.
- **The geometry gap is undersold.** "Build what you need (the café app uses the mean of a way's node coordinates)" is fine for a café dot, but it's the hard part for trails and routes. A doc page or helper for ways and route relations is missing.
- **The docs are split.** The guide lives on GitHub, the reference on Pages. Dokka hides non-literal defaults (`Query.maxCandidates` and several `AreaConfig` fields look required).
- **Thread-confined `OsmStore`.** It's well explained, but with a map moving at 60 fps and queries per viewport change, I need a disciplined single-thread dispatcher. A library-provided suspend wrapper would remove that footgun.
- **`state()` follows the area, not the run.** Waiting for *my* download needs the awkward `dropWhile`/`first` sequence or matching `workId`.
- **The basemap style for hiking isn't covered.** The guide's "full offline style" is the Protomaps look. I don't know whether the Protomaps tiles carry paths, `sac_scale` or trail details at the zooms I need, or whether I'd draw trails from Cantino data as GeoJSON overlays (the guide hints at the latter).
- **No testing story** (final classes, native-only), one exception type with a string message, and no Compose sample.
- **Project signals:** 0 stars, released today, single maintainer, personal namespace, not on Maven Central and unsigned. A hiking app's offline data is safety-adjacent, so I care about longevity.

## 3. Features I'd want next (ranked for my app)

1. **Geometry helpers:** a way with resolved coordinates in one call, and route-relation assembly into ordered line strings with gap reporting. This is the single biggest win for any trail or transit app.
2. **Batch `get(ids)`** (or a coordinate-only fast path) to cut JNI overhead.
3. **Several files per area:** a second PMTiles (raster DEM or hillshade) published atomically with the area.
4. **Rural and large-area evidence:** benchmarks for a 50×50 km park, import memory curves, and a published size limit or guidance.
5. **Byte-range resume** for the PBF and basemap downloads.
6. **Pre-download size estimate** (even a rough one from SliceOSM or a density heuristic).
7. **Import profiles** ("outdoor": paths, POIs, water, natural; skip buildings) to shrink disk.
8. **Polygon or corridor areas,** or at least several bboxes per area for a trail corridor.
9. iOS, armeabi-v7a, Maven Central with signing.
10. A hiking sample (trails as GeoJSON over the basemap, route relation detail) next to the café app.

## 4. Questions for the maintainer

1. What's the realistic area limit today? Has anyone imported a 50×50 km or 100×100 km rural region on a mid-range phone, and what were the disk, memory and time numbers?
2. Does SliceOSM accept bboxes that large, and what are its rate limits, terms and expected lag for production apps? Is self-hosting via `sliceBaseUrl` supported and documented?
3. Is geometry assembly (ways, route relations) in scope for the core, or will it stay app-side? Would you accept a contribution?
4. Is offline routing on the roadmap? If so, built on this store or separate?
5. Can an area carry more than one basemap file (vector plus raster DEM)? If not, what's the recommended way to keep a terrain PMTiles in sync with the area's lifecycle?
6. Do Protomaps basemap tiles include footpaths and trail attributes at z14–15, or should trails always come from Cantino data as an overlay?
7. Can an app hold several non-overlapping areas (different IDs) for a multi-region trip? What happens if they touch or overlap?
8. Is byte-range resume planned? It's listed as "not in this release".
9. What's the iOS timeline? Would you accept Kotlin Multiplatform bindings over the C ABI?
10. Who maintains this, and what's the path to 1.0 and Maven Central?
