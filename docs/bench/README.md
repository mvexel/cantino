# Benchmark evidence

The dated JSON files record historical device and desktop measurements. They
are evidence for the SQLite decision and import/storage tradeoffs, not expected
results for the current schema. See [performance](../guide/performance.md) for
interpretation and [building](../guide/building.md) for current benchmark commands.

The superseded SQLite A/B prototypes were removed from the working tree.
To reproduce them without restoring obsolete code into the current checkout:

```sh
git worktree add --detach /tmp/cantino-storage-spikes fb66a0cfec399e83852d749cc3a5771f5ae2a0eb
cd /tmp/cantino-storage-spikes
cargo run --release --manifest-path spike/sqlite-b/Cargo.toml -- run INPUT.osm.pbf OUTPUT.sqlite
```

That revision contains `spike/sqlite-a` and `spike/sqlite-b`, including their
manifests and lockfiles. Variant A was unfinished; variant B informed the core.
