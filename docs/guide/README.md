# Cantino guide

Cantino for Android and iOS. Start with the [quickstart](../../README.md#quickstart)
(Android) or the [Swift adapter](../../swift/README.md) (iOS), then read the
page you need.

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
| [Editing apps](editing-apps.md) | What Cantino gives an editor today: versions, snapshot freshness, detecting stale data |
| [Maintenance and roadmap](roadmap.md) | Maintainer, versioning, 0.2, what is next, path to 1.0 |

Guide (HTML): <https://mvexel.github.io/cantino/guide/>.
API reference: <https://mvexel.github.io/cantino/api/>
(every public symbol has KDoc; the reference is the authority on details).
