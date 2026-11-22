# Field findings and editing apps

Cantino is read-only. It gives a field app immutable base objects and versions;
the app owns observations, review and any eventual OSM contribution. Cantino
does not provide a mutable object graph, OAuth, upload, changesets or conflict
handling.

This boundary supports two useful products:

- An agency audit whose final deliverable is a private CSV, GeoJSON or backend.
- An OSM field campaign whose reviewed findings become ordinary OSM
  changesets through an established editor.

## What the snapshot provides

- **Raw tags**, ordered way node lists, and relation members with roles.
- **Per-object metadata** (`ObjectMetadata`): `version`, `timestampSeconds`,
  `changeset`, `uid`, `user`. Tagged nodes, ways and relations have it;
  untagged nodes require
  `ImportOptions(preserveUntaggedMetadata = true)`.
- **A snapshot timestamp**: `AreaMetadata.snapshotTimestamp`, the SliceOSM
  replication time ([SliceOSM: data lag](sliceosm.md#data-lag)).
- **Immutable reads:** the base does not change under an audit session.
- **Field helpers:** `wayCoordinates`, batch `get`, and `TagFilter.NotExists`
  for checks such as amenities without `opening_hours` or kerbs without
  `kerb`.

## Keep findings in an app-owned overlay

Do not modify the Cantino SQLite file. Store each observation separately with
enough provenance to review it later:

```json
{
  "osm": {"type": "node", "id": 123, "base_version": 7},
  "observed_at": "2026-11-22T14:30:00Z",
  "proposed_tags": {
    "wheelchair": "yes",
    "toilets:wheelchair": "no"
  },
  "note": "Level entrance; toilet door too narrow",
  "status": "unreviewed"
}
```

The app may add photos, forms and private workflow state. Those are not OSM
object tags and need their own storage, privacy and licensing decisions.

## Recommended OSM handoff

The first contribution workflow should be review-first:

1. Collect structured findings offline.
2. On reconnect, export them or synchronize them to a review queue such as
   [MapRoulette](https://maproulette.org/).
3. Show the base tags, observation, proposed tag patch and evidence.
4. Let a reviewer open the object in iD or JOSM and upload a normal,
   attributed changeset.

GeoJSON plus CSV is a practical pilot export. OSM Notes are suitable for
occasional ambiguous observations, not a bulk structured audit: notes are
public, unstructured and do not express a reviewed tag patch.

This path gives the project human review, established OAuth and changeset
handling, normal conflict tools and an auditable contribution history without
turning Cantino into another editor.

## If an app uploads directly

Direct upload is outside Cantino and should initially be limited to explicit,
per-object tag edits:

1. Keep `obj.metadata?.version` with the finding.
2. Before upload, fetch the current object from the OSM API.
3. If its version differs, require conflict review.
4. Apply only the reviewed tag patch; never replace the complete current tag
   set with the older snapshot's tags.
5. Upload through an attributed changeset using OSM OAuth.

New objects, geometry edits, deletions and unattended batches require
substantially more machinery and policy guardrails. They are not an
incremental extension of the workflow above.

The snapshot age helps decide when to warn or require refresh, but it cannot
prove an individual object is current. Only the live OSM API can do that.

A way vertex has no object metadata in the default import. Moving one cannot
be version-checked from the snapshot unless untagged metadata was preserved;
budget the larger area file if an editing app needs that.
