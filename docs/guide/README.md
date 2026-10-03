# Cantino guide

Cantino 0.1.0, Android. Start with the [quickstart](../../README.md#quickstart),
then read the page you need.

| Page | Read it for |
| --- | --- |
| [Concepts](concepts.md) | Areas, snapshots, refresh, what "published" means |
| [Downloading areas](downloading.md) | `AreaManager`, the `AreaState` machine, cancellation, failures, foreground mode, sizes, privacy |
| [Basemaps](basemaps.md) | `BasemapSource`, hosting PMTiles, the offline style, MapLibre, attribution |
| [Querying](querying.md) | `OsmStore` threading, `Query` semantics, pagination, the object model |
| [Performance and sizes](performance.md) | Measured numbers on a Pixel 8 |
| [Building from source](building.md) | Rust core, NDK, AAR, checks, Dokka, publishing |
| [C ABI](c-abi.md) | `include/cantino.h` for iOS and other bindings |
| [Café app walkthrough](cafe-app.md) | The reference app, step by step, mapped to code |

API reference: <https://mvexel.github.io/cantino/api/>
(every public symbol has KDoc; the reference is the authority on details).
