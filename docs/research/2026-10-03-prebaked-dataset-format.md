# Pre-baked area dataset: what should the phone download?

Date: 2026-10-03. Status: proposal for the fallback server component (not being built yet). Web sources were not re-fetched this session; spec details are from memory and marked (unverified) where they matter.

## TL;DR

1. Ship the **finished SQLite store file, whole-file zstd-compressed** (`area.db.zst`) plus a small signed JSON manifest. No custom format.
2. Compressed size is ~2.3-3x the source PBF, i.e. ~3-4x smaller than the raw db (measured below). Decompress streaming to the staging dir (~0.2-0.3 s for 130-170 MB), verify, publish by atomic rename: the existing HANDOFF.md model, with "import" replaced by "decompress".
3. Refresh = full re-download at first; add a server-built zstd `--patch-from` delta (base version to new) only if measurements justify it. Never apply minutely .osc on device.
4. Base files are immutable and swappable; edits live in a separate `edits.db` ATTACHed at open. Overlapping areas are separate base files; objects are deduped by (kind, id, version) at query time, newest edit/base wins.
5. Keep (e) the null option (PBF + on-device import) as the permanent path for custom bboxes. Only build the server if the import-vs-download timing on mid-range phones loses.

## Measurements (this session)

Real spike dbs from the SLC extract (PBF = 13 MB; 4 KiB pages; file copied, not modified):

| db | raw | zstd -3 | zstd -19 | gzip -6 | xz -9 |
|---|---|---|---|---|---|
| spike A (`a.sqlite`, nodes/ways/way_nodes/tags/strings + spatial) | 169 MB | 57.4 MB (2.9x) | 41.0 MB (4.1x) | 61.3 MB | n/m |
| spike B (`b.sqlite`, nodes/ways/tag_index/geo) | 129 MB | 38.0 MB (3.4x) | 29.5 MB (4.4x) | 39.4 MB | 21.6 MB |

Timings (this x86 laptop, not a phone): zstd -3 compress 0.2-0.3 s, -19 compress 15-22 s, zstd decompress 0.15-0.28 s. Caveats: spikes are in flux and unvacuumed; ratios will move with the final schema; brotli was not installed so not measured (expect ~zstd-19 ratios, slower decode); phone decode speed unmeasured. Even at -19 the download is 2.3-3.2x the PBF, because PBF is already zlib-compressed delta-coded blocks and indexes are low-entropy in SQLite but not free.

## Comparison

| | (a) .db + whole-file zstd | (b) page-level compression / VFS / rsync | (c) tiled set of SQLite / PMTiles-like archive | (d) custom format (FlatBuffers, PBF as transport) | (e) PBF + fast on-device import |
|---|---|---|---|---|---|
| Download | 30-57 MB for SLC (measured) | similar to (a) at best; per-page loses 10-30% ratio (unverified) | +overhead per tile, duplicated boundary objects | smallest if columnar+delta; ~PBF-ish 13-20 MB | 13 MB (best) |
| Time to first query | download + 0.3 s decompress + open (ms). Best | no decompress, but every read pays decode | open only needed tiles; excellent for regions | needs import/index build unless format is itself queryable | download + import (5 s on Pixel 8 for OSMExpress; SQLite import cost pending) |
| Device memory | page cache only; stream decode uses a zstd window (few MB) | VFS adds per-page buffers | per-tile open handles | depends; zero-copy FlatBuffers needs mmap | import peak memory (the thing to measure) |
| Disk | raw size (130-170 MB SLC) | smaller on disk (ZIPVFS is proprietary; sqlite-zstd is a row-level ext, not a VFS) | raw size per tile | small | raw size |
| Integrity | sha256 of .zst + sha256 of .db in signed manifest; `PRAGMA integrity_check`/quick_check | cksumvfs adds per-page checksums (in-file, detects bitrot) | per-tile hash in directory | per-blob hash | hash of PBF only |
| Schema versioning | `PRAGMA user_version` + `application_id` + manifest `schema`; reader refuses newer | same | per-tile, mixed versions possible (worse) | version in schema; evolvable (FlatBuffers/protobuf) | PBF is stable; schema is local |
| CDN / range / resume | single object; resume by byte-range on the .zst (a stream decoder can resume only by re-reading; download-to-disk first, then decode) | needs custom VFS over HTTP for range reads; fragile | native: range requests per tile | single or tiled | single object, resumable |
| Server build cost | import once on server (we already have the importer) + zstd | high: custom VFS needed on both ends | high: tiler, boundary dedupe, directory | highest: new writer, reader, tests | nil (SliceOSM exists) |

