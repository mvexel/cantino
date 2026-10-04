# C ABI

[`include/cantino.h`](../../include/cantino.h) is the contract between the
Rust core and every platform adapter. Android's JNI layer wraps these
functions; the iOS Swift adapter (planned) will call them directly; any
language with a C FFI can too (`scripts/mobile-api-smoke.py` drives it from
Python `ctypes`). The header is hand-written and documents every function.

```text
Kotlin (OsmStore, AreaManager)      Swift (planned)      Python ctypes (tests)
        │ JNI (src/android.rs)          │                       │
        └─────────── C ABI: include/cantino.h, JSON in and out ─┘
                                 │
                       Rust core (src/), SQLite
```

## Conventions

| Rule | Detail |
| --- | --- |
| Return codes | `0` success, `1` not found (`cantino_get`, `cantino_way_coordinates`), `-CANTINO_ERROR_*` status on failure |
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

## Stability

The API and ABI are under development and may change freely. The area file
format has a separate `FORMAT_VERSION` in `src/schema.rs`; re-import data
when it changes.
