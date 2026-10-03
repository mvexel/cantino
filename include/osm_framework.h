#pragma once
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif

typedef struct FrameworkStore FrameworkStore;
// Return codes: 0 success; 1 object missing (get only); -1 error.
// Strings and paths are UTF-8. Every non-NULL result/error buffer must be freed
// with osm_framework_free, including buffers returned alongside an error.
// Store handles belong to their creating thread. Open/lookup/query/close must
// run on the same worker thread. Returned JSON owns copies of all object data.
int32_t osm_framework_open(const char *path,FrameworkStore **store,char **error);
int32_t osm_framework_close(FrameworkStore *store,char **error);
void osm_framework_free(char *value);
int32_t osm_framework_get(FrameworkStore *store,int32_t kind,int64_t id,char **json,char **error);
// Query example: {"tags":[{"Equals":["amenity","cafe"]}],"limit":100}
// Optional bbox: {"west":-111.89,"south":40.758,"east":-111.886,"north":40.762}
// Cursor example: {"type":"node","id":12345}; node=0, way=1, relation=2.
int32_t osm_framework_query(FrameworkStore *store,const char *request,char **json,char **error);
// Import options may be NULL for defaults or a JSON object. Import is synchronous
// and performs disk/CPU work; the adapter must schedule it off the UI thread.
int32_t osm_framework_import(const char *input,const char *destination,const char *options,char **report,char **error);
// SliceOSM protocol helpers: pure functions, callable from any thread, no
// handle. The adapter does the HTTP; these build requests and read responses.
// `base` is NULL for https://slice.openstreetmap.us/ or an http(s) base URL.
// job_request: bbox {"west","south","east","north"} -> {"url","body"}; POST
//   body verbatim as application/json; the response text is the job UUID.
// job: submit response text (or a persisted job ID) -> {"job_id","status_url",
//   "download_url"}; rejects anything that is not a UUID.
// progress: status JSON -> {"complete","fraction"(0..1|null),"size_bytes"
//   (null if unknown),"timestamp"(snapshot age, ISO 8601|null)}.
int32_t osm_framework_slice_job_request(const char *base,const char *bbox,const char *name,char **json,char **error);
int32_t osm_framework_slice_job(const char *base,const char *response,char **json,char **error);
int32_t osm_framework_slice_progress(const char *status,char **json,char **error);
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
// Thread confinement as for FrameworkStore: a plan belongs to the thread that
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
typedef struct FrameworkBasemapPlan FrameworkBasemapPlan;
typedef struct FrameworkBasemapAssembler FrameworkBasemapAssembler;
// bbox {"west","south","east","north"} in degrees (west > east crosses the
// antimeridian); zooms -1 = the source archive's, else clamped to it;
// overfetch: extra bytes allowed per wanted byte to save requests (0.05).
int32_t osm_framework_basemap_plan_new(const char *bbox,int32_t min_zoom,int32_t max_zoom,double overfetch,FrameworkBasemapPlan **plan,char **error);
int32_t osm_framework_basemap_plan_first_request(FrameworkBasemapPlan *plan,char **json,char **error);
// bytes may be NULL when len is 0.
int32_t osm_framework_basemap_plan_feed(FrameworkBasemapPlan *plan,uint64_t id,const uint8_t *bytes,size_t len,char **json,char **error);
int32_t osm_framework_basemap_plan_outstanding(FrameworkBasemapPlan *plan,char **json,char **error);
// Consumes the plan on every call that passes the handle check (success or
// not); only a NULL handle or a wrong-thread call leaves it alive. `staging`
// is created/truncated and must be on the output's file system.
int32_t osm_framework_basemap_plan_into_assembler(FrameworkBasemapPlan *plan,const char *staging,FrameworkBasemapAssembler **assembler,char **error);
int32_t osm_framework_basemap_plan_free(FrameworkBasemapPlan *plan,char **error);
// Writes are idempotent; a response of the wrong length is rejected.
int32_t osm_framework_basemap_asm_write_range(FrameworkBasemapAssembler *assembler,uint64_t id,const uint8_t *bytes,size_t len,char **error);
// Streams the response from a file (64 KiB buffer); the caller deletes it.
int32_t osm_framework_basemap_asm_write_range_file(FrameworkBasemapAssembler *assembler,uint64_t id,const char *path,char **error);
int32_t osm_framework_basemap_asm_remaining(FrameworkBasemapAssembler *assembler,char **json,char **error);
int32_t osm_framework_basemap_asm_progress(FrameworkBasemapAssembler *assembler,char **json,char **error);
// Header last, fsync, atomic rename over `output`. Refuses (output untouched)
// while ranges are missing. Free the handle afterwards in every case; freeing
// an unfinished assembler deletes its staging file.
int32_t osm_framework_basemap_asm_finish(FrameworkBasemapAssembler *assembler,const char *output,char **error);
int32_t osm_framework_basemap_asm_free(FrameworkBasemapAssembler *assembler,char **error);
// Validates a local PMTiles file (magic, spec v3, sections within the file)
// and writes {"spec_version","bounds":[w,s,e,n],"center":[lon,lat,zoom],
// "min_zoom","max_zoom","addressed_tiles","tile_entries","tile_contents",
// "tile_type","tile_compression","clustered","file_bytes"}. Any thread.
int32_t osm_framework_basemap_info(const char *path,char **json,char **error);
#ifdef __cplusplus
}
#endif
