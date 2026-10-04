# Querying

```kotlin
OsmStore.open(area.dataFile).use { store ->                       // on a worker thread
    val cafe = store.get(OsmId(OsmKind.NODE, 6611023942))          // OsmObject? (null = not in area)
    val inBox = store.query(
        Query(
            tags = listOf(TagFilter.Equals("amenity", "cafe"), TagFilter.Exists("outdoor_seating")),
            bbox = Bbox(west = -111.90, south = 40.755, east = -111.88, north = 40.770),
            limit = 500,
        ),
    )
}
```

## Threading rule

**An `OsmStore` belongs to the thread that opened it.** Every call
(`get`, `query`, `wayCoordinates`, `representativePoint`, `close`) must come from that thread; any other thread gets a
`CantinoException.WrongThread`, never corrupted state. Calls are synchronous.

| Pattern | Code |
| --- | --- |
| One-shot (open, query, close) | `withContext(Dispatchers.IO) { OsmStore.open(f).use { … } }`: a block without suspension points runs on one thread |
| Long-lived store (recommended for apps) | A single-thread dispatcher that owns the store: `Executors.newSingleThreadExecutor().asCoroutineDispatcher()`, and every call goes through `withContext(thatDispatcher)` |

Do not hold a store across suspension points on `Dispatchers.IO`: the
coroutine can resume on another thread. `open` loads dictionaries, so keep a
long-lived store rather than opening one per query. Returned objects are
plain immutable values: they outlive the store and can go to any thread.

The café app's [`StoreWorker`](../../android/cafe-app/src/main/java/io/github/mvexel/cantino/cafe/StoreWorker.kt)
is a complete long-lived pattern, including reopening after a refresh.

## Query semantics

A `Query` ANDs every tag filter and, if `bbox` is set, the spatial filter.
No filters at all pages through every object.

| Part | Behavior |
| --- | --- |
| `TagFilter.Equals(k, v)` | Tag `k` has exactly value `v`. Raw strings: case-sensitive, no trimming |
| `TagFilter.Exists(k)` | Tag `k` is present, any value (an empty value counts) |
| `TagFilter.NotExists(k)` | Tag `k` is absent. **Never drives**: it is checked on the candidates of another filter. Needs an `Exists`/`Equals` filter or a `bbox` next to it; alone it throws `CantinoException.InvalidArgument` (it would otherwise scan the whole area) |
| Several tag filters | AND. Index-driven from the most selective filter; never a full scan |
| `bbox` on **tagged nodes** | Exact: the point is inside the box |
| `bbox` on **ways and relations** | **Candidates**: their bounding box intersects the box. A way crossing the box with no node inside is included; one that merely bends around the box may be too |
| `bbox` on **untagged nodes** | **Never returned**: way vertices are not spatially indexed. Reach them through their ways (`store.wayCoordinates(wayId)` for coordinates, or `store.get(way.nodeIds.map { OsmId(OsmKind.NODE, it) })` for the node objects) |
| Objects at the area's edge | Bounds cover only members present in the extract, so clipped objects get smaller boxes and can be missed near the edge |
| `maxCandidates` (100 000) | A bbox that selects more spatial candidates throws `CantinoException.InvalidArgument`; results are never silently truncated. Narrow the box or add a tag filter |
| `limit` | 1..10 000 per call (default 100) |

There is no "OR" and no value pattern matching: run several queries and merge,
or filter the returned tags in Kotlin.

## Pagination

Results are ordered nodes → ways → relations, then by ascending ID. Pass the
last result's ID as `after` to get the next page; a page shorter than `limit`
is the last one.

```kotlin
fun OsmStore.all(base: Query): List<OsmObject> {
    val out = mutableListOf<OsmObject>()
    var after: OsmId? = null
    while (true) {
        val page = query(base.copy(after = after))
        out += page
        if (page.size < base.limit) return out
        after = page.last().id
    }
}
```

## The object model

```text
OsmObject (sealed)            id: OsmId(kind, id)   tags: Map<String, String>   metadata: ObjectMetadata?
 ├─ Node      latE7, lonE7 (Int, 1e-7 degrees)  lat, lon (Double)  locationVersion
 ├─ Way       nodeIds: List<Long>          ordered; repeats significant (closed way repeats its first node)
 └─ Relation  members: List<Member>        ordered; Member(id: OsmId, role: String), role may be ""
```

- **IDs** are unique only within a kind: node 42 and way 42 are different
  objects. Always use `OsmId(kind, id)`.
