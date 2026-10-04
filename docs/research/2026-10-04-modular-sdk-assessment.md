# Cantino as a modular SDK: assessment

*Research note, 2026-10-04. Not a commitment and not a backlog item. It
assesses a proposal to restructure Cantino as a core plus optional modules,
editing and upload among them, against the 0.3.0 code on `ios/main`
(`f9c8e35`).*

**Verdict:** Adopt the vocabulary and the "editing is outside the current
implementation" framing. Don't build the module structure yet. The code
already has most of the seams that matter, and packaging them as separate
artifacts now would multiply surface across Rust, the C ABI, Kotlin and Swift
before a second consumer exists. Editing is a separate, large project. It
needs its own design and its own maintenance commitment, and it fits Cantino
only if the core's read path is reworked for it first.

## 1. What the code already separates

| Proposed module | State in 0.3.0 | Evidence |
|---|---|---|
| Core (objects, snapshot, storage) | Exists | `model.rs`, `store.rs`, `schema.rs`, `encoding.rs`. Read-only, immutable file. |
| Feature queries | Part of core, not separable | `Store::query`, `spatial_candidates` and `dependencies` are methods on the store and read `tag_index`/`geo` directly. Splitting queries from storage would add an interface with exactly one implementation. |
| Area acquisition | Separate in Rust, bundled in adapters | `area_storage.rs` and `slice.rs` import nothing from `store`. `mobile_area.rs` is its own ABI group (`cantino_area_*`, `cantino_slice_*`). Downloads live only in the adapters (WorkManager, URLSession). |
| Basemap | Separate engine, coupled lifecycle | `basemap/` and `mobile_basemap.rs` use only `Error`/`Result` from the crate. But `BasemapSource` is a parameter of `AreaManager.download`, and an area is `Ready` only when OSM data *and* basemap are published. A basemap-only app still downloads and imports OSM data. |
| Renderer integration | Docs only | The SDK hands MapLibre a `pmtiles://` URL. There is no code to modularise. |
| Editing, upload | Absent, out of scope | `docs/guide/editing-apps.md`, overlay sketch in `2026-10-03-prebaked-dataset-format.md`. |

So the proposal's separations are mostly real already, at the level that
costs nothing: Rust modules and C ABI symbol groups. The one concrete gap it
points to is **basemap-only use**, and that is a lifecycle question in
`AreaManager`, not a packaging question.

## 2. Where the proposal is right

- **The mission framing.** "Composable capabilities for OSM mobile apps" is
  an honest description of the founding goal. "Editing is outside the
  current implementation" is more accurate than "a permanent boundary". The
  editor-developer persona review (2026-10-03) shows the demand.
- **"Plugin" means a build-time dependency.** No dynamic loading and no
  registry. That is the only version a two-adapter project can afford.
- **The test for a module:** it must remove substantial repeated work across
  apps. That rules out a renderer module and probably a geometry module.
- **Edits must be inside the query, not overlaid after it.** This is the
  correct and important technical point. An edit that retags or moves an
  object changes whether it matches. Post-filtering base results misses
  objects that edits move into a result and keeps ones they move out.

## 3. Where it overreaches or is premature

**Every module costs at least three surfaces.** A capability means Rust code,
C ABI symbols and docs, a Kotlin API with KDoc and JNI, and a Swift API with
tests. Splitting a capability into its own artifact adds per-platform
packaging on top: a Gradle module, a SwiftPM product, possibly a separate
native library, and ABI version checks between them. One maintainer just
removed machinery in 0.3.0. Seven logical modules would be a reversal.

**There is no second consumer.** The café app and the sample app are the only
clients. Interfaces designed now, such as a "shared read interface" or a
module boundary for queries, would be shaped by guesses. The existing rule
"no features without a plan" applies to structure too.

**Binary size is not a forcing function yet.** The Rust core is one `cdylib`
/ `staticlib`. The basemap engine is small and adds no dependency beyond
`flate2`, which `osmpbf` already pulls in. Removing it would barely shrink
the library. A basemap-only app would carry SQLite and the PBF decoder, which
a Cargo feature could drop later. Nobody has asked for that yet.

**Editing is not "a module on top of" the current core.** The proposal
underestimates how deep it reaches.

- *IDs.* `checked_id` rejects `id <= 0` ("snapshot IDs must be positive").
  `schema::packed` assumes positive IDs. Query cursors are `(kind, id)`
  keyset pagination. New objects need negative placeholder IDs that are
  remapped after upload, which touches the R-tree key, the cursor order and
  every adapter's ID type.
- *Query engine.* `Store::query` picks one driver (a `tag_index` key or the
  `geo` R-tree), then post-checks. `tag_index.k` is a dictionary ID from the
  base file's `dict`. An edits database would need its own tag index and
  R-tree, keyed by string or by a shared dictionary. Every query would then
  run against both sources and merge, with tombstones and with shadowing by
  `(kind, id)`, while keeping the "never silently truncated" limit and the
  cursor contracts. This is a rewrite of the read path, not an addition.
- *Dependencies.* Moving a node changes the bounds of every way and relation
  that uses it. Effective `geo` entries must be recomputed for the parents.
  Way vertices are not in `geo` at all.
- *Refresh.* Refresh is a full replace. Pending edits must survive it and be
  rebased: an edit is in conflict when the base `version` exceeds the edit's
  base version. Untagged vertices carry no metadata unless the area was
  imported with `preserveUntaggedMetadata`.
