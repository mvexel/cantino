# Cantino guide

Cantino is the raw OSM data layer for offline, domain-specific Android and iOS
apps. Start with the [quickstart](../../README.md#quickstart) or one of the
reference apps, then read the page for the capability or production concern
you need.

| Page | Read it for |
| --- | --- |
| [Concepts](concepts.md) | Areas, snapshots, refresh, what "published" means |
| [Downloading areas](downloading.md) | `AreaManager`, the `AreaState` machine, cancellation, failures, foreground mode, sizes, privacy |
| [Basemaps](basemaps.md) | `BasemapSource`, hosting PMTiles, the offline style, MapLibre, attribution |
| [Querying](querying.md) | `OsmStore` threading, `Query` semantics, pagination, the object model |
| [Performance and sizes](performance.md) | Measured numbers on a Pixel 8 |
| [Building from source](building.md) | Rust core, NDK, AAR, checks, Dokka, publishing |
| [C ABI](c-abi.md) | `include/cantino.h` for iOS and other bindings |
| [Café app walkthrough](cafe-app.md) | The reference app, step by step, mapped to code (Android and iOS) |
| [Inspector walkthrough](inspector-app.md) | The example for OSM developers and mappers: query bar, checks, tap-to-inspect, object navigator (Android and iOS) |
| [Android and iOS parity](platform-parity.md) | Which tests run on both platforms, known differences |
| [SliceOSM in production](sliceosm.md) | What the service is, terms, limits, data lag, self-hosting via `sliceBaseUrl` |
| [Opening hours](opening-hours.md) | Pairing raw `opening_hours` strings with an evaluator library; time zones |
| [Field findings and editing apps](editing-apps.md) | App-owned observations, reviewed handoff to OSM, versions and stale-data checks |
| [Maintenance and roadmap](roadmap.md) | Positioning, release bar, first external validation and path to stability |

Guide (HTML): <https://mvexel.github.io/cantino/guide/>.
API reference: <https://mvexel.github.io/cantino/api/>
(every public symbol has KDoc; the reference is the authority on details).
