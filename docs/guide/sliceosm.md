# SliceOSM in production

Cantino downloads its OpenStreetMap data from [SliceOSM](https://slice.openstreetmap.us/).
This page collects what is published about the service, what is not, and how
to point Cantino at your own instance. Read it before you ship an app that
depends on the public instance.

## What it is

SliceOSM is "a web downloads portal for fresh OpenStreetMap data": you submit
a bounding box (or a GeoJSON polygon) and get an OSM PBF of that area. It is
an [OpenStreetMap US Community Project](https://openstreetmap.us/our-work/community-charter-projects/)
(formerly Protomaps Extracts). It is built from
[OSM Express](https://github.com/bdon/OSMExpress) (a spatially indexed planet
database kept current by minutely replication), `sliceosm-api` (a Go web
server) and `sliceosm-frontend`; the API and frontend are MIT licensed.
Sources: the [SliceOSM organization README](https://github.com/SliceOSM/.github/blob/main/profile/README.md)
and the [sliceosm-api README](https://github.com/SliceOSM/sliceosm-api).

Cantino uses the bbox form of the job protocol (`POST api/`, poll `api/<uuid>`,
fetch `files/<uuid>.osm.pbf`), implemented in `src/slice.rs`.

## Terms and attribution

- **Data licence.** The data is OpenStreetMap data, ODbL. Your app must credit
  "OpenStreetMap contributors"; see the [attribution rules](../../README.md#license-and-attribution)
  and the [OSMF guidelines](https://osmfoundation.org/wiki/Licence/Attribution_Guidelines).
- **Service terms.** We found no published terms of service for API use.
  There is a [privacy policy](https://github.com/SliceOSM/.github/blob/main/PRIVACY.md)
  (OpenStreetMap US; it names IP address and usage data collected through
  analytics). Cantino sends the area's bbox, and the device's IP address
  reaches the service; say so in your app's privacy policy
  ([Downloading areas: privacy](downloading.md#privacy)).
- **Credit to SliceOSM itself** is not stated as a requirement anywhere we
  found. Crediting it in an about screen is courteous.

## Capacity, rate limits and availability

Published:

- "The hosted instance is provided on a best-effort, volunteer basis. Heavy
  automated use of the API is discouraged - if you need bulk exports please
  use a weekly planet dump + [osmium-tool](https://osmcode.org/osmium-tool/)
  instead." (organization README)
- A server-wide **nodes limit** per job. The `sliceosm-api` default is
  `100000000` nodes, set by the operator; the public instance reports
  `"NodesLimit": 100000000` on `GET https://slice.openstreetmap.us/api/`
  (checked 2026-10-03).
- Task history is kept for 24 hours and result files are meant to be deleted
  after a day (`sliceosm-api` README).

**Not published** (ask OpenStreetMap US, `#sliceosm` on the
[OSMUS Slack](https://slack.openstreetmap.us), or team@openstreetmap.us,
before launch):

- Any per-client or per-IP rate limit, and what "heavy" means.
- An uptime or support commitment (it says best-effort).
- Maximum concurrent jobs and queue behaviour (the status endpoint reports
  `QueueSize`, nothing more).
- Whether the service stays free or at this address.

Each download is one slicing job on a volunteer-run server. An app that
triggers many downloads across many users should talk to OpenStreetMap US
first, or self-host (below). Cantino retries transient failures with backoff
and resumes the submitted job instead of submitting another
([Downloading areas](downloading.md)).

## Data lag

SliceOSM follows minutely replication: "If you've just edited OpenStreetMap, a
new OSM PBF slice reflects those changes up to the minute." How far replication
is behind at a given moment is not published.

Cantino records the server's own timestamp as `AreaMetadata.snapshotTimestamp`
(the `Timestamp` of the job status). Show that as the data's age, not the
download time. The data then ages until the app refreshes (full replace; no
incremental updates in 0.x). See [Concepts](concepts.md#snapshot) and
[Editing apps](editing-apps.md).

## Self-hosting

`AreaConfig.sliceBaseUrl` (default `AreaConfig.DEFAULT_SLICE_BASE_URL`,
`https://slice.openstreetmap.us/`) is the service root: http or https, ending
in `/`. Cantino derives `<base>api/` and `<base>files/` from it, so any server
that speaks the same protocol works.

```kotlin
val config = AreaConfig(sliceBaseUrl = "https://slice.example.org/")
```

To run a compatible server:

1. Build an OSM Express database of the planet or your region
   ([OSMExpress](https://github.com/bdon/OSMExpress)) and keep it current with
   `osmx-update` (minutely replication), as the `sliceosm-api` README describes.
2. Build and run `sliceosm-api`
   (`./sliceosm-api -bind ADDRESS:PORT -exec PATH_TO_OSMX -filesDir RESULTS_DIR -nodesLimit N`;
   the repository has a systemd unit) and serve `RESULTS_DIR` over HTTP.
3. Route `<base>api/` to the API and `<base>files/` to the results directory.
   The public instance's deployment layout is not documented. Cantino's
   instrumented tests (`AreaManagerTest`) run a small fake server with these
   routes, which shows what the client needs: status JSON with `Complete`,
   `Timestamp` and optional progress counters, and the PBF at
   `files/<uuid>.osm.pbf`.

The config is copied into each work request, so a download in flight keeps the
URL it started with. Self-hosting is protocol compatibility, tested against a
fake server; Cantino has not been run against a production self-hosted
deployment.
