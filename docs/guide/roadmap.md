# Maintenance and roadmap

Cantino is maintained by [Martijn van Exel](https://github.com/mvexel).
The code is Apache-2.0; [builds are documented](building.md). Bugs and requests
belong in [GitHub issues](https://github.com/mvexel/cantino/issues).

There are no external consumers yet. API, ABI and file formats may change
without compatibility layers. Re-import development data after a format change.

0.3.0 simplified the SDK; 0.4.0 brings iOS to parity with Android (Swift
adapter, xcframework, area downloads, the café demo), checked by the
[parity tests](platform-parity.md). Features land on both platforms with
tests on both. The examples and guide stay supported throughout.

Unreleased: basemap-only acquisition (`downloadBasemap`). Cantino's direction is composable capabilities for apps that work
with OSM data offline ([assessment](../research/2026-10-04-modular-sdk-assessment.md)).
Editing is outside the current implementation and upload is not planned;
routing, overlapping areas, incremental refresh and on-device vector tile
generation are out of scope. Dated research notes are ideas, not a backlog.
