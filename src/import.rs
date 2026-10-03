//! Snapshot import: OSM PBF or XML in, one published SQLite area file out.
//!
//! Pipeline (all inside a private staging directory next to the destination):
//!
//! 1. Stream the input once (`input`), validating order and content, and
//!    insert object rows into `build.sqlite` inside a single transaction.
//!    Side-index rows (tags, reverse references, bounds) are collected in
//!    memory meanwhile.
//! 2. Sort the side-index rows and append them in primary-key order, so the
//!    WITHOUT ROWID B-trees are built by appending rather than random inserts.
//!    Then commit.
//! 3. `VACUUM INTO area.sqlite` writes a compacted copy. Unlike an in-place
//!    `VACUUM`, it needs no temporary database; on Android SQLite's temporary
//!    storage is in memory, so an in-place VACUUM would hold a second copy of
//!    the whole area in RAM.
//! 4. Reopen the copy read-only through the normal `Store` path, check the
//!    recorded counts against the tables, fsync, rename over the destination
//!    and fsync the directory.
//!
//! Durability of the build database does not matter: it is private, never
//! published, and deleted with the staging directory on any failure. That is
//! why it runs with `journal_mode=OFF` and `synchronous=OFF`. Durability of the
//! published file comes from the explicit fsyncs in step 4, not from SQLite.
use crate::{
    Bbox, Coordinate, Error, Result, Store,
    encoding::{put_svar, put_uvar},
    input,
    schema::{self, NODE, RELATION, WAY, packed},
};
use rusqlite::{Connection, Statement, params};
use serde::{Deserialize, Serialize};
use std::{collections::HashMap, path::Path};

/// Import tuning. Unknown JSON fields are ignored (`serde` default), so options
/// written for the earlier OSMExpress backend (`map_size`, `sort_pairs`) are
/// still accepted and have no effect.
#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
#[serde(default)]
pub struct ImportOptions {
    /// Keep version, timestamp, changeset and user for untagged nodes too.
    /// Off by default: untagged nodes are mostly way vertices, and their
    /// metadata is about a quarter of a city file. When off, such nodes read
    /// back with `metadata: None` and `location_version` = their version.
    pub preserve_untagged_metadata: bool,
    /// SQLite page cache for the build connection, in MiB. Larger values speed
    /// up imports of large areas at the cost of resident memory. Lookup tables
    /// held by the importer itself (node coordinates, side-index rows) are not
    /// bounded by this.
    pub cache_mb: u32,
}
impl Default for ImportOptions {
    fn default() -> Self {
        Self {
            preserve_untagged_metadata: false,
            cache_mb: 16,
        }
    }
}

