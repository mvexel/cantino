# TODO

Verified 2026-10-03.

Plan of record. Scope and phase definitions: `CLAUDE.md`. Verify each `[x]`
against the repo before trusting it.

## 0. Scope and tracking
- [x] `CLAUDE.md` scope file
- [x] GitHub repo `mvexel/osm-framework`, pushed

## 1. Android vertical slice
- [x] Android SDK, platform tools, x86_64 emulator image installed locally
- [x] `build-android.sh` builds x86_64 alongside arm64-v8a
- [x] JNI adapter + Kotlin API (open/close/get/query/import, strings in, JSON out)
- [x] Gradle library module packaging all three `.so` per ABI
- [x] Strip native libraries in the AAR (SDK-managed NDK; arm64 29 MB → 7.8 MB, AAR 6.3 MB)
- [x] Publishable AAR (maven-publish to android/build/repo), typed Kotlin models instead of JSON strings
- [x] Instrumented test: fixture import, get, query, close on one worker thread
- [x] Test green on x86_64 emulator
- [x] Test green on physical arm64 phone

## 2. Phone measurement (go/no-go)
- [x] Pick city extract, fetch via SliceOSM — Salt Lake City bbox (-112.10, 40.70, -111.80, 40.85), 13 MB PBF
- [x] Measure on Pixel 8 (`scripts/bench-android.sh`, results in `docs/bench/2026-10-03-slc-pixel8.json`)
- [x] Compare against thresholds: import 5.0 s ✅; peak RSS 425 MB ❌; db 338 MB = 26× ❌; tag query p95 32.6 s ❌
- [ ] **DECISION (Martijn):** keep OSMExpress + fix our layer, or revisit backend? Blocks phases 3–6.
- [ ] Proposed (awaiting GO): 2h spike, pure-Rust SQLite store (osmpbf + rusqlite) on the same SLC bench. Kill criteria → stay on OSMExpress if db > 215 MB, Pixel import > 60 s, or tag query p95 > 200 ms
- [ ] If kept: default `preserve_untagged_metadata=false` (338 → 215 MB)
- [ ] If kept: tag index table at import (additive LMDB table in fork) → tag queries in ms
- [ ] If kept: replace 3× size threshold with an absolute per-city budget; re-run bench

## 3. iOS vertical slice
- [ ] Inspect Mac (Xcode, toolchain)
- [ ] xcframework: device arm64 + simulator arm64
- [ ] Swift wrapper + XCTest on simulator (import, get, query)

## 4. Basemap
- [ ] Decision record: separate PMTiles basemap for the area bbox
- [ ] 2h spike: obtain bbox PMTiles extract, render offline in MapLibre Native

## 5. Area download lifecycle
- [ ] Android: WorkManager submit/poll/download/cancel → staged import
- [ ] iOS: URLSession background equivalent
- [ ] Kill/restart mid-download recovers; failed replace keeps old area

## 6. Café reference app
- [ ] Find nearby cafés, filter outdoor seating + opening hours (unknown stays unknown)
- [ ] Inspect full tags and underlying objects
- [ ] Airplane-mode acceptance scenario passes end to end
