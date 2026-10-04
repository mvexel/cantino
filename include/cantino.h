// Cantino C ABI, versioned with the crate (Cargo.toml; 0.1.0 at its first release).
// This header is the contract for platform adapters (Android JNI wraps the
// same functions; the iOS Swift adapter will call them directly). It is
// hand-written: every exported function is declared and briefly documented
// here, and `scripts/mobile-api-smoke.py` exercises it from Python ctypes.
#pragma once
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif

typedef struct CantinoStore CantinoStore;
// Return codes: 0 success; 1 object missing (get, way_coordinates,
// representative_point only); -1 error.
//
// Error categories (since 0.2.0). After a call returns -1, its category is
// cantino_last_error_code(), read on the same thread before the next ABI
// call (errno-style: kept per thread, overwritten by every call, 0 after a
// success). The message in *error is for developers; branch on the code.
// Values are stable ABI: never renumbered, new ones only appended.
#define CANTINO_ERROR_NONE 0
// The caller passed something invalid: bad query (limit, filters, only
// NotExists without bbox), invalid bbox, too many spatial candidates, batch
// over 10000 IDs, non-positive ID, unknown kind, NULL pointer, malformed
// JSON (including a SliceOSM response the adapter passed on), a basemap
// response of the wrong length or id.
#define CANTINO_ERROR_INVALID_ARGUMENT 1
// A file is not what it should be: not an area database, an area of
// another format version (re-import), a corrupt/truncated/unreadable OSM
// PBF or XML input or one breaking the snapshot rules (unsorted or duplicate
// IDs), a malformed or unsupported PMTiles archive.
#define CANTINO_ERROR_INVALID_FILE 2
// The environment failed: missing file, permission denied, disk full, I/O
// error, out of memory, a database locked by another process.
#define CANTINO_ERROR_IO 3
// A store, basemap plan or basemap assembler handle was used from a thread
// other than its owner. The handle is untouched.
#define CANTINO_ERROR_WRONG_THREAD 4
// A bug in the core, including a caught panic. Report it.
#define CANTINO_ERROR_INTERNAL 5
// The CANTINO_ERROR_* category of the last ABI call on the calling thread
// (CANTINO_ERROR_NONE if it succeeded). Any thread; never fails.
int32_t cantino_last_error_code(void);
//
// Strings and paths are UTF-8. Every non-NULL result/error buffer must be freed
// with cantino_free, including buffers returned alongside an error.
// Store handles belong to their creating thread. Open/lookup/query/close must
// run on the same worker thread. Returned JSON owns copies of all object data.