#[derive(Debug, Clone, Copy, Default, Serialize, Deserialize, PartialEq, Eq)]
pub struct Counts {
    pub nodes: usize,
    pub ways: usize,
    pub relations: usize,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct ImportReport {
    pub counts: Counts,
    /// Size of the published file.
    pub database_bytes: u64,
}

/// Import into a private sibling directory, validate the result, sync it, then
/// publish it with an atomic rename. Failure leaves the old destination intact
/// and removes the staging directory.
///
/// An already-open `Store` keeps reading its previous file: the rename swaps
/// the directory entry, while the open connection holds the old inode. Drop
/// the store and reopen the destination to see the new area.
///
/// Input is sniffed: a file starting with `<` (after an optional BOM and
/// whitespace) is OSM XML, anything else is treated as PBF. Compressed XML is
/// not supported.
pub fn import_area(
    input: impl AsRef<Path>,
    destination: impl AsRef<Path>,
    options: ImportOptions,
) -> Result<ImportReport> {
    let input = input.as_ref();
    let destination = destination.as_ref();
    if destination.file_name().is_none() {
        return Err(Error::Invalid("destination must be a file".into()));
    }
    let parent = destination
        .parent()
        .filter(|p| !p.as_os_str().is_empty())
        .unwrap_or(Path::new("."));
    // The staging directory lives next to the destination so the final rename
    // stays on one filesystem (and is therefore atomic). `TempDir` removes it
    // on every early return.
    let staging = tempfile::Builder::new()
        .prefix(".osmfw-stage-")
        .tempdir_in(parent)?;
    let build = staging.path().join("build.sqlite");
    let staged = staging.path().join("area.sqlite");

    let expected = build_database(input, &build, &staged, options)?;
    std::fs::remove_file(&build)?;

    // Validate through the same code path readers use.
    let store = Store::open(&staged)?;
    let counts = store.verify_counts()?;
    drop(store);
    if counts != expected {
        return Err(Error::Corrupt(format!(
            "staged area holds {counts:?}, importer wrote {expected:?}"
        )));
    }

    let file = std::fs::File::open(&staged)?;
    file.sync_all()?;
    let database_bytes = file.metadata()?.len();
    drop(file);
    std::fs::rename(&staged, destination)?;
    // POSIX directory sync makes the new directory entry durable after rename.
    #[cfg(unix)]
    std::fs::File::open(parent)?.sync_all()?;
    Ok(ImportReport {
        counts,
        database_bytes,
    })
}

/// Steps 1-3 of the pipeline. Returns the counts the importer wrote.
fn build_database(
    input: &Path,
    build: &Path,
    staged: &Path,
    options: ImportOptions,
) -> Result<Counts> {
    let connection = Connection::open(build)?;
    let cache_kib = i64::from(options.cache_mb.max(1)) * 1024;
    connection.execute_batch(&format!(
        "PRAGMA page_size=4096; PRAGMA journal_mode=OFF; PRAGMA synchronous=OFF;
         PRAGMA locking_mode=EXCLUSIVE; PRAGMA cache_size=-{cache_kib};
         PRAGMA application_id={}; PRAGMA user_version={};",
        schema::APPLICATION_ID,
        schema::FORMAT_VERSION
    ))?;
    connection.execute_batch(schema::CREATE)?;
    // One transaction for the whole load. With journal_mode=OFF a failure
    // cannot be rolled back, which is fine: the file is discarded.
    connection.execute_batch("BEGIN")?;
    let counts = {
        let mut importer = Importer::new(&connection, options)?;
        input::read(input, &mut importer)?;
        importer.finish()?
    };
    connection.execute_batch("COMMIT")?;
    let target = staged
        .to_str()
        .ok_or_else(|| Error::Invalid("path must be UTF-8".into()))?;
    connection.execute("VACUUM INTO ?1", [target])?;
    connection.close().map_err(|(_, error)| error)?;
    Ok(counts)
}

/// Bounds in e7 units: `[min_lon, max_lon, min_lat, max_lat]`, the column
/// order of the `geo` R-tree.
type Bounds = [i32; 4];
fn point(lon: i32, lat: i32) -> Bounds {
    [lon, lon, lat, lat]
}
fn extend(bounds: &mut Option<Bounds>, other: Bounds) {
    *bounds = Some(match *bounds {
        None => other,
        Some(b) => [
            b[0].min(other[0]),
            b[1].max(other[1]),
            b[2].min(other[2]),
            b[3].max(other[3]),
        ],
    });
}

/// Metadata as it arrives from a reader, before range checks.
pub(crate) struct SourceInfo<'a> {
    pub version: i64,
    /// Unix seconds.
    pub timestamp: i64,
    pub changeset: i64,
    pub uid: i64,
    pub user: &'a str,
    /// False for deleted objects (history files, osmChange-like dumps).
    pub visible: bool,
}

/// String interner for dictionary ids. Ids are dense, in first-seen order.
#[derive(Default)]
struct Interner(HashMap<String, u32>);
impl Interner {
    fn id(&mut self, text: &str) -> u32 {
        if let Some(&id) = self.0.get(text) {
            return id;
        }
        let id = self.0.len() as u32;
        self.0.insert(text.to_owned(), id);
        id
    }
    fn into_vec(self) -> Vec<String> {
        let mut strings = vec![String::new(); self.0.len()];
        for (text, id) in self.0 {
            strings[id as usize] = text;
        }
        strings
    }
}

