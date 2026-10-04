# Maintenance and roadmap

Status as of 2026-10-03 (0.3.0). "Planned" means intended, not promised; there are no
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

## Released

- **0.2.0** (2026-10-03): `AsyncOsmStore`, `Bbox.around`, armeabi-v7a, way
  coordinates, representative points and batch `get`, `TagFilter.NotExists`,
  run identity in `AreaState`, typed errors, and the `lol.osm.cantino`
  package. Details in the [CHANGELOG](../../CHANGELOG.md).
- **0.3.0**: import profiles (keep only the objects your app needs; a POI
  area is about 15× smaller), size guidance for large areas, and tests that
  kill the process mid-download and mid-commit.

## Next: iOS, and nothing else

**Android feature work is paused until the iOS adapter is built.** Until
then releases carry only bug fixes and documentation. iOS means: an
xcframework and Swift adapter over the same Rust core and C ABI, area
downloads on iOS, and the café app on an iPhone, with both platforms passing
the same tests. It needs a Mac, so there is no date.

Considered for after iOS, each needing scoping (and some a scope change):
offline editing overlay and upload, offline routing, several files per
area, route relation assembly, polygon areas, a pre-download size estimate.
The full list is in [`TODO.md`](../../TODO.md).

## Path to 1.0

1.0 means the Kotlin API is stable and breaking changes wait for 2.0. Planned
conditions, none promised with a date:

- The API settled after 0.2, with at least one further release that breaks
  nothing.
- Distribution beyond the GitHub Pages Maven repository: Maven Central with
  signed artifacts, and a final group ID decision.
- An iOS adapter released, so the C ABI and model are exercised on two
  platforms.
- Download reliability and import memory: done (process-death tests; large
  areas measured, see [Performance](performance.md)).

## Not planned

Anything out of scope in `CLAUDE.md`: on-device vector tile generation,
country-scale operation, incremental `.osc` refresh, and opening hours or
café policy in the core.
