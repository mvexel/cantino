# Editing apps: what Cantino gives you today

Cantino 0.x is read-only: there is no edit layer, no upload and no sync, and
those are out of scope until the project's scope changes. If you are building
an editor, here is what you can rely on and what you must build.

## What you get

- **Raw tags**, exactly as in OpenStreetMap, plus ordered way node lists and
  relation members with roles.
- **Per-object metadata** (`ObjectMetadata`): `version`, `timestampSeconds`,
  `changeset`, `uid`, `user` of the object version in the extract. Always
  present for tagged nodes, ways and relations. Untagged nodes have none
  unless you import with `ImportOptions(preserveUntaggedMetadata = true)`;
  they always keep `Node.locationVersion`. A zero field may mean "unknown"
  ([Querying: metadata](querying.md#metadata)).
- **A snapshot timestamp**: `AreaMetadata.snapshotTimestamp`, the replication
  time of the SliceOSM extract ([SliceOSM: data lag](sliceosm.md#data-lag)).
- **Immutable snapshots.** Queries see one consistent state; the data does not
  change under you until you refresh.
- **Helpers for field apps**: `wayCoordinates`, batch
  `get`, `TagFilter.NotExists` ("amenities without `opening_hours`").

## What you do not get

- No local pending-edits layer, no osmChange generation, no OAuth, no upload,
  no conflict handling. Build these against the
  [OSM API](https://wiki.openstreetmap.org/wiki/API_v0.6).
- No incremental updates: a refresh re-downloads the whole area.
- No guarantee that an object is current. It is current as of the snapshot.

## Detecting stale data

Check an object against the server, not the snapshot, before you upload a
change to it:

1. Keep `obj.metadata?.version` from the snapshot: the version your user
   edited on top of.
2. Fetch the current object (`GET /api/0.6/node/<id>`, or `way`, `relation`)
   and compare `version`. If it is higher, someone edited the object since the
   snapshot: show a conflict instead of uploading. The OSM API enforces the
   same thing, since an upload must name the version it modifies.
3. Use `snapshotTimestamp` to decide when to warn or force a refresh before
   editing: an area whose snapshot is older than your threshold is more likely
   to conflict. The snapshot alone cannot tell you that a given object has
   changed; only the server can.

A way vertex (untagged node) has no metadata in the default import, so moving
one cannot be version-checked from the snapshot unless you set
`preserveUntaggedMetadata`; budget the larger file.

## Where this is going

The design on file (a research note, not a promise) keeps the downloaded base
file immutable and stores user edits in a separate overlay database attached at
open, each edit recording the base version it derived from; queries return
edits over the base. See
[`docs/research/2026-10-03-prebaked-dataset-format.md`](../research/2026-10-03-prebaked-dataset-format.md).
Building it needs a scope change first; see the [roadmap](roadmap.md).