struct TagRow {
    key: u32,
    value: u32,
    kind: u8,
    id: i64,
}

/// Receives validated objects from a reader and writes them to the build
/// database. Readers call `node`, `way` and `relation` in file order.
///
/// Invariant enforced here (not in the readers): objects arrive nodes first,
/// then ways, then relations, each with strictly ascending positive IDs. That
/// is what an OSM snapshot looks like; it rejects duplicates and history files
/// (several versions of one ID), and it lets coordinate lookups during import
/// use binary search over the IDs seen so far.
pub(crate) struct Importer<'c> {
    options: ImportOptions,
    insert_node: Statement<'c>,
    insert_way: Statement<'c>,
    insert_relation: Statement<'c>,
    connection: &'c Connection,
    payload: Vec<u8>,
    refs: Vec<u8>,
    dict: Interner,
    values: Interner,
    users: HashMap<u32, String>,
    tag_rows: Vec<TagRow>,
    node_way: Vec<(i64, i64)>,
    member_rel: Vec<(i64, i64)>,
    /// Every node's ID and position, parallel and ascending, for way bounds.
    /// About 16 bytes per node: the dominant import allocation for a city.
    node_ids: Vec<i64>,
    node_points: Vec<(i32, i32)>,
    /// Way bounds by ascending way ID, for relation bounds.
    way_bounds: Vec<(i64, Bounds)>,
    /// Relation bounds from direct node/way members, by ascending ID, plus the
    /// relation-to-relation edges resolved in `finish`.
    relation_bounds: Vec<(i64, Option<Bounds>)>,
    relation_children: Vec<(usize, i64)>,
    geo_rows: Vec<(i64, Bounds)>,
    last_id: [i64; 3],
    stage: i64,
    counts: Counts,
}

impl<'c> Importer<'c> {
    fn new(connection: &'c Connection, options: ImportOptions) -> Result<Self> {
        Ok(Self {
            options,
            insert_node: connection.prepare("INSERT INTO nodes VALUES(?1, ?2, ?3, ?4, ?5)")?,
            insert_way: connection.prepare("INSERT INTO ways VALUES(?1, ?2, ?3)")?,
            insert_relation: connection.prepare("INSERT INTO relations VALUES(?1, ?2, ?3)")?,
            connection,
            payload: Vec::with_capacity(4096),
            refs: Vec::with_capacity(4096),
            dict: Interner::default(),
            values: Interner::default(),
            users: HashMap::new(),
            tag_rows: Vec::new(),
            node_way: Vec::new(),
            member_rel: Vec::new(),
            node_ids: Vec::new(),
            node_points: Vec::new(),
            way_bounds: Vec::new(),
            relation_bounds: Vec::new(),
            relation_children: Vec::new(),
            geo_rows: Vec::new(),
            last_id: [0; 3],
            stage: NODE,
            counts: Counts::default(),
        })
    }

    /// Order, identity and visibility checks shared by all kinds.
    fn admit(&mut self, kind: i64, id: i64, visible: bool) -> Result<()> {
        let name = ["node", "way", "relation"][kind as usize];
        if !visible {
            return Err(Error::Input(format!(
                "{name} {id} is deleted (visible=false); only current snapshots can be imported"
            )));
        }
        if id <= 0 {
            return Err(Error::Input(format!(
                "{name} {id}: snapshot IDs must be positive"
            )));
        }
        if kind < self.stage {
            return Err(Error::Input(format!(
                "{name} {id} after later object kinds; input must be ordered nodes, ways, relations"
            )));
        }
        self.stage = kind;
        let last = &mut self.last_id[kind as usize];
        if id <= *last {
            return Err(Error::Input(format!(
                "{name} {id} is duplicate or out of order (previous {name} {}); \
                 IDs must be strictly ascending, and history files are not supported",
                *last
            )));
        }
        *last = id;
        Ok(())
    }

