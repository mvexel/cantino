//! The on-disk area format: one SQLite file per published area.
//!
//! The layout is "variant B" from the storage spike (`spike/sqlite-b`): one
//! row per OSM object with compact blobs (see `encoding`), plus side indexes
//! that are bulk-loaded in key order after the objects. An area file is written
//! once by `import_area` and then only read; nothing updates it in place.
//!
//! ```text
//! nodes      (id PK, lat, lon, version, payload NULL)  e7 integer coordinates;
//!            payload NULL = untagged node without stored metadata
//! ways       (id PK, refs BLOB, payload BLOB)           delta-zigzag node refs
//! relations  (id PK, members BLOB, payload BLOB)
//! dict       (id PK, s)        interned tag keys and member roles
//! users      (uid PK, name)    one display name per uid (first one seen)
//! node_way   (node_id, way_id)        WITHOUT ROWID reverse references
//! member_rel (member, rel_id)         WITHOUT ROWID, member = id*4 + kind
//! tag_index  (k, v, kind, id)         WITHOUT ROWID; k = dict id, v = raw text
//! geo        rtree_i32(id, minx, maxx, miny, maxy)   id = osm_id*4 + kind
//! area       (key PK, value)          object counts written at import
//! profile    (json)                   0 or 1 row: the import profile, if any
//! ```
//!
//! `node_way` and `member_rel` are not queried by the current read-only API.
//! They are kept because parent lookups (which ways use this node?) are the
//! next thing an editor or renderer needs, and they were part of the measured
//! 128.5 MB city footprint, so dropping them later only makes files smaller.
//!
//! `geo` holds bounds for tagged nodes (a degenerate point box), every way with
//! at least one resolvable node, and every relation with at least one
//! resolvable member. Untagged nodes (way vertices) are deliberately absent:
//! they are most of the file and are reached through their ways.
//!
//! `profile` was added without a format bump: older readers ignore unknown
//! tables, and a file without the table (or row) was imported unfiltered.
//!
//! The header's `application_id` and `user_version` identify the format so a
//! foreign or older file fails at open instead of returning garbage.
pub(crate) const APPLICATION_ID: i32 = 0x434E_544E; // "CNTN"
pub(crate) const FORMAT_VERSION: i32 = 1;

/// Object kind codes. They match the C ABI's `kind` argument and order the
/// namespaces in query results (nodes, then ways, then relations).
pub(crate) const NODE: i64 = 0;
pub(crate) const WAY: i64 = 1;
pub(crate) const RELATION: i64 = 2;

/// R-tree and reverse-index key: typed identity packed into one integer.
/// Snapshot IDs are positive and far below 2^61, so this cannot overflow.
pub(crate) fn packed(kind: i64, id: i64) -> i64 {
    id * 4 + kind
}

pub(crate) const CREATE: &str = "
CREATE TABLE nodes(id INTEGER PRIMARY KEY, lat INTEGER NOT NULL, lon INTEGER NOT NULL,
                   version INTEGER NOT NULL, payload BLOB);
CREATE TABLE ways(id INTEGER PRIMARY KEY, refs BLOB NOT NULL, payload BLOB NOT NULL);
CREATE TABLE relations(id INTEGER PRIMARY KEY, members BLOB NOT NULL, payload BLOB NOT NULL);
CREATE TABLE dict(id INTEGER PRIMARY KEY, s TEXT NOT NULL);
CREATE TABLE users(uid INTEGER PRIMARY KEY, name TEXT NOT NULL);
CREATE TABLE node_way(node_id INTEGER, way_id INTEGER, PRIMARY KEY(node_id, way_id)) WITHOUT ROWID;
CREATE TABLE member_rel(member INTEGER, rel_id INTEGER, PRIMARY KEY(member, rel_id)) WITHOUT ROWID;
CREATE TABLE tag_index(k INTEGER, v TEXT, kind INTEGER, id INTEGER,
                       PRIMARY KEY(k, v, kind, id)) WITHOUT ROWID;
CREATE VIRTUAL TABLE geo USING rtree_i32(id, minx, maxx, miny, maxy);
CREATE TABLE profile(json TEXT NOT NULL);
CREATE TABLE area(key TEXT PRIMARY KEY, value INTEGER NOT NULL) WITHOUT ROWID;
";