Verdict: (a) wins on every axis that matters now (time-to-first-query, simplicity, server cost). (b) page VFS adds a read-path dependency in our Rust core on every query for a saving zstd already gives at transfer time; take only cksumvfs-style checksums if corruption shows up. (c) only pays once datasets are region-sized and partial downloads matter; PMTiles-like layout is the right idea then (see overlap section). (d) re-solves what SQLite already solves (random access, indexes, atomic update, tooling); the SQLite appfileformat page makes the same argument. (e) remains the baseline to beat.

## Flow (plugs into staged import / atomic publish)

```
server (area build)            phone
-------------------            -----
import PBF -> area.db
VACUUM, user_version, ANALYZE
zstd -19 -> area.db.zst  ---->  GET manifest.json (+sig)  -- schema ok? version newer?
manifest.json (signed)          Range GET area.db.zst  (resumable, to staging/.part)
                                verify sha256(.zst)  --fail--> discard
                                zstd -d  -> staging/area.db
                                verify sha256(.db), user_version, quick_check
                                fsync; atomic rename -> areas/<id>@<ver>.db   (publish)
                                open read-only, ATTACH edits.db; drop old version after last reader closes
```

Same private-sibling-staging and rename publication as today; only the "import" step is swapped, so failure semantics (existing area stays intact) are unchanged.

## Manifest (JSON, signed with ed25519 detached signature)

`format: 1`, `area_id`, `dataset_version` (monotonic), `osm_replication_seq` and `osm_timestamp`, `bbox`, `schema_version` (= db `user_version`), `min_reader_schema`, `compression: zstd`, `zstd_level`, `compressed_bytes`, `sha256_compressed`, `uncompressed_bytes`, `sha256_db`, `url` (immutable, content-addressed name), optional `patch_from: [{base_version, url, bytes, sha256}]`, `counts` (nodes/ways/relations), `generator` version. Signature check before download of the big file; key pinned in the app.

Compatibility rule: reader opens any db with `min_reader_schema <= reader_schema <= schema_version`-compatible via additive-only changes within a major; a bumped major means the old datasets are re-fetched or locally re-imported from PBF.

## Update strategy

| Option | Server cost | Phone cost | Verdict |
|---|---|---|---|
| Full re-download | zero extra | full size; atomic, simplest | default; city-sized (30-60 MB) is tolerable on wifi |
| zstd `--patch-from=old` delta of .db (or of the .zst-decompressed bytes) | one diff per (base, new) pair; deterministic build needed so db pages stay aligned | needs old .db present (it is), apply in seconds; size unmeasured | best candidate if daily/weekly refresh matters; measure |
| SQLite session extension changeset/patchset | needs both dbs and PKs on all tables; compact for row changes | `sqlite3changeset_apply` in-place: breaks immutable-base model, conflicts with local edits, rowid ids unstable across rebuilds | no |
| `sqldiff` (SQL text) | cheap | slow apply, in place | no |
| `sqlite_rsync` (SQLite 3.46+) | needs live origin and replica reachable over ssh/pipe, not a CDN | in place, page-level | no for CDN delivery (verify availability on target) |
| Minutely .osc on device | none | apply on-device + parse + merge with edit overlay; breaks "base is immutable"; needs diff-feed bandwidth | no |
| Per-tile replacement | needs (c) | small | later, with regions |

OSM changes are spatially sparse but a db rebuild shifts pages, so a binary delta may be poor unless the builder is deterministic and inserts in stable id order (it should: sorted by id). That is the key unknown; measure it (below).

## Edits and overlap model

- Base `area@ver.db`: opened read-only (`mode=ro`, `immutable=1` is safe because the file never changes after publish), swapped by renaming a new version in; readers pin the old file until closed.
- `edits.db` (single, device-owned, read-write): objects a user created/modified/deleted, with base version they were derived from, plus the upload journal. Attached as `edits`; queries are "edits shadow base" by (kind, id), with tombstones for deletes.
- Refresh with pending edits: new base arrives, rebase journal entries whose base version differs; conflicts are detected when the base's object version > the edit's base version. This is why raw `version` metadata must ship in the base.
- Multiple/overlapping areas: one base file per area, queried as a UNION; dedupe by (kind, id), highest `version` wins, edits first. Boundary ways: area builder includes complete ways touching the bbox (all nodes, not clipped), so each file is self-consistent; the duplicated nodes are the dedupe cost, small for city areas. Do not clip ways to the bbox (SliceOSM "complete ways" behavior should be verified for our extracts).
- Region-scale later: shard by fixed grid (e.g. z8 or S2 cell) each as its own .db with the same layout and a PMTiles-style directory manifest (tile id, offset/length, hash). Same objects across tiles are deduped by id at query time. This is an additive change to the manifest, not a new format.

