# Maintenance and roadmap

Cantino is maintained by [Martijn van Exel](https://github.com/mvexel).
The code is Apache-2.0; [builds are documented](building.md). Bugs and requests
belong in [GitHub issues](https://github.com/mvexel/cantino/issues).

There are no external consumers yet. API, ABI and file formats may change
without compatibility layers. Re-import development data after a format change.

The 0.3.0 simplification is complete. Next is iOS: xcframework, Swift adapter, area downloads and the café demo,
validated on macOS and against the same behavior as Android. No new features
until that work is complete. The examples and guide stay supported throughout.

Editing, routing, overlapping areas, incremental refresh and on-device vector
tile generation are out of scope. Dated research notes are ideas, not a backlog.
