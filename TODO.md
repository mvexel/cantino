# TODO

Plan of record. Scope and phase definitions: `CLAUDE.md`. Verify each `[x]`
against the repo before trusting it.

## 0. Scope and tracking
- [x] `CLAUDE.md` scope file
- [x] GitHub repo `mvexel/osm-framework`, pushed

## 1. Android vertical slice
- [ ] Android SDK, platform tools, x86_64 emulator image installed locally
- [ ] `build-android.sh` builds x86_64 alongside arm64-v8a
- [ ] JNI adapter + Kotlin API (open/close/get/query/import, strings in, JSON out)
- [ ] Gradle library module (AAR) packaging all three `.so` per ABI
- [ ] Instrumented test: fixture import, get, query, close on one worker thread
- [ ] Test green on x86_64 emulator
- [ ] Test green on physical arm64 phone

## 2. Phone measurement (go/no-go)
- [ ] Pick city extract, fetch via SliceOSM
- [ ] Measure: download size, db size, peak RSS, import time, query p50/p95
- [ ] Compare against thresholds in `CLAUDE.md`, record go/no-go

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