## Prior art

- PMTiles v3: single-file archive, 127-byte header, root directory + leaf directories, entries with run-length (identical consecutive tiles), clustered offsets, served by HTTP range requests; spec https://github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md. Good model for (c); it is read-only and has no concept of editing.
- MBTiles: SQLite file with `tiles` table; proves a SQLite file as a downloadable geodata artifact. https://github.com/mapbox/mbtiles-spec
- Organic Maps `.mwm`: one custom binary per region, downloaded whole; updates by regenerated binary diffs of mwm files, with edits kept separately in an OSM-edit store (details unverified). https://github.com/organicmaps/organicmaps/blob/master/docs/MWM.md (unverified path).
- OsmAnd `.obf`: per-region compressed custom binary (map, POI, routing, address in one), full re-download plus separate daily/hourly "live update" obf diffs (unverified). https://osmand.net/docs/technical/osmand-file-formats/osmand-obf/
- Overture / GeoParquet: columnar, excellent for analytics and range reads with row-group stats and a bbox column; poor fit for phone random-id lookup and tag queries (no secondary index, per-row-group decode, heavy reader). https://geoparquet.org, https://docs.overturemaps.org
- Valhalla tiles / `valhalla_build_extract`: tile hierarchy packed into a tar with an index for mmap access; a "directory of tiles, not a database" precedent for (c). https://valhalla.github.io/valhalla/
- OSRM: preprocessed `.osrm*` file set, rebuilt per extract, not updated incrementally (from memory).
- Apple/Google offline maps: formats and update mechanics are not publicly documented; Google offline areas are expiring downloads and auto-refreshed (unverified).
- SQLite application file format guidance (atomic transactions, single-file, versioned via `application_id`/`user_version`): https://www.sqlite.org/appfileformat.html. Also https://www.sqlite.org/cksumvfs.html, https://www.sqlite.org/rsync.html, https://www.sqlite.org/sessionintro.html, https://www.sqlite.org/sqldiff.html, https://github.com/phiresky/sqlite-zstd.

## Validation measurements

1. **Total time-to-first-query on a mid-range Android phone** (not only Pixel 8): download (30/60/100 Mbps) + zstd decode + open, versus PBF download + SQLite import (spike winner). Break-even bandwidth decides whether the server is worth building at all.
2. **Delta size**: build the SLC db at two OSM timestamps (e.g. 1 and 7 days apart) with sorted-id inserts; compare `zstd --patch-from` size, session-extension patchset size, and full .zst. Decides full vs delta.
3. **On-device decode peak memory and free-space need**: staging needs compressed + raw + (old version) simultaneously (~60 + 170 + 170 MB for SLC with spike A); check against low-storage phones, and with a streaming decode that deletes the .zst last. Also repeat compression ratio on the final schema after VACUUM, for a 10x-larger region (so scale is checked, not extrapolated).

## What would change the recommendation

- Phone import of a PBF is under ~10 s and memory-safe: drop the server (option e).
- Final db is much larger than ~10x PBF (compressed more than ~4x PBF): consider a leaner schema before any format work, or option (d) with delta-coded columns.
- Regions of GB scale or users needing partial areas: move to (c), tiles as SQLite shards with a PMTiles-like directory.
- Storage-constrained devices: revisit (b) page compression, accepting a custom VFS.

## Open questions

- Which db (A or B) wins, and is it deterministic to rebuild (stable page layout)?
- Does SliceOSM return complete ways at the bbox edge, and are relation members outside the bbox kept as bare references?
- Who owns the signing key, and is a signature required for v1 or a hash from a TLS-served manifest enough?
- Is a pre-baked server worth it for app-defined (arbitrary) bboxes, or only for a catalog of fixed areas? Arbitrary bboxes cannot be cached usefully; the fallback realistically serves a fixed catalog.
