# Maintenance and roadmap

Status as of 2026-10-03. "Planned" means intended, not promised; there are no
committed dates.

## Who maintains Cantino

Cantino is maintained by [Martijn van Exel](https://github.com/mvexel), a
single maintainer. No organization or funding stands behind it today. The code
is Apache-2.0, so you can fork it, and the build is documented and pinned
([Building from source](building.md)). Bugs and requests go to GitHub issues on
[mvexel/cantino](https://github.com/mvexel/cantino).

## Versioning

[Semantic versioning](https://semver.org/). **Before 1.0, a minor version may
break the API.** The [CHANGELOG](../../CHANGELOG.md) lists every break under
"Breaking". Patch versions do not break. Expect breaking minors until 1.0 and
pin the exact version in your build.

## 0.2 (in progress)

From the CHANGELOG: `AsyncOsmStore` (coroutine wrapper that owns the store
thread), `Bbox.around`, armeabi-v7a, way coordinates, representative point and
batch `get`, `TagFilter.NotExists`, run identity in `AreaState` (`runId`,
`isTerminal`) with suspend file loaders, coroutines as an `api` dependency,
and these docs. Breaking: `AreaState.Submitting`, `Importing` and `Cancelled`
now carry a `runId`. Planned for the same release: typed errors (breaking) and
possibly a rename of the package and Maven group to `lol.osm.cantino`, which
would break imports once.

## Next (planned, in this order)

1. **Download reliability**: a test that kills the process mid-download and
   mid-commit and checks that the old area stays intact; a decision on
   byte-range resume.
2. **Import performance and memory**: peak memory bounded as areas grow, and
   an explanation for the in-app versus plain-binary import time gap.
3. **Café app fixes**: opening hours in the area's time zone.
4. **iOS**: the Rust core and C ABI are ready; the xcframework and Swift
   adapter wait on Mac access.
5. **Offline routing**: research first; no code is committed.

Considered, each needing scoping (and some a scope change) before work:
offline editing overlay and upload, tag-filtered import, large and rural
areas, several files per area, route relation assembly, polygon areas. The
full list is in [`TODO.md`](../../TODO.md).

## Path to 1.0

1.0 means the Kotlin API is stable and breaking changes wait for 2.0. Planned
conditions, none promised with a date:

- The API settled after 0.2, with at least one further release that breaks
  nothing.
- Distribution beyond the GitHub Pages Maven repository: Maven Central with
  signed artifacts, and a final group ID decision.
- An iOS adapter released, so the C ABI and model are exercised on two
  platforms.
- Evidence for download reliability and import memory (the first two items
  above).

## Not planned

Anything out of scope in `CLAUDE.md`: on-device vector tile generation,
country-scale operation, incremental `.osc` refresh, and opening hours or
café policy in the core.