// Opens a published area database read-only into *store. The calling thread
// becomes the handle's owner thread.
int32_t cantino_open(const char *path,CantinoStore **store,char **error);
// Closes and frees a store handle; call once, on the owner thread.
int32_t cantino_close(CantinoStore *store,char **error);
// Frees a string returned by any function here (result or error). NULL is a no-op.
void cantino_free(char *value);
// Looks up one object; kind node=0, way=1, relation=2. Returns 0 with the
// object JSON in *json, or 1 (and no JSON) when it is not in the area.
int32_t cantino_get(CantinoStore *store,int32_t kind,int64_t id,char **json,char **error);
// Looks up several objects in one call. request: JSON array of IDs
// [{"type":"node","id":1},{"type":"way","id":7}]; writes a JSON array with one
// entry per ID in input order: the object, or null when it is not in the area.
// At most 10000 IDs (more is an error, never a truncation).
int32_t cantino_get_many(CantinoStore *store,const char *request,char **json,char **error);
// Way node coordinates in way order (repeats kept) as one flat JSON array of
// e7 integers: [lat_e7,lon_e7,lat_e7,lon_e7,...], with null,null for a node
// outside the area. Returns 1 (and no JSON) when the way is not in the area.
int32_t cantino_way_coordinates(CantinoStore *store,int64_t way_id,char **json,char **error);
// Representative point (kind as for get): writes {"lat_e7","lon_e7"}, or
// returns 1 (no JSON) when the object or all of its geometry is outside the
// area. A label/anchor point, NOT a guaranteed point-on-surface: node = its
// coordinate; closed way = mean of distinct in-area vertices; open way = point
// at half the length of the polyline over in-area nodes; relation = mean of
// its distinct in-area members' points (nested relations up to 8 levels,
// cycles skipped).
int32_t cantino_representative_point(CantinoStore *store,int32_t kind,int64_t id,char **json,char **error);
// Runs a query (JSON) and writes a JSON array of objects ordered by (kind, id).
// Tag filters are ANDed; a bbox yields spatial candidates (tagged nodes exact,
// ways/relations by bounding box; untagged nodes never). limit is 1..10000;
// exceeding max_candidates is an error, never a truncation.
// Filters: {"Exists":k}, {"Equals":[k,v]}, {"NotExists":k}. NotExists is only
// checked on candidates from another filter or the bbox; a query whose only
// filters are NotExists (and no bbox) is an error.
// Query example: {"tags":[{"Equals":["amenity","cafe"]}],"limit":100}
// Optional bbox: {"west":-111.89,"south":40.758,"east":-111.886,"north":40.762}
// Cursor example: {"type":"node","id":12345}; node=0, way=1, relation=2.
int32_t cantino_query(CantinoStore *store,const char *request,char **json,char **error);
// Imports an OSM PBF/XML file into an area database at `destination`,
// published atomically (a failure leaves an existing file intact), and writes
// {"counts":{"nodes","ways","relations"},"database_bytes"[,"profile"]} to *report.
// Options: {"preserve_untagged_metadata":bool,"cache_mb":MiB,"profile":P}, where
// P = {"keep":[{"kinds":"nwr","key":"amenity","values":["cafe"]}]} keeps
// matching objects plus everything they reference ("values" optional).
// Import options may be NULL for defaults or a JSON object. Import is synchronous
// and performs disk/CPU work; the adapter must schedule it off the UI thread.
int32_t cantino_import(const char *input,const char *destination,const char *options,char **report,char **error);
// SliceOSM protocol helpers: pure functions, callable from any thread, no
// handle. The adapter does the HTTP; these build requests and read responses.
// `base` is NULL for https://slice.openstreetmap.us/ or an http(s) base URL.
// job_request: bbox {"west","south","east","north"} -> {"url","body"}; POST
//   body verbatim as application/json; the response text is the job UUID.
// job: submit response text (or a persisted job ID) -> {"job_id","status_url",
//   "download_url"}; rejects anything that is not a UUID.
// progress: status JSON -> {"complete","fraction"(0..1|null),"size_bytes"
//   (null if unknown),"timestamp"(snapshot age, ISO 8601|null)}.
// Builds the job submission: writes {"url","body"}.
int32_t cantino_slice_job_request(const char *base,const char *bbox,const char *name,char **json,char **error);
// Validates a job ID (submit response or persisted ID): writes {"job_id","status_url","download_url"}.
int32_t cantino_slice_job(const char *base,const char *response,char **json,char **error);
// Reads a job status document: writes {"complete","fraction","size_bytes","timestamp"}.
int32_t cantino_slice_progress(const char *status,char **json,char **error);
// --- Basemap extract (PMTiles v3) ------------------------------------------
// Sans-IO: the adapter performs every HTTP range request
// ("Range: bytes=offset-(offset+length-1)", expect 206 and exactly `length`
// bytes; the first request may come back shorter for a tiny archive) and
// feeds the responses back. JSON shapes:
//   ByteRange {"id","offset","length"}  (id unique within one plan)
//   Step      {"fetch":[ByteRange...]} | "wait" | {"tiles_ready":TilePlan}
//   TilePlan  {"requests":[ByteRange...],"transfer_bytes","tile_data_bytes",
//              "archive_bytes","tile_entries","addressed_tiles","tile_contents",
//              "cover_tiles","directory_requests","directory_bytes","min_zoom","max_zoom"}
//   Progress  {"ranges_done","ranges_total","bytes_done","bytes_total"}
// Thread confinement as for CantinoStore: a plan belongs to the thread that
// created it, an assembler to the thread that called plan_into_assembler, and
// every call including *_free must come from that thread (other threads get
// an error and the handle is untouched). Fetch on any threads; hand responses
// to the owner thread.
// Flow: plan_new -> plan_first_request -> plan_feed each response (issuing the
// requests of every "fetch" step) until "tiles_ready" -> plan_into_assembler
// -> write every TilePlan request (asm_write_range, or asm_write_range_file
// with a file holding exactly the response) -> asm_finish -> asm_free.
// A wrong id or length is rejected without changing state (re-fetch and feed
// again); other plan errors are fatal for the plan.
typedef struct CantinoBasemapPlan CantinoBasemapPlan;
typedef struct CantinoBasemapAssembler CantinoBasemapAssembler;
// bbox {"west","south","east","north"} in degrees (west > east crosses the
// antimeridian); zooms -1 = the source archive's, else clamped to it;
// overfetch: extra bytes allowed per wanted byte to save requests (0.05).
// Creates an extract plan into *plan; the calling thread owns it.
int32_t cantino_basemap_plan_new(const char *bbox,int32_t min_zoom,int32_t max_zoom,double overfetch,CantinoBasemapPlan **plan,char **error);
// Writes the first ByteRange to fetch ({"id":0,"offset":0,"length":16384}).
int32_t cantino_basemap_plan_first_request(CantinoBasemapPlan *plan,char **json,char **error);
// Feeds the response to request `id` and writes the next Step.
// bytes may be NULL when len is 0.
int32_t cantino_basemap_plan_feed(CantinoBasemapPlan *plan,uint64_t id,const uint8_t *bytes,size_t len,char **json,char **error);
// Writes the requests issued and not yet fed, as a JSON array of ByteRange.
int32_t cantino_basemap_plan_outstanding(CantinoBasemapPlan *plan,char **json,char **error);
// Consumes the plan on every call that passes the handle check (success or
// not); only a NULL handle or a wrong-thread call leaves it alive. `staging`
// is created/truncated and must be on the output's file system.
int32_t cantino_basemap_plan_into_assembler(CantinoBasemapPlan *plan,const char *staging,CantinoBasemapAssembler **assembler,char **error);
// Frees a plan that was not consumed by plan_into_assembler (owner thread).
int32_t cantino_basemap_plan_free(CantinoBasemapPlan *plan,char **error);
// Writes the response to tile request `id` into the staging file.
// Writes are idempotent; a response of the wrong length is rejected.
int32_t cantino_basemap_asm_write_range(CantinoBasemapAssembler *assembler,uint64_t id,const uint8_t *bytes,size_t len,char **error);
// Streams the response from a file (64 KiB buffer); the caller deletes it.
int32_t cantino_basemap_asm_write_range_file(CantinoBasemapAssembler *assembler,uint64_t id,const char *path,char **error);
// Writes the tile requests not yet written, as a JSON array of ByteRange.
int32_t cantino_basemap_asm_remaining(CantinoBasemapAssembler *assembler,char **json,char **error);
// Writes Progress {"ranges_done","ranges_total","bytes_done","bytes_total"}.
int32_t cantino_basemap_asm_progress(CantinoBasemapAssembler *assembler,char **json,char **error);
// Header last, fsync, atomic rename over `output`. Refuses (output untouched)
// while ranges are missing. Free the handle afterwards in every case; freeing
// an unfinished assembler deletes its staging file.
int32_t cantino_basemap_asm_finish(CantinoBasemapAssembler *assembler,const char *output,char **error);
// Frees an assembler (owner thread); an unfinished one deletes its staging file.
int32_t cantino_basemap_asm_free(CantinoBasemapAssembler *assembler,char **error);
// Validates a local PMTiles file (magic, spec v3, sections within the file)
// and writes {"spec_version","bounds":[w,s,e,n],"center":[lon,lat,zoom],
// "min_zoom","max_zoom","addressed_tiles","tile_entries","tile_contents",
// "tile_type","tile_compression","clustered","file_bytes"}. Any thread.
int32_t cantino_basemap_info(const char *path,char **json,char **error);
#ifdef __cplusplus
}
#endif