- *Upload.* This needs changeset open/upload/close, osmChange generation with
  placeholder-ID remapping, partial-failure recovery, and conflict
  presentation (an app-level UI decision the SDK cannot make). It needs
  OAuth 2.0 PKCE on two platforms: custom-tab or `ASWebAuthenticationSession`
  flows, token storage in Keystore and Keychain, and app registration on
  osm.org. It needs compliance with the OSM API usage policy and the
  Contributor Terms. An SDK that makes mass upload easy invites
  automated-edit problems, so Cantino would have to ship guardrails and
  documentation for them.
- *Data-source mismatch.* SliceOSM extracts lag the live database
  (`sliceosm.md`), so an editor built on them conflicts more often than one
  built on API `map` calls.

Realistically, editing alone is larger than everything in 0.3.0, on both
platforms.

## 4. Risks of adopting it now

1. It breaks the feature freeze in spirit. The freeze exists to get iOS to
   parity, and a restructure would compete with that.
2. Speculative interfaces would harden into the API that the iOS work is
   currently reconciling.
3. A promise in the README ("editing module planned") creates expectations
   with no commitment behind them.
4. A plugin-shaped project looks open to third-party modules, which is
   support load with no owner.

## 5. Incremental path (smallest structure that keeps the option)

**Phase 0: words only. Allowed during the freeze.**
Deliverables: this note; the CLAUDE.md scope wording below; a short
"Capabilities" section in `docs/guide/concepts.md` that names the existing
layers (store and queries, area acquisition, basemap) and maps them to Rust
modules and ABI symbol groups. README: "editing is outside the current
implementation". Exit: Martijn approves the wording. No code changes.

**Phase 1: finish the freeze.** iOS/Android parity and the café demo on iOS,
as already planned. Keep the existing seams intact while porting:
`area_storage`/`slice` must not import `store`, and `basemap` must import
nothing from the area side. Add a one-line comment in `lib.rs` stating the
rule. Exit: the freeze exit criteria.

**Phase 2: the first real seam, only if a consumer asks.** Make the basemap
usable without OSM data, either as a standalone
`BasemapManager.download(bbox, source)` or as `AreaManager.download` with
`osmData = false`. This is a lifecycle change in two adapters. Packaging
stays the same: one AAR, one SwiftPM product. Optionally gate
`import`/`store` behind a default-on Cargo feature (`osm-data`) so a
basemap-only build drops SQLite and PBF. Do this only if someone measures a
size problem. Exit: the café-style test passes, plus a basemap-only sample
on both platforms.

**Phase 3: packaging split, only when sizes or consumers demand it.** Map
modules to artifacts:

| Layer | Rust | C ABI | Android | iOS |
|---|---|---|---|---|
| Core + queries + acquisition | default features | `cantino_*`, `cantino_area_*` | `:cantino` AAR | `Cantino` product |
| Basemap | `basemap` feature | `cantino_basemap_*` | `:cantino-basemap` (depends on `:cantino`) | `CantinoBasemap` product |
| Editing (future) | `edit` feature or `cantino-edit` crate | `cantino_edit_*` | `:cantino-edit` | `CantinoEdit` product |

Keep one repo, one version (`Cargo.toml` already drives the AAR version), one
native library per platform built with the selected features, and shared CI.
There would be no separate native libraries per module: two Rust staticlibs
in one iOS app would duplicate `std` and SQLite.

**Phase 4: editing, a separate scope decision.** It starts with a design
note, not code. The note must cover: effective-view query semantics (a merged
driver over base and `edits.db`, tombstones, cursors); the ID scheme for new
objects; parent-bounds recomputation; rebase on refresh; the upload journal
and recovery; OAuth on both platforms; OSM API policy and guardrails; and
whether upload is a separate module from local editing. Its prerequisite is
a core read path that works against "base plus overlay". That is the one
piece of the proposal that must live in the core, and it is the format bump
to plan for. Exit to build: Martijn commits maintenance time, and at least
one real editing app (internal or external) is the design partner.

## 6. Decisions for Martijn

1. Accept the mission wording, with editing "outside the current
   implementation" rather than permanently out of scope?
2. Is basemap-only use a goal worth Phase 2 after the freeze, or is
   "OSM data is always present" a simplifying invariant to keep?
3. Is one artifact per platform the default until a measured size or
   consumer need appears? Recommended: yes.
4. Is editing something you would personally maintain (OAuth, API policy,
   conflict support), or should it remain a documented integration path
   (`editing-apps.md`) for app developers?
5. If editing ever happens: is upload part of it, or does the SDK stop at a
   local edit journal plus osmChange export?

## 7. Proposed CLAUDE.md wording (not applied)

Replace the first paragraph of "Out of scope" and add one line to the intro:

> Mission: composable, supported capabilities for apps that work with OSM
> data offline. The current implementation is snapshots, queries, area
> acquisition and optional basemaps, shipped as one artifact per platform.
> Modules are build-time options inside this repo (Cargo features, Gradle
> modules, SwiftPM products), never dynamic plugins. A module is added only
> when it removes substantial repeated work across apps and has a consumer.

> ## Outside the current implementation (needs a scope change and a design note)
>
> - Edits, upload, sync, conflict handling. A possible future module; it
>   requires an effective-view read path in the core first.

Keep the remaining out-of-scope items as they are.

## 8. Decisions (Martijn, 2026-10-04)

1. Mission wording accepted; editing is "outside the current
   implementation", not a permanent boundary.
2. Basemap-only use is a goal.
3. One artifact per platform until a measured need appears.
4. Leaning no on maintaining editing inside Cantino: it stays a documented
   integration path for now.
5. No upload.

Phases accepted. P0 applied (CLAUDE.md, concepts guide, README wording).
P1 is in progress. The freeze is lifted once iOS/Android parity holds, the
café app works on iOS and 0.3.0 is released; P2 (basemap-only acquisition)
follows as the first post-freeze work.