- **Tags** are exactly as in OpenStreetMap: no normalization, no
  interpretation. A missing key and an empty value are different.
- **References may dangle.** A way's node or a relation's member outside the
  area (or never in the extract) returns null from `get`. Handle it.
- **Coordinates** are stored as integers in 1e-7 degrees; `lat`/`lon` convert
  to `Double`. Compare and store `latE7`/`lonE7` if you need exact values.
- **Geometry** is not assembled: a way is a list of node IDs; a multipolygon
  is a relation. The [geometry helpers](#geometry-helpers) give you a way's
  coordinates and one anchor point per object; build anything else yourself.

## Geometry helpers

```kotlin
val line: List<Coordinate?>? = store.wayCoordinates(wayId)        // null = way not in area
val anchor: Coordinate? = store.representativePoint(cafe.id)      // null = nothing in area
val objects: List<OsmObject?> = store.get(listOf(id1, id2, id3))  // input order, null = missing
```

| Call | Returns |
| --- | --- |
| `wayCoordinates(wayId)` | The way's node coordinates in order, repeats kept (a closed way ends where it starts), one native call. A **null entry** is a node outside the area: do not draw across it. Null for a way not in the area |
| `representativePoint(id)` | One point for a marker, a label or a distance origin (see below). Null when the object, or all of its geometry, is outside the area |
| `get(ids)` | One entry per ID in input order, null where the object is not in the area. One JNI crossing for the list; at most `OsmStore.MAX_BATCH` (10 000) IDs, more throws `CantinoException.InvalidArgument` |

`Coordinate` holds `latE7`/`lonE7` (exact integers) and `lat`/`lon` in degrees.

**The representative point is an anchor, not a guaranteed point-on-surface or
a true centroid.** For an L-shaped building it can lie outside the outline;
for a multipolygon it can fall in a hole. It is computed in the core as:

| Object | Point |
| --- | --- |
| Node | Its coordinate |
| Closed way (first node = last node) | Mean of its distinct vertices that are in the area |
| Open way | The point at half the length of the line through its in-area nodes (nodes outside the area are skipped, so the line joins across the gap) |
| Relation | Mean of the representative points of its distinct in-area members, each weighted equally; member relations followed up to 8 levels deep, cycles skipped |

### Metadata

`ObjectMetadata` = `version`, `timestampSeconds`, `changeset`, `uid`, `user`
of the object version in the extract.

| Object | `metadata` |
| --- | --- |
| Tagged nodes, all ways and relations | Always present |
| Untagged nodes (way vertices), default import | **null: "metadata not stored"**. Only `Node.locationVersion` (the version at which the node got its current position) is kept. This saves a lot of space: most nodes are untagged |
| Untagged nodes with `ImportOptions(preserveUntaggedMetadata = true)` | Present (larger file) |

For downloaded areas set it through `AreaConfig(importOptions = ImportOptions(preserveUntaggedMetadata = true))`.
Absent source fields are stored as zero/empty, so a zero may mean "unknown".

## Errors

Native failures are subtypes of the sealed `CantinoException`; catch the one
you can act on, or `CantinoException` for all. The store stays usable after
any of them.

| Exception | When |
| --- | --- |
| `CantinoException.InvalidArgument` | Invalid query (limit outside 1..10 000, invalid bbox, only `NotExists` filters and no bbox), more spatial candidates than `maxCandidates`, a batch `get` over 10 000 IDs, a non-positive ID |
| `CantinoException.InvalidFile` | `open` of a file that is not an area database or of an incompatible format version (re-import / re-download); `importArea` of a corrupt, truncated or non-snapshot input; `PmtilesInfo.read` of a file that is not PMTiles v3 |
| `CantinoException.Io` | A missing file (`open` of a path that does not exist, a missing import input), permissions, a full disk, read/write errors |
| `CantinoException.WrongThread` | A call from a thread other than the store's owner |
| `IllegalStateException` | Store already closed; also an internal error of the core (a bug, message "Cantino internal error ...": report it) |

```kotlin
val store = try {
    OsmStore.open(file)
} catch (e: CantinoException.InvalidFile) {
    redownload()   // not an area, or an old format version
    return
} catch (e: CantinoException.Io) {
    showError("cannot read offline data: ${e.message}")
    return
}
```

The category comes from the native core (`cantino_last_error_code`, see
[C ABI](c-abi.md#errors)); the subtype is never inferred from the message.
