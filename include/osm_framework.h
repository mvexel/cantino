#pragma once
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
#ifdef __cplusplus
}
#endif
