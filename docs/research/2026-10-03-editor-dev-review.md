# Cantino 0.1.0 through the eyes of an opening-hours-editor developer

*Persona review, 2026-10-03. Sources: the public site (https://mvexel.github.io/cantino/), its Dokka API reference, and the public GitHub README, guide and CHANGELOG. No local checkout or memory used.*

**Bottom line:** Cantino solves half of my problem well: finding and reading POIs offline. It does nothing for the other half, which is editing and uploading. That's stated openly ("0.1.0 is read-only", "`opening_hours` … raw strings; their meaning is your app's business"). Within its scope, it's the most carefully documented 0.1.0 SDK I've seen. Before I commit, I need to know where the project is going and who stands behind it.

## 1. Missing features (for my use case)

| Gap | Why it hurts an OH editor |
|---|---|
| **No write path.** No edits, no local pending-changes layer, no changeset or upload, no OAuth | It's the core of my app. I'd build all of it myself against the OSM API. |
| **No negative or OR tag filters** (only `Exists` and `Equals`, ANDed) | My main query is "shops/amenities *without* `opening_hours` near me". Today that means running several `Exists` queries and filtering in Kotlin. |
| **No nearest-N or distance ordering.** Results are ordered by kind, then ID | A field app's list is "closest first". I'd page through the whole bbox and sort it myself. |
| **No representative point for ways/relations** | Many shops are mapped as building ways. The café app averages the node coordinates, and every user will copy that code. |
| **No tag-filtered import** | About 75 MB per 10×10 km of city, and I only need POIs. That's heavy for a field app. |
| **Snapshot only, no freshness check** | Before uploading I need the current version. `ObjectMetadata.version` exists, but SliceOSM's lag isn't documented. |
| **Distribution** | Not on Maven Central, no `.asc` signatures (checked: 0 in the 0.1.0 directory), personal `io.github.mvexel` namespace. Corporate dependency policies will flag all three. |
| **ABIs: arm64-v8a and x86_64 only** | No armeabi-v7a, so the cheap devices field mappers often carry are excluded. |
| **No iOS yet** | The C ABI is "ready", but I can't ship cross-platform today. |

I would **not** ask Cantino to parse `opening_hours`. Existing libraries already do that (e.g. westnordost's osm-opening-hours, Simon Poole's OpeningHoursParser). A doc page on pairing one with Cantino would be enough.

## 2. Developer experience and docs

### Strong

- Every public symbol has KDoc, and the guarantees are written as contracts: atomic publish, snapshot semantics for open stores, thread confinement, "never silently truncated", and the difference between a missing tag and an empty value.
- It says "candidates" honestly and never claims exact intersection. Edge-clipping behaviour is spelled out.
- The performance and size numbers come with device, date and raw bench records. That's rare and builds trust.
- The quickstart is a complete Activity. The state machine is a Mermaid diagram, and an exhaustive `when` example is included.
- The privacy section (the bbox is sent to OSM US), the attribution rules and the manifest/permission table are all things I'd otherwise discover in Play review.
- The compatibility policy (returned types aren't data classes, `copy()` isn't covered before 1.0) is mature thinking.

### Weak

- **The docs are split.** The Pages site is a thin landing page, and the guide lives in a GitHub tree. The guide says "the reference is the authority", but:
- **The Dokka reference drops non-literal defaults.** `Query(…, maxCandidates: Int)` looks like a required parameter; only the guide says the default is 100 000. `AreaConfig`'s `pollIntervalMillis`, the timeouts and `backoffDelayMillis` render the same way.
- **The OsmStore thread confinement is a coroutine footgun.** It's well explained, but every app will write the same single-thread-dispatcher wrapper. A suspend wrapper should ship in the library.
- **`state()` follows the area, not the run.** The quickstart's `dropWhile`/`first` sequence to wait for *your* download is awkward and easy to get wrong.
- **Testability isn't covered.** `OsmStore` is a final class with no interface, so I can't fake it. It's also native-only, so I don't know whether JVM unit tests or Robolectric are possible.
- **One exception type with a string message.** There are no error codes to branch on.
- **Samples use plain Views.** There's no Compose example, and building the samples needs `mise` plus scripts.
- **Project signals:** 0 stars, no repo description or topics, Discussions off, released today. That's fine for a 0.1.0, but it's a bus-factor question for anyone adopting it.

## 3. Features I'd want next (ranked for my app)

1. `TagFilter.NotExists` plus key-OR (or `ExistsAny(keys)`), giving "POIs missing `opening_hours`" in one indexed query.
2. Distance-ordered or nearest-N query around a point, with a core-computed representative point for ways and relations.
3. Import profiles or tag filters ("POIs only") to cut disk usage by a large factor.
4. A suspend-friendly store API plus an interface or fake for tests.
5. Maven Central with signing, and armeabi-v7a.
6. *(Scope change, I know)* A local edit overlay that exports osmChange. I'd handle the upload myself, but the "my edits on top of the snapshot" layer is generic.
7. Docs: render defaults in Dokka, move the guide onto Pages, add a Compose sample and an "editing apps: what Cantino does and doesn't give you" page.

## 4. Questions for the maintainer

1. Are edits or upload ever on the roadmap, or permanently out? If out, can I rely on `version`/`changeset` from a SliceOSM snapshot for conflict detection when I upload via the API?
2. SliceOSM: what are the production terms, rate limits, typical replication lag and uptime expectations from OSM US? Is self-hosting via `sliceBaseUrl` a supported path with docs?
3. Can an app hold several non-overlapping areas (different IDs)? What's the recommended pattern when the user moves out of the area?
4. Is there a plan for Maven Central, signing, a non-personal group ID, and armeabi-v7a?
5. How do I test against Cantino? Is there a host-side native lib for JVM tests, or is it instrumented tests only?
6. When is iOS coming? Is Kotlin Multiplatform bindings over the C ABI something you'd accept?
7. Who maintains this, and what's the path and timeline to 1.0? How many breaking minors should I expect?
8. Is `Query.maxCandidates` really defaulted at 100 000? The API reference shows it as required.
