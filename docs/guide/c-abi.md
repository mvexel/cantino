# C ABI

[`include/cantino.h`](../../include/cantino.h) is the contract between the
Rust core and every platform adapter. Android's JNI layer wraps these
functions; the Swift adapter ([`swift/`](../../swift/README.md), platform-neutral
so far, tested on Linux) calls them directly; any
language with a C FFI can too (`scripts/mobile-api-smoke.py` drives it from
Python `ctypes`). The header is hand-written and documents every function.

```text
Kotlin (OsmStore, AreaManager)  Swift (OsmStore, swift/)  Python ctypes (tests)
        │ JNI (src/android.rs)          │ CCantino module        │
        └─────────── C ABI: include/cantino.h, JSON in and out ─┘
                                 │
                       Rust core (src/), SQLite
```

## Conventions

| Rule | Detail |
| --- | --- |
| Return codes | `0` success, `1` nothing to return (`cantino_get`, `cantino_way_coordinates`: not found; `cantino_area_published`: no area; `cantino_classify_failure`: not a failure) or aborted (`cantino_area_commit`), `-CANTINO_ERROR_*` status on failure |
| Strings | UTF-8, NUL-terminated. Structured input and output is JSON |
| Ownership | Every non-NULL `char*` the library returns (result **or** `*error`) is owned by the caller and freed with `cantino_free`, including error strings returned alongside a failure. `cantino_free(NULL)` is a no-op |
| Handles | `CantinoStore*`, `CantinoBasemapPlan*`, `CantinoBasemapAssembler*` belong to the thread that created them. Calls from another thread return `-CANTINO_ERROR_WRONG_THREAD` with an error and leave the handle untouched |
| Error category | The failing call returns its `-CANTINO_ERROR_*` code directly (see [Errors](#errors)) |
| Panics | Never unwind across the ABI; they become `-CANTINO_ERROR_INTERNAL` + error |
| Returned data | Owns copies; valid after the handle is closed |

## Function groups

| Group | Functions | Threading |
| --- | --- | --- |
| Store | `cantino_open`, `cantino_get`, `cantino_get_many`, `cantino_query`, `cantino_way_coordinates`, `cantino_close` | Owner thread |
| Import | `cantino_import(input, destination, options, report, error)` | Any thread; synchronous, seconds of CPU and disk: never the UI thread |
| SliceOSM protocol | `cantino_slice_job_request`, `cantino_slice_job`, `cantino_slice_progress` | Pure functions, any thread. The adapter does HTTP; these build requests and parse responses |
| Basemap extract | `cantino_basemap_plan_*`, `cantino_basemap_asm_*` | Sans-IO state machine; owner thread per handle, fetch on any thread |
| PMTiles info | `cantino_basemap_info(path)` | Any thread |
| Area store | `cantino_area_validate_id`, `cantino_area_layout`, `cantino_area_prepare_staging`, `cantino_area_discard_staging`, `cantino_area_write_staged_metadata`, `cantino_area_commit`, `cantino_area_recover`, `cantino_area_published` | No handles, any thread; calls on one area serialize on the area lock. See [Area store](#area-store) |
| Failure classification | `cantino_classify_failure(input)` | Pure function, any thread. See [Download failures](#download-failures) |

## Example: open and query

```c
CantinoStore *store = NULL;
char *json = NULL, *error = NULL;
int32_t code = cantino_open("/path/city.sqlite", &store, &error);
if (code != 0) {
    fprintf(stderr, "%s (category %d)\n", error, code);
    cantino_free(error);
    if (code == -CANTINO_ERROR_INVALID_FILE) { /* not an area, or old format: re-import */ }
    return;
}
const char *q = "{\"tags\":[{\"Equals\":[\"amenity\",\"cafe\"]}],"
                "\"bbox\":{\"west\":-111.90,\"south\":40.755,\"east\":-111.88,\"north\":40.77},"
                "\"limit\":100}";
if (cantino_query(store, q, &json, &error) == 0) {
    puts(json);                 /* JSON array ordered by (kind, id) */
    cantino_free(json);
} else {
    cantino_free(error);
}
cantino_close(store, &error);   /* same thread as cantino_open */
cantino_free(error);
```

Query JSON: `tags` (array of `{"Equals":[k,v]}` / `{"Exists":k}` /
`{"NotExists":k}`), optional `bbox`, optional `after` cursor
`{"type":"node","id":123}`, `limit` 1..10000, optional `max_candidates`.
`NotExists` never drives a query: a query whose only filters are `NotExists`
and that has no `bbox` returns `-CANTINO_ERROR_INVALID_ARGUMENT`. Semantics are the same as the Kotlin
`Query` ([Querying](querying.md)).

## Geometry and batch lookups

| Function | Input | Output |
| --- | --- | --- |
| `cantino_get_many(store, request, json, error)` | JSON array of IDs, `[{"type":"node","id":1},{"type":"way","id":7}]`, at most 10000 | JSON array, one entry per ID in input order: the object, or `null` when it is not in the area |
| `cantino_way_coordinates(store, way_id, json, error)` | Way ID | Flat JSON array of e7 integers `[lat_e7,lon_e7,lat_e7,lon_e7,...]` in way order (repeats kept), `null,null` for a node outside the area. `1` when the way is not in the area |

## Errors

Every failing call returns a negative category code and writes a developer-facing
message to `*error` (free it). The category constants are positive; compare a
failure status to their negative, e.g. `status == -CANTINO_ERROR_IO`.
Branch on the returned code, never on message text.

| Return code | Category constant | When |
| --- | --- | --- |
| -1 | `CANTINO_ERROR_INVALID_ARGUMENT` | Bad query (limit, only `NotExists` filters without bbox), invalid bbox, too many spatial candidates, batch over 10 000 IDs, non-positive ID, unknown kind, NULL pointer, malformed JSON (also a SliceOSM response the adapter passed on), a basemap response of the wrong id or length |
| -2 | `CANTINO_ERROR_INVALID_FILE` | Not an area database, an area of another format version (re-import), a corrupt/truncated/unreadable PBF or XML input or one breaking the snapshot rules (unsorted or duplicate IDs), a malformed or unsupported PMTiles archive |
| -3 | `CANTINO_ERROR_IO` | Missing file, permission denied, disk full, I/O error, out of memory, a database locked by another process |
| -4 | `CANTINO_ERROR_WRONG_THREAD` | A store, plan or assembler handle used from a non-owner thread (the handle is untouched) |
| -5 | `CANTINO_ERROR_INTERNAL` | A bug in the core or a caught panic: report it |

In the Rust core the categories are `ErrorKind` (`Error::kind()`); every `Error` variant, and every
SQLite result code, maps to exactly one of them. The Android adapter turns
them into `CantinoException.InvalidArgument` / `InvalidFile` / `Io` /
`WrongThread` (and `IllegalStateException` for internal errors).

## Basemap extract flow

The extract engine never does I/O; the adapter fetches byte ranges with
whatever HTTP stack the platform has:

```text
plan_new(bbox, zooms, overfetch)
  → plan_first_request → fetch → plan_feed ─┐  repeat for every {"fetch":[...]} step
                                  ▲─────────┘  until {"tiles_ready": TilePlan}
  → plan_into_assembler(staging)
  → for each TilePlan request: fetch → asm_write_range (bytes) or asm_write_range_file (path)
  → asm_finish(output)   header written last, fsync, atomic rename
  → asm_free
```

Each range response must be `206` with exactly the requested length; a wrong
id or length is rejected without changing state (re-fetch and feed again).
The Kotlin driver (`BasemapExtract` in `Basemap.kt`) is a reference
implementation: 4 parallel requests, tile ranges streamed to files.

## Area store

*Unreleased.* The on-disk layout of an app's downloaded areas and the
crash-safe replacement of one, implemented once in the core
(`src/area_storage.rs`) so every adapter behaves the same. The adapter
chooses one root directory (Android: `filesDir/cantino-areas`) and passes it
on every call; there are no handles and no in-memory state.

```text
<root>/<area_id>.sqlite      published OSM data
<root>/<area_id>.pmtiles     published basemap (only when the version has one)
<root>/<area_id>.json        metadata sidecar
<root>/<area_id>.commit      commit journal {"work_id":"<uuid>","basemap":true|false}, only while publishing
<root>/<area_id>.lock        lock file (empty, never deleted)
<root>/.staging/<area_id>/<work_id>/area.sqlite, basemap.pmtiles, area.json
```

This is Cantino 0.2.0's layout plus the lock file, byte for byte (same names,
journal text and sidecar JSON), so areas written by 0.2.0 are read and
recovered unchanged. `area_id` is 1 to 64 characters from `[A-Za-z0-9_-]`;
`work_id` is a UUID (either case, lower case in paths). Invalid IDs are
`CANTINO_ERROR_INVALID_ARGUMENT`, file system failures `CANTINO_ERROR_IO`.

One download run, as the Android worker drives it:

```text
prepare_staging(root, area, run)          rolls a pending commit forward, deletes other runs' staging,
                                          creates this run's; writes the layout (staged_data, ...)
  import into staged_data and/or basemap into staged_basemap
write_staged_metadata(root, area, run, sidecar JSON)   report null without data
commit(root, area, run, parts, hook, context)          parts: CANTINO_AREA_PART_DATA | _BASEMAP
  ├ hook(context, BEFORE_COMMIT)          under the lock; non-zero = abort, nothing changed (returns 1)
  ├ journal written atomically            ← the commit point
  ├ hook(context, AFTER_COMMIT_POINT)     answer ignored
  └ rename basemap and data (or delete an old part the version lacks), sidecar; fsync; delete journal and staging
discard_staging(root, area, run)          always at the end of a run; keeps a pending commit's staging
```

Readers call `cantino_area_published` (it rolls a pending commit forward,
then reads under the lock) and get `{"data","basemap","metadata"}` or `1`.
`cantino_area_recover` finishes an interrupted commit without reading.
After a kill at any point, the next `recover`, `published` or
`prepare_staging` yields the complete old area (killed before the journal
was in place) or the complete new one (after), never a mix: tested by
killing a commit after every step and recovery after every roll-forward step
(`src/area_storage/tests.rs`).

**The sidecar.** `write_staged_metadata` writes the JSON text verbatim after
checking its shape: `{"bbox":{"west","south","east","north"}, "name",
"snapshot_timestamp" (string|null), "imported_at_millis", "report":
{"counts":{"nodes","ways","relations"},"database_bytes"}, "basemap": null |
{"kind":"url"|"extract","source_url","bytes","addressed_tiles","min_zoom",
"max_zoom","requests","transferred_bytes"}, "work_id" (UUID|null)}` (extra
keys are ignored). `published` returns it as `metadata` only if
`report.database_bytes` equals the data file's size and `basemap.bytes` the
basemap file's (or both are absent); otherwise `metadata` is `null`
("unknown", never wrong).

**Locking.** Every operation that touches published files holds the area
lock: an in-process lock (always) plus an exclusive `flock` on
`<area_id>.lock` for other processes of the app (best effort: skipped if the
file cannot be opened). A reader never sees a half-published area; it waits
milliseconds for a commit in progress. The commit hook runs under the lock
on the calling thread and must not call `cantino_area_*` for the same area.

## Download failures

*Unreleased.* `cantino_classify_failure` is the table the download worker
uses to decide what a failure means, so every platform retries and reports
identically. Input is one of:

| Input | Meaning |
| --- | --- |
| `{"http":status,"context":"job"}` | A SliceOSM job status or file GET (404 = the job is gone) |
| `{"http":status,"context":"request"}` | Any other request: job submit, basemap URL |
| `{"http":status,"context":"range"}` | A basemap range request (only 206 succeeds) |
| `{"io":"network"}` / `{"io":"storage"}` | An I/O error reading the network / writing a local file |
| `{"native":code,"context":c}` | A core error (`CANTINO_ERROR_*` 1..5) from the import or PMTiles validation (`default`), the basemap extract engine (`engine`), building (`protocol_request`) or reading (`protocol_response`) a SliceOSM message |

Output: `{"class","reason","inline_retry","scheduler_retry"}`, or `1` when
the input is not a failure (a 2xx; 206 for `range`).

| Class | Inline retry | Scheduler retry | Examples |
| --- | --- | --- | --- |
| `transient` | yes | yes | network I/O; HTTP 408, 429, 5xx; a SliceOSM answer that does not parse |
| `storage` | **no** | yes (the next run waits for storage-not-low) | local write failed; `CANTINO_ERROR_IO` from the import |
| `job_gone` | no | yes, resubmitting the job | 404 on a job status or file |
| `permanent` | no | no | other 4xx; range 200/416; `CANTINO_ERROR_INVALID_FILE` from the import; any engine error |

`reason` is the app-facing `FailureReason` in lower case: `network`,
`server`, `invalid_request`, `storage`, `invalid_data`, `unknown`.

## Stability

The API and ABI are under development and may change freely. The area file
format has a separate `FORMAT_VERSION` in `src/schema.rs`; re-import data
when it changes.
