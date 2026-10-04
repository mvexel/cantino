# Performance and sizes

All numbers: Pixel 8 (Android 17, arm64), 2026-10-03, Cantino's SQLite core
through the AAR (ART, JNI, JSON and Kotlin decoding included). Raw records in
[`docs/bench/`](../bench/).

## City extract: Salt Lake City

SliceOSM bbox `-112.10,40.70,-111.80,40.85` (about 25×17 km), 13.1 MB PBF,
1.44M nodes, 207k ways, 1,473 relations.
Record: [`2026-10-03-slc-sqlite-core-pixel8.json`](../bench/2026-10-03-slc-sqlite-core-pixel8.json).

| Measure | Value |
| --- | --- |
| Area database | 128.5 MB (9.8× the PBF) |
| Import, in the app | 8.2 s (5.7 s as a plain native binary on the same phone) |
| Peak memory during import | ~156 MB above the app's own baseline |
| Tag query, 175 cafés (`amenity=cafe`) | p50 11.9 ms, p95 22.3 ms (cold 87.5 ms) |
| Bbox + tag query (downtown, 15 cafés) | p50 3.3 ms, p95 3.7 ms |
| `get` by ID | p50 0.08 ms, p95 0.12 ms |

Query time through the AAR is dominated by crossing JNI as JSON and decoding
it (about 0.08 ms per object); the Rust core itself answers the 175-café
query in 3–4 ms. Ask for the objects you need, page with `limit`.

## Area download: 10×10 km

Downtown Salt Lake City, live SliceOSM and Protomaps, basemap `Extract` z0–15.

| Measure | Value |
| --- | --- |
| Total to `Ready` | 27.4 s (refresh of the same area: 14.0 s) |
| of which slicing (server) | 19.5 s |
| PBF | 7.8 MB, downloaded in 0.8 s |
| Import | 3.2 s → 75.6 MB database (778k nodes, 123k ways, 931 relations) |
| Basemap | 2.3 s; 28 requests, 7.6 MB transferred, 6.5 MB file, 196 tiles |

A small area (0.01°×0.006°, a few blocks) is `Ready` in about 4 s without a
basemap and 6 s with one (1 MB basemap).

## Large areas: 50×50 km

Two 50×50 km boxes at opposite ends of mapping density, same phone, same day
(SliceOSM snapshot 2026-10-04T03:48Z; basemap Protomaps build 20261003,
`Extract` z0–15). Records: [`2026-10-03-zion-50km-pixel8.json`](../bench/2026-10-03-zion-50km-pixel8.json),
[`2026-10-03-oberland-50km-pixel8.json`](../bench/2026-10-03-oberland-50km-pixel8.json);
the SLC row was re-run alongside them ([`2026-10-03-slc-0.2.0-pixel8.json`](../bench/2026-10-03-slc-0.2.0-pixel8.json)).

| Area | PBF | Area file | POI profile | Basemap | Import (in app) | Peak memory above baseline |
| --- | --- | --- | --- | --- | --- | --- |
| Zion NP + towns, 50×50 km (sparse) | 2.9 MB, 0.5M nodes | 27.5 MB | 2.1 MB | 6.2 MB | 1.6 s | 68 MB |
| Salt Lake City, ~25×17 km (city) | 13.1 MB, 1.4M nodes | 128.5 MB | 8.8 MB | — | 8.2 s | 155 MB |
| Bernese Oberland (Thun–Interlaken–Grindelwald), 50×50 km (dense) | 26.3 MB, 3.0M nodes | 208.7 MB | 8.3 MB | 35.2 MB | 13.4 s | 204 MB |

Bboxes: Zion `-113.333,37.075,-112.767,37.525`; Oberland `7.53,46.465,8.19,46.915`.
SliceOSM sliced them in 11 s and 63 s. Queries stay fast at this size
(Oberland: `get` p95 0.1 ms, bbox p95 0.2 ms, 116-café tag query p95 22 ms).

Size depends on mapping density far more than on the box: the sparse
50×50 km area is smaller than 10×10 km of downtown Salt Lake City. Import
memory grows with node count (about 50–70 bytes per node on top of a
~50 MB floor), so a dense 50×50 km area is fine on current phones;
country-scale areas are not a goal (see the roadmap).

## App size

The AAR is 3.5 MB; each ABI's `libcantino.so` is about 3.3 MB (stripped). The
native library depends only on libc, libm and libdl. MapLibre Native adds
about 13 MB per ABI if you use it. Ship only the ABIs you need (`arm64-v8a`, `armeabi-v7a`, `x86_64`)
(`abiFilters`), or split per ABI with an App Bundle.

## Rules of thumb

- Disk ≈ 10× the PBF size, and ≈ 75 MB per 10×10 km of dense city, plus
  ~6.5 MB of basemap at z15. An [import profile](downloading.md#keep-only-what-you-need-import-profiles)
  cuts that to ~7% for a POI app.
- Import memory grows with the node count: ~155 MB extra for a city, ~205 MB
  for a densely mapped 50×50 km region; country scale is not a 0.x goal.
- Size follows mapping density, not area: 50×50 km is 27 MB in the
  Utah desert and 209 MB in the Swiss Alps (basemap 6–35 MB more).
- Keep one store open; `open` costs more than a query.
- Prefer a tag filter plus bbox over a bare bbox: tag filters are the most
  selective index.
