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
// Return codes: 0 success; 1 object missing (get only); -1 error.
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
// Runs a query (JSON) and writes a JSON array of objects ordered by (kind, id).
// Tag filters are ANDed; a bbox yields spatial candidates (tagged nodes exact,
// ways/relations by bounding box; untagged nodes never). limit is 1..10000;
// exceeding max_candidates is an error, never a truncation.
// Query example: {"tags":[{"Equals":["amenity","cafe"]}],"limit":100}
// Optional bbox: {"west":-111.89,"south":40.758,"east":-111.886,"north":40.762}
// Cursor example: {"type":"node","id":12345}; node=0, way=1, relation=2.
int32_t cantino_query(CantinoStore *store,const char *request,char **json,char **error);
// Imports an OSM PBF/XML file into an area database at `destination`,
// published atomically (a failure leaves an existing file intact), and writes
// {"counts":{"nodes","ways","relations"},"database_bytes"} to *report.
// Options: {"preserve_untagged_metadata":bool,"cache_mb":MiB}.
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
