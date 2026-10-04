# Android and iOS parity

Both adapters wrap the same Rust core and C ABI, so behaviour that matters
(import, queries, the SliceOSM protocol, the basemap engine, staging,
publication, recovery, failure classification) is implemented once. Parity
is checked two ways:

1. **The parity corpus** ([`tests/parity`](../../tests/parity/README.md)):
   243 calls whose canonical output every runner must reproduce byte for
   byte. Rust runs all of them through the C ABI; Kotlin (`ParityTest`,
   instrumented) and Swift (`ParityTests`) run the 219 that a typed API can
   express.
2. **The same behavioural tests on both platforms**, listed below. A Swift
   test has the Kotlin test's name unless noted.

Verified 2026-10-04: Android on the Pixel 8 emulator (Android 17, arm64),
iOS on the iPhone 18 Pro simulator (iOS 27) and natively on macOS. The café
acceptance flow (download, airplane-mode relaunch, force-quit during a
download and resume) passed on a physical iPhone 15 Pro Max (iOS 27).

## SDK

| Android (Kotlin) | iOS (Swift) | Notes |
| --- | --- | --- |
| `ParityTest` (2) | `ParityTests`, `CanonicalTests` | corpus byte for byte; canonical form |
| `OsmStoreTest` (10) | `OsmStoreTests` | same ten cases |
| `AsyncOsmStoreTest` (2) | `AsyncOsmStoreTests` (+ `concurrentCloseIsSafe`) | Kotlin `Dispatcher` → Swift task |
| `BboxAroundTest` (6, JVM) | `BboxAroundTests` | same six cases |
| `ImportProfileTest` (4) | `ImportProfileTests` (+ wire form), `profileSurvivesTheStoredRequest` | WorkManager input data → stored `request.json` |
| `AreaStorageTest` (2) | `AreaStorageTests` | thin wrappers; the commit protocol itself is tested in the core (`src/area_storage/tests.rs`) |
| `AreaManagerTest` (14 shared) | `AreaManagerTests` | same scenarios against a fake SliceOSM (MockWebServer / URLProtocol) |
| `AreaManagerTest.snapshotTimestamp…` | `snapshotTimestampParsesRfc3339AndToleratesGarbage` | |
| `ProcessDeathTest` + `scripts/kill-test-android.sh` | `aRunKilledWhileDownloadingIsResumedWithItsJob`, `aRunKilledAfterItsCommitPointSucceedsWithoutDownloading`, `aCancelledRunIsNotResumed` | Android kills the process; Swift recreates a killed process's files (downloads run in the app's process, resumed by the next `AreaManager`) |
| — | `downloadsReportProgressWhileTransferring`, `userAgentVersionMatchesTheCrate`, `invalidAreaIdsAndBasemapSourcesAreRejected` | Swift-only checks of Swift-only code |
| `ModelOwnershipTest` (JVM) | — | Kotlin collections; Swift models are value types |
| `AreaManagerTest`: two `foregroundMode…` tests, `manifestCheckFindsTheOptInForegroundEntries` | — | Android foreground services; no iOS equivalent |
| `liveDownload…` (2), `CityBenchmark` | — | opt-in (live service, benchmark); not ported |

## Café app

| Android | iOS | Notes |
| --- | --- | --- |
| `OpeningHoursTest` (14, JVM) | `OpeningHoursTests` (+ `localTimeFromADate`) | same cases |
| `ProtomapsBuildsTest` (2, JVM) | `ProtomapsBuildsTests` | |
| `RelationCafeTest`, `CafeStoreTest` | `CafeTests` (+ paging, filters, location input, debug location) | |

## Running both

```sh
scripts/check.sh                                   # Rust, C ABI smoke, Rust parity runner
SIMULATOR="iPhone 18 Pro" scripts/swift-test.sh    # Swift SDK on the simulator (omit SIMULATOR: macOS)
SIMULATOR="iPhone 18 Pro" scripts/build-ios-cafe.sh test
scripts/build-android.sh
cd android && ANDROID_SERIAL=<device> mise exec -- ./gradlew \
    :cantino:testDebugUnitTest :cafe-app:testDebugUnitTest \
    :cantino:connectedDebugAndroidTest :cafe-app:connectedDebugAndroidTest
ANDROID_SERIAL=<device> scripts/kill-test-android.sh   # from the repository root
```

## Known differences

Listed with reasons in the [Swift adapter README](../../swift/README.md#deviations-from-the-kotlin-api).
The ones that change behaviour:

- **Background work.** Android downloads run in WorkManager and survive the
  app being killed; iOS downloads run in the app's process and resume the
  next time the app creates an `AreaManager`.
- **Final states** are kept by WorkManager for about a day; on iOS they
  live in memory, so after a restart a finished run reads as idle with the
  published area.
- **Foreground mode** exists on Android only.