    /// Writes `tags` in key order into `self.payload` and queues their index
    /// rows. Rejects duplicate keys, which a `Tags` map could not represent.
    fn tags<'t>(
        &mut self,
        kind: i64,
        id: i64,
        tags: impl IntoIterator<Item = (&'t str, &'t str)>,
    ) -> Result<usize> {
        let mut values: Vec<(&str, &str)> = tags.into_iter().collect();
        values.sort_unstable_by(|a, b| a.0.cmp(b.0));
        if let Some(pair) = values.windows(2).find(|pair| pair[0].0 == pair[1].0) {
            return Err(Error::Input(format!(
                "{} {id} has duplicate tag key {:?}",
                ["node", "way", "relation"][kind as usize],
                pair[0].0
            )));
        }
        put_uvar(&mut self.payload, values.len() as u64);
        for (key, value) in &values {
            let key_id = self.dict.id(key);
            put_uvar(&mut self.payload, u64::from(key_id));
            put_uvar(&mut self.payload, value.len() as u64);
            self.payload.extend_from_slice(value.as_bytes());
            self.tag_rows.push(TagRow {
                key: key_id,
                value: self.values.id(value),
                kind: kind as u8,
                id,
            });
        }
        Ok(values.len())
    }

    /// Appends metadata to `self.payload`. Values outside the model's integer
    /// ranges are rejected rather than silently truncated; negative values
    /// (some writers use -1 for "unknown") are stored as 0, matching how the
    /// model already represents absent fields.
    fn metadata(&mut self, info: &SourceInfo, with_version: bool) -> Result<()> {
        let field = |value: i64, name: &str| -> Result<u64> {
            let value = value.max(0);
            if name != "timestamp" && value > i64::from(u32::MAX) {
                return Err(Error::Input(format!("{name} {value} out of range")));
            }
            Ok(value as u64)
        };
        if with_version {
            put_uvar(&mut self.payload, field(info.version, "version")?);
        }
        put_uvar(&mut self.payload, field(info.timestamp, "timestamp")?);
        put_uvar(&mut self.payload, field(info.changeset, "changeset")?);
        let uid = field(info.uid, "uid")? as u32;
        put_uvar(&mut self.payload, u64::from(uid));
        if uid != 0 && !info.user.is_empty() {
            // First name seen wins; renamed users keep one name per area.
            self.users
                .entry(uid)
                .or_insert_with(|| info.user.to_owned());
        }
        Ok(())
    }

    pub(crate) fn node<'t>(
        &mut self,
        id: i64,
        lat_e7: i32,
        lon_e7: i32,
        tags: impl IntoIterator<Item = (&'t str, &'t str)>,
        info: &SourceInfo,
    ) -> Result<()> {
        self.admit(NODE, id, info.visible)?;
        Coordinate::new(lat_e7, lon_e7)
            .map_err(|_| Error::Input(format!("node {id} has coordinates outside WGS84")))?;
        if info.version < 0 || info.version > i64::from(i32::MAX) {
            return Err(Error::Input(format!("node {id} version out of range")));
        }
        self.payload.clear();
        let tag_count = self.tags(NODE, id, tags)?;
        self.node_ids.push(id);
        self.node_points.push((lon_e7, lat_e7));
        // Tagged nodes always keep a payload (tags + metadata). Untagged nodes
        // keep one only on request; NULL means "no tags, no stored metadata".
        let payload = if tag_count > 0 || self.options.preserve_untagged_metadata {
            self.metadata(info, false)?;
            Some(self.payload.as_slice())
        } else {
            None
        };
        if tag_count > 0 {
            self.geo_rows
                .push((packed(NODE, id), point(lon_e7, lat_e7)));
        }
        self.insert_node
            .execute(params![id, lat_e7, lon_e7, info.version, payload])?;
        self.counts.nodes += 1;
        Ok(())
    }

    fn node_point(&self, id: i64) -> Option<(i32, i32)> {
        self.node_ids
            .binary_search(&id)
            .ok()
            .map(|index| self.node_points[index])
    }

    pub(crate) fn way<'t>(
        &mut self,
        id: i64,
        refs: impl IntoIterator<Item = i64>,
        tags: impl IntoIterator<Item = (&'t str, &'t str)>,
        info: &SourceInfo,
    ) -> Result<()> {
        self.admit(WAY, id, info.visible)?;
        self.payload.clear();
        self.tags(WAY, id, tags)?;
        self.metadata(info, true)?;
        self.refs.clear();
        let mut previous = 0i64;
        let mut bounds = None;
        for node in refs {
            // Deltas of consecutive refs are small for spatially sorted IDs;
            // order and repeats (closed ways) are preserved exactly.
            put_svar(&mut self.refs, node.wrapping_sub(previous));
            previous = node;
            self.node_way.push((node, id));
            // Missing nodes (clipped extracts) leave a gap in the bounds; the
            // bounds then cover only the resolvable part of the way.
            if let Some((lon, lat)) = self.node_point(node) {
                extend(&mut bounds, point(lon, lat));
            }
        }
        self.insert_way
            .execute(params![id, &self.refs, &self.payload])?;
        if let Some(bounds) = bounds {
            self.geo_rows.push((packed(WAY, id), bounds));
            self.way_bounds.push((id, bounds));
        }
        self.counts.ways += 1;
        Ok(())
    }

    /// Members are `(kind, id, role)` with kind codes from `schema`.
    pub(crate) fn relation<'t>(
        &mut self,
        id: i64,
        members: impl IntoIterator<Item = (i64, i64, &'t str)>,
        tags: impl IntoIterator<Item = (&'t str, &'t str)>,
        info: &SourceInfo,
    ) -> Result<()> {
        self.admit(RELATION, id, info.visible)?;
        self.payload.clear();
        self.tags(RELATION, id, tags)?;
        self.metadata(info, true)?;
        self.refs.clear();
        let mut previous = 0i64;
        let mut bounds = None;
        let index = self.relation_bounds.len();
        for (kind, member, role) in members {
            let role = self.dict.id(role);
            put_uvar(&mut self.refs, (u64::from(role) << 2) | kind as u64);
            put_svar(&mut self.refs, member.wrapping_sub(previous));
            previous = member;
            self.member_rel.push((packed(kind, member), id));
            match kind {
                NODE => {
                    if let Some((lon, lat)) = self.node_point(member) {
                        extend(&mut bounds, point(lon, lat));
                    }
                }
                WAY => {
                    if let Ok(found) = self.way_bounds.binary_search_by_key(&member, |w| w.0) {
                        extend(&mut bounds, self.way_bounds[found].1);
                    }
                }
                // Child relations may come later in the file; resolve in finish.
                _ => self.relation_children.push((index, member)),
            }
        }
        self.insert_relation
            .execute(params![id, &self.refs, &self.payload])?;
        self.relation_bounds.push((id, bounds));
        self.counts.relations += 1;
        Ok(())
    }

    /// Writes the side tables in key order and returns the object counts.
    fn finish(mut self) -> Result<Counts> {
        // Coordinates are no longer needed; free them before the sorts below.
        self.node_ids = Vec::new();
        self.node_points = Vec::new();
        self.way_bounds = Vec::new();
        self.resolve_relation_bounds();
        let connection = self.connection;

        {
            let mut insert = connection.prepare("INSERT INTO geo VALUES(?1, ?2, ?3, ?4, ?5)")?;
            for (id, b) in &self.geo_rows {
                insert.execute(params![id, b[0], b[1], b[2], b[3]])?;
            }
            for (id, bounds) in &self.relation_bounds {
                if let Some(b) = bounds {
                    insert.execute(params![packed(RELATION, *id), b[0], b[1], b[2], b[3]])?;
                }
            }
        }
        self.geo_rows = Vec::new();

        {
            let mut insert = connection.prepare("INSERT INTO dict VALUES(?1, ?2)")?;
            for (id, text) in std::mem::take(&mut self.dict).into_vec().iter().enumerate() {
                insert.execute(params![id as i64, text])?;
            }
            let mut insert = connection.prepare("INSERT INTO users VALUES(?1, ?2)")?;
            for (uid, name) in &self.users {
                insert.execute(params![uid, name])?;
            }
        }

        // Reverse references: sorted and deduplicated (a closed way references
        // its first node twice) so inserts append to the B-tree.
        for (table, mut rows) in [
            ("node_way", std::mem::take(&mut self.node_way)),
            ("member_rel", std::mem::take(&mut self.member_rel)),
        ] {
            rows.sort_unstable();
            rows.dedup();
            let mut insert = connection.prepare(&format!("INSERT INTO {table} VALUES(?1, ?2)"))?;
            for (a, b) in &rows {
                insert.execute(params![a, b])?;
            }
        }

        // tag_index orders by value *text*. Rank the interned values once by
        // their bytes (SQLite's BINARY collation compares UTF-8 bytes, as does
        // `str::cmp`), then sort rows by integers instead of strings.
        let values = std::mem::take(&mut self.values).into_vec();
        let mut order: Vec<u32> = (0..values.len() as u32).collect();
        order.sort_unstable_by(|a, b| values[*a as usize].cmp(&values[*b as usize]));
        let mut rank = vec![0u32; values.len()];
        for (position, value) in order.iter().enumerate() {
            rank[*value as usize] = position as u32;
        }
        let mut rows = std::mem::take(&mut self.tag_rows);
        rows.sort_unstable_by_key(|row| (row.key, rank[row.value as usize], row.kind, row.id));
        {
            let mut insert = connection.prepare("INSERT INTO tag_index VALUES(?1, ?2, ?3, ?4)")?;
            for row in &rows {
                insert.execute(params![
                    row.key,
                    values[row.value as usize],
                    row.kind,
                    row.id
                ])?;
            }
        }

        let counts = self.counts;
        let mut insert = connection.prepare("INSERT INTO area VALUES(?1, ?2)")?;
        for (key, value) in [
            ("nodes", counts.nodes),
            ("ways", counts.ways),
            ("relations", counts.relations),
        ] {
            insert.execute(params![key, value as i64])?;
        }
        Ok(counts)
    }

    /// Adds child-relation bounds to their parents. Union is monotone and the
    /// lattice of boxes is finite, so iterating to a fixed point terminates
    /// even for cyclic relations; the round cap is a backstop, not a limit
    /// expected to bind (each round propagates at least one level of nesting).
    fn resolve_relation_bounds(&mut self) {
        let rounds = self.relation_bounds.len() + 1;
        for _ in 0..rounds {
            let mut changed = false;
            for &(parent, child) in &self.relation_children {
                let Ok(found) = self.relation_bounds.binary_search_by_key(&child, |r| r.0) else {
                    continue;
                };
                let Some(child) = self.relation_bounds[found].1 else {
                    continue;
                };
                let before = self.relation_bounds[parent].1;
                extend(&mut self.relation_bounds[parent].1, child);
                changed |= before != self.relation_bounds[parent].1;
            }
            if !changed {
                break;
            }
        }
    }
}

/// e7 integer bounds of a query box for the R-tree: the smallest integer
/// rectangle containing every e7 point inside `bbox`. Values within 1e-6 of an
/// integer snap to it, so a box typed in degrees with 7 decimals is exact
/// despite float scaling error.
pub(crate) fn e7_bounds(bbox: Bbox) -> Bounds {
    fn scaled(value: f64, up: bool) -> i32 {
        let scaled = value * 1e7;
        let nearest = scaled.round();
        let result = if (scaled - nearest).abs() < 1e-6 {
            nearest
        } else if up {
            scaled.ceil()
        } else {
            scaled.floor()
        };
        result as i32
    }
    [
        scaled(bbox.west, true),
        scaled(bbox.east, false),
        scaled(bbox.south, true),
        scaled(bbox.north, false),
    ]
}
