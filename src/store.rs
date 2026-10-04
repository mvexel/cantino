//! Read side of a published area: lookups, tag/bbox queries and graph helpers.
use crate::{
    encoding::{Reader, corrupt},
    import::{Counts, e7_bounds},
    schema::{self, NODE, RELATION, WAY, packed},
    *,
};
use rusqlite::{Connection, OpenFlags, OptionalExtension, params};
use serde::{Deserialize, Serialize};
use std::{
    collections::{BTreeSet, HashMap},
    marker::PhantomData,
    path::Path,
    rc::Rc,
};

/// Owns one read-only connection to a published area file.
///
/// Thread confinement: use a `Store` on one worker thread. The `Rc` marker
/// deliberately makes it neither `Send` nor `Sync`; the C ABI additionally
/// checks the calling thread at runtime. SQLite itself would tolerate
/// serialised cross-thread use, but cached statements and the decoding
/// dictionaries are not designed for it, and one confinement rule is simpler
/// for the Kotlin and Swift adapters than two.
///
/// Several `Store`s may open the same file (also across processes): the file
/// is never written after publication, and SQLite's shared read locks allow
/// any number of readers. Opening per query is still wasteful, because `open`
/// loads the key and user dictionaries.
///
/// Objects returned from lookups and queries own their data and outlive the
/// store.
pub struct Store {
    connection: Connection,
    /// Interned tag keys and member roles by dictionary id.
    dict: Vec<String>,
    /// Reverse of `dict`, for turning query keys into ids.
    keys: HashMap<String, i64>,
    users: HashMap<u32, String>,
    _thread: PhantomData<Rc<()>>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MissingReference {
    pub position: usize,
    pub target: OsmId,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Dependencies {
    pub objects: Vec<Object>,
    pub missing: Vec<OsmId>,
}
/// One tag predicate of a `Query`. Matching is on raw strings: case-sensitive,
/// no trimming; a missing tag and a tag with an empty value are distinct.
///
/// JSON wire form (C ABI, Kotlin adapter): `{"Exists":"k"}`,
/// `{"Equals":["k","v"]}`, `{"NotExists":"k"}`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum TagFilter {
    /// Tag `k` is present with any value (an empty value counts).
    Exists(String),
    /// Tag `k` has exactly value `v`.
    Equals(String, String),
    /// Tag `k` is absent. Never drives a query: absence has no index range,
    /// so it is only checked on candidates that another filter (an
    /// `Exists`/`Equals` filter or the bbox) produced. A query whose only
    /// filters are `NotExists` is rejected instead of silently scanning
    /// the whole area.
    NotExists(String),
}
impl TagFilter {
    fn key(&self) -> &str {
        match self {
            Self::Exists(key) | Self::Equals(key, _) | Self::NotExists(key) => key,
        }
    }
    /// Whether this filter can be a candidate source (has an index range).
    fn drives(&self) -> bool {
        !matches!(self, Self::NotExists(_))
    }
    fn matches(&self, object: &Object) -> bool {
        match self {
            Self::Exists(key) => object.tag(key).is_some(),
            Self::Equals(key, value) => object.tag(key) == Some(value.as_str()),
            Self::NotExists(key) => object.tag(key).is_none(),
        }
    }
}

/// Most IDs one `Store::get_many` call accepts; the same bound as a query
/// page, so one batch never holds more objects than one page can.
pub const MAX_BATCH: usize = 10_000;

/// How deep `Store::representative_point` follows relation members that are
/// relations themselves. Deeper members are ignored (they contribute no
/// point), which bounds the work on pathological nesting.
pub const MAX_RELATION_DEPTH: usize = 8;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default)]
pub struct Query {
    /// All filters must match; missing and empty tag values are distinct.
    /// `NotExists` filters need a driver: at least one `Exists`/`Equals`
    /// filter or a bbox.
    pub tags: Vec<TagFilter>,
    /// Spatial filter. Tagged nodes match exactly when the point lies inside
    /// the box (edges included). Ways and relations match when their bounding
    /// box intersects it, so results are *candidates*: a way that bends around
    /// the box, or a relation whose members only surround it, is returned
    /// although no part of it lies inside. Ways crossing the box without a node
    /// inside it are included. Untagged nodes are never returned by a bbox
    /// query; reach them through their ways. Bounds cover only members present
    /// in the area, so clipped objects can be missed near the area's edge.
    pub bbox: Option<Bbox>,
    /// Keyset cursor: return objects strictly after this one in `(kind, id)`
    /// order (nodes, then ways, then relations; ascending IDs).
    pub after: Option<OsmId>,
    pub limit: usize,
    /// Upper bound on the spatial candidates a bbox-driven query may collect
    /// (all kinds together). Exceeding it is an error, not a truncation, so a
    /// caller never mistakes a partial answer for a complete one.
    pub max_candidates: usize,
}
impl Default for Query {
    fn default() -> Self {
        Self {
            tags: vec![],
            bbox: None,
            after: None,
            limit: 100,
            max_candidates: 100000,
        }
    }
}

#[derive(Debug, Clone, Copy)]
#[repr(i32)]
pub enum ObjectKind {
    Node = 0,
    Way = 1,
    Relation = 2,
}

/// Index-backed tag lookups. Rows of `tag_index` for one `(k, v)` are stored
/// in `(kind, id)` order, so an `Equals` driver streams results in output
/// order without sorting and resumes from the cursor with a B-tree seek.
/// An `Exists` driver reads every row of the key (a prefix range of the
/// primary key) and sorts them by `(kind, id)`: cost grows with the number of
/// objects carrying the key, per page.
const TAG_EQUALS: &str = "SELECT kind, id FROM tag_index
     WHERE k = ?1 AND v = ?2 AND (kind, id) > (?3, ?4) ORDER BY kind, id";
const TAG_EXISTS: &str = "SELECT kind, id FROM tag_index
     WHERE k = ?1 AND (kind, id) > (?3, ?4) ORDER BY kind, id";
/// The same ranges, counted up to a cap, to choose the most selective driver.
const COUNT_EQUALS: &str =
    "SELECT count(*) FROM (SELECT 1 FROM tag_index WHERE k = ?1 AND v = ?2 LIMIT ?3)";
const COUNT_EXISTS: &str = "SELECT count(*) FROM (SELECT 1 FROM tag_index WHERE k = ?1 LIMIT ?3)";
/// Bounds intersection on the R-tree; for point rows this is point-in-box.
const GEO_SEARCH: &str =
    "SELECT id FROM geo WHERE minx <= ?2 AND maxx >= ?1 AND miny <= ?4 AND maxy >= ?3 LIMIT ?5";
const COUNT_GEO: &str = "SELECT count(*) FROM (SELECT id FROM geo
     WHERE minx <= ?2 AND maxx >= ?1 AND miny <= ?4 AND maxy >= ?3 LIMIT ?5)";
/// Object reads. Statements are prepared once per connection and cached.
const NODE_BY_ID: &str = "SELECT id, lat, lon, version, payload FROM nodes WHERE id = ?1";
const WAY_BY_ID: &str = "SELECT id, refs, payload FROM ways WHERE id = ?1";
const RELATION_BY_ID: &str = "SELECT id, members, payload FROM relations WHERE id = ?1";
const NODE_RANGE: &str =
    "SELECT id, lat, lon, version, payload FROM nodes WHERE id > ?1 ORDER BY id LIMIT ?2";
const WAY_RANGE: &str = "SELECT id, refs, payload FROM ways WHERE id > ?1 ORDER BY id LIMIT ?2";
const RELATION_RANGE: &str =
    "SELECT id, members, payload FROM relations WHERE id > ?1 ORDER BY id LIMIT ?2";
/// Selectivity estimates stop counting here; beyond it the exact number does
/// not change which driver is cheaper by much.
const ESTIMATE_CAP: i64 = 10_000;

/// Where a query's candidates come from.
enum Driver<'q> {
    Tag(&'q TagFilter, i64),
    Bbox,
    Scan,
}

impl Store {
    /// Opens a published area read-only.
    pub fn open(path: impl AsRef<Path>) -> Result<Self> {
        // NO_MUTEX: the handle is confined to one thread (see the type docs),
        // so SQLite's per-connection mutex would be pure overhead.
        let connection = Connection::open_with_flags(
            path,
            OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_NO_MUTEX,
        )?;
        let (application_id, version): (i32, i32) = connection.query_row(
            "SELECT * FROM pragma_application_id, pragma_user_version",
            [],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )?;
        if application_id != schema::APPLICATION_ID || version != schema::FORMAT_VERSION {
            // A readable SQLite file that is not ours, or ours from another
            // format version: InvalidFile for the adapters, never a caller error.
            return Err(Error::Format(format!(
                "not a Cantino area of format {} (application_id {application_id:#x}, \
                 version {version}); re-import it",
                schema::FORMAT_VERSION
            )));
        }
        let mut dict = vec![];
        {
            let mut statement = connection.prepare("SELECT id, s FROM dict ORDER BY id")?;
            let mut rows = statement.query([])?;
            while let Some(row) = rows.next()? {
                // Ids are dense from 0, so the position in `dict` is the id.
                if row.get::<_, i64>(0)? != dict.len() as i64 {
                    return Err(corrupt("dictionary ids are not dense"));
                }
                dict.push(row.get::<_, String>(1)?);
            }
        }
        let keys = dict
            .iter()
            .enumerate()
            .map(|(id, text)| (text.clone(), id as i64))
            .collect();
        let users = connection
            .prepare("SELECT uid, name FROM users")?
            .query_map([], |row| Ok((row.get::<_, u32>(0)?, row.get(1)?)))?
            .collect::<rusqlite::Result<_>>()?;
        // Room for every statement this type prepares (about 20), so mixed
        // workloads never evict and re-prepare.
        connection.set_prepared_statement_cache_capacity(32);
        Ok(Self {
            connection,
            dict,
            keys,
            users,
            _thread: PhantomData,
        })
    }

    /// The object with `id`, or `None` when it is not in the area.
    pub fn get(&self, id: OsmId) -> Result<Option<Object>> {
        let (kind, id) = checked_id(id)?;
        match kind {
            NODE => self.read_nodes(NODE_BY_ID, params![id]),
            WAY => self.read_ways(WAY_BY_ID, params![id]),
            _ => self.read_relations(RELATION_BY_ID, params![id]),
        }
        .map(|mut objects| objects.pop())
    }

    /// Looks up several objects at once: one result per input ID, in input
    /// order, `None` where the object is not in the area. Duplicates in `ids`
    /// are looked up (and returned) once per occurrence.
    ///
    /// Same cost per object as `get` (each is a primary-key lookup on a
    /// cached statement); the point is one call across the C ABI / JNI
    /// instead of one per object. At most `MAX_BATCH` IDs per call; more is
    /// an `Error::Invalid`, never a truncation. All IDs are validated before
    /// any is read, so an invalid ID fails the whole call.
    pub fn get_many(&self, ids: &[OsmId]) -> Result<Vec<Option<Object>>> {
        if ids.len() > MAX_BATCH {
            return Err(Error::Invalid(format!(
                "batch get takes at most {MAX_BATCH} IDs, got {}; split the request",
                ids.len()
            )));
        }
        for id in ids {
            checked_id(*id)?;
        }
        ids.iter().map(|id| self.get(*id)).collect()
    }

    /// Counts recorded at import. Constant time.
    pub fn counts(&self) -> Result<Counts> {
        let mut counts = Counts::default();
        let mut statement = self
            .connection
            .prepare_cached("SELECT key, value FROM area")?;
        let mut rows = statement.query([])?;
        while let Some(row) = rows.next()? {
            let value = row.get::<_, i64>(1)? as usize;
            match row.get_ref(0)?.as_str()? {
                "nodes" => counts.nodes = value,
                "ways" => counts.ways = value,
                "relations" => counts.relations = value,
                _ => {}
            }
        }
        Ok(counts)
    }

    /// The [`ImportProfile`] this area was imported with, or `None` for an
    /// unfiltered import (including files written before profiles existed,
    /// which have no `profile` table).
    pub fn profile(&self) -> Result<Option<ImportProfile>> {
        let has_table: bool = self.connection.query_row(
            "SELECT EXISTS(SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'profile')",
            [],
            |row| row.get(0),
        )?;
        if !has_table {
            return Ok(None);
        }
        let mut statement = self.connection.prepare_cached("SELECT json FROM profile")?;
        let mut rows = statement.query([])?;
        let Some(row) = rows.next()? else {
            return Ok(None);
        };
        let json: String = row.get(0)?;
        serde_json::from_str(&json)
            .map(Some)
            .map_err(|error| Error::Corrupt(format!("recorded import profile: {error}")))
    }

    /// Recorded counts, checked against the tables (a full pass over each
    /// table's B-tree). Import uses this before publishing.
    pub(crate) fn verify_counts(&self) -> Result<Counts> {
        let recorded = self.counts()?;
        let count = |table: &str| -> Result<usize> {
            let value: i64 =
                self.connection
                    .query_row(&format!("SELECT count(*) FROM {table}"), [], |row| {
                        row.get(0)
                    })?;
            Ok(value as usize)
        };
        let actual = Counts {
            nodes: count("nodes")?,
            ways: count("ways")?,
            relations: count("relations")?,
        };
        if actual != recorded {
            return Err(Error::Corrupt(format!(
                "area records {recorded:?} but holds {actual:?}"
            )));
        }
        Ok(actual)
    }

    /// One namespace in ID order, starting after `after`. A primary-key range
    /// read; useful for exports and for checking an area exhaustively.
    pub fn scan(&self, kind: ObjectKind, after: u64, limit: usize) -> Result<Vec<Object>> {
        if !(1..=10000).contains(&limit) {
            return Err(Error::Invalid("scan limit must be 1..=10000".into()));
        }
        let after = i64::try_from(after).map_err(|_| Error::Invalid("cursor too large".into()))?;
        let limit = limit as i64;
        match kind {
            ObjectKind::Node => self.read_nodes(NODE_RANGE, params![after, limit]),
            ObjectKind::Way => self.read_ways(WAY_RANGE, params![after, limit]),
            ObjectKind::Relation => self.read_relations(RELATION_RANGE, params![after, limit]),
        }
    }

    /// IDs whose stored bounds intersect `bbox` (points: inside it), sorted.
    /// Errors when more than `maximum` objects match instead of truncating.
    /// See `Query::bbox` for what the bounds do and do not promise.
    pub fn spatial_candidates(&self, bbox: Bbox, maximum: usize) -> Result<Vec<OsmId>> {
        bbox.validate()?;
        let [west, east, south, north] = e7_bounds(bbox);
        let mut ids = vec![];
        if west > east || south > north {
            // The box lies between two e7 grid lines: nothing can be inside.
            return Ok(ids);
        }
        let mut statement = self.connection.prepare_cached(GEO_SEARCH)?;
        let cap = i64::try_from(maximum).unwrap_or(i64::MAX - 1) + 1;
        let mut rows = statement.query(params![west, east, south, north, cap])?;
        while let Some(row) = rows.next()? {
            if ids.len() == maximum {
                return Err(Error::Invalid(format!(
                    "bbox selects more than {maximum} candidates; narrow it or raise the limit"
                )));
            }
            ids.push(unpack(row.get(0)?)?);
        }
        ids.sort_unstable();
        Ok(ids)
    }

    /// Query raw tags and/or a bbox with stable `(kind, id)` ordering and
    /// keyset pagination.
    ///
    /// Execution never scans object tables for filtered queries:
    /// - With tag filters, the filter matching the fewest index rows drives
    ///   (estimated with capped counts on `tag_index`); the other filters are
    ///   checked on each candidate object.
    /// - With a bbox, the R-tree is a candidate source too. Whichever of the
    ///   best tag filter and the bbox selects fewer rows drives; the other is
    ///   applied per candidate (bbox via an R-tree rowid lookup).
    /// - `NotExists` filters never drive; they are checked per candidate. A
    ///   query whose only filters are `NotExists` (no other tag filter, no
    ///   bbox) is rejected with `Error::Invalid`.
    /// - Without any filter, objects are read in primary-key order.
    pub fn query(&self, query: &Query) -> Result<Vec<Object>> {
        if !(1..=10000).contains(&query.limit) {
            return Err(Error::Invalid("query limit must be 1..=10000".into()));
        }
        // NotExists filters are post-checks only. Without a filter that can
        // drive (Exists/Equals or a bbox), answering would mean a full scan
        // of every table, which this method promises never to do for a
        // filtered query, so reject it with a message naming the fix.
        let drivers = query.tags.iter().filter(|filter| filter.drives()).count();
        if drivers == 0 && query.bbox.is_none() && !query.tags.is_empty() {
            return Err(Error::Invalid(
                "a query with only NotExists filters has nothing to drive it; \
                 add an Exists or Equals filter or a bbox"
                    .into(),
            ));
        }
        let cursor = match query.after {
            Some(after) => checked_id(after)?,
            None => (-1, 0), // before every (kind, id)
        };
        let bounds = match query.bbox {
            Some(bbox) => {
                bbox.validate()?;
                let bounds = e7_bounds(bbox);
                if bounds[0] > bounds[1] || bounds[2] > bounds[3] {
                    return Ok(vec![]);
                }
                Some(bounds)
            }
            None => None,
        };
        // Resolve driving keys once. An unknown key cannot match anything,
        // and the filters are ANDed, so the whole query is empty. A NotExists
        // filter on an unknown key matches every object; `matches` handles
        // it by string, so it needs no dictionary id.
        let mut key_ids = Vec::with_capacity(drivers);
        for filter in query.tags.iter().filter(|filter| filter.drives()) {
            match self.keys.get(filter.key()) {
                Some(id) => key_ids.push(*id),
                None => return Ok(vec![]),
            }
        }

        let mut driver = Driver::Scan;
        let mut best = i64::MAX;
        // Estimates only matter when there is a choice to make.
        let choose = drivers + usize::from(bounds.is_some()) > 1;
        let driving = query.tags.iter().filter(|filter| filter.drives());
        for (filter, key) in driving.zip(&key_ids) {
            let estimate = if choose {
                self.estimate(filter, *key)?
            } else {
                0
            };
            // Strictly smaller wins, so ties keep the earlier filter; an
            // Equals filter on the same key range is never larger than Exists.
            if estimate < best {
                best = estimate;
                driver = Driver::Tag(filter, *key);
            }
        }
        if let Some(bounds) = bounds {
            // Tag drivers stream in output order; the bbox driver must collect
            // and sort, so it only wins when strictly more selective.
            if !choose || self.estimate_bbox(bounds)? < best {
                driver = Driver::Bbox;
            }
        }

        let accept = |object: &Object| query.tags.iter().all(|tag| tag.matches(object));
        let mut objects = vec![];
        match driver {
            Driver::Scan => self.scan_all(cursor, query.limit, &mut objects)?,
            Driver::Tag(filter, key) => {
                // Both statements number their parameters alike (?2 is unused
                // by TAG_EXISTS) so one binding serves either.
                let (sql, value) = match filter {
                    TagFilter::Equals(_, value) => (TAG_EQUALS, Some(value.as_str())),
                    TagFilter::Exists(_) => (TAG_EXISTS, None),
                    TagFilter::NotExists(_) => unreachable!("NotExists never drives"),
                };
                let mut statement = self.connection.prepare_cached(sql)?;
                let mut rows = statement.query(params![key, value, cursor.0, cursor.1])?;
                while let Some(row) = rows.next()? {
                    let id = typed(row.get(0)?, row.get(1)?)?;
                    if let Some(bounds) = bounds
                        && !self.intersects(id, bounds)?
                    {
                        continue;
                    }
                    let object = self
                        .get(id)?
                        .ok_or_else(|| corrupt("tag index names a missing object"))?;
                    if accept(&object) {
                        objects.push(object);
                        if objects.len() == query.limit {
                            break;
                        }
                    }
                }
            }
            Driver::Bbox => {
                let bbox = query.bbox.expect("bbox driver implies a bbox");
                let after = query.after;
                for id in self.spatial_candidates(bbox, query.max_candidates)? {
                    if after.is_some_and(|after| id <= after) {
                        continue;
                    }
                    let object = self
                        .get(id)?
                        .ok_or_else(|| corrupt("spatial index names a missing object"))?;
                    if accept(&object) {
                        objects.push(object);
                        if objects.len() == query.limit {
                            break;
                        }
                    }
                }
            }
        }
        Ok(objects)
    }

    /// The coordinates of way `id`'s nodes, resolved in the way's original
    /// order with repeats kept (a closed way ends with its first coordinate
    /// again). `None` when the way is not in the area. A node outside the
    /// area remains a `None` entry, so a renderer cannot accidentally draw a
    /// line across an unresolved gap.
    ///
    /// Cost: one primary-key lookup per node reference, reading only the
    /// coordinate columns (not tags or metadata).
    pub fn way_coordinates(&self, id: WayId) -> Result<Option<Vec<Option<Coordinate>>>> {
        let Some(Object::Way(way)) = self.get(OsmId::Way(id))? else {
            return Ok(None);
        };
        Ok(Some(self.resolve_nodes(&way.nodes)?))
    }

    /// A single label/anchor point for an object, or `None` when the object
    /// is not in the area or none of its geometry is.
    ///
    /// **This is not a guaranteed point-on-surface or a true centroid.** It
    /// is a cheap, deterministic point for placing a marker, a label or a
    /// distance origin. For a concave polygon (an L-shaped building, a
    /// horseshoe) the point can lie outside the polygon; for a multipolygon
    /// it can fall in a hole or between parts. Definition:
    ///
    /// - **Node**: its coordinate.
    /// - **Closed way** (first and last node reference equal, at least two
    ///   references): the arithmetic mean of its *distinct* vertices that are
    ///   in the area (the repeated closing node counts once).
    /// - **Open way**: the point at half the length of the polyline through
    ///   its in-area nodes, in order. Nodes outside the area are skipped, so
    ///   the polyline joins across such gaps. Lengths are measured on a local
    ///   equirectangular projection (longitude scaled by the cosine of the
    ///   mean latitude): accurate enough at street and city scale, not a
    ///   geodesic. A zero-length way yields its first in-area node.
    /// - **Relation**: the arithmetic mean of the representative points of
    ///   its distinct members, counting only members that are in the area and
    ///   have a point. Every member weighs the same, whatever its size.
    ///   Member relations are followed recursively up to
    ///   `MAX_RELATION_DEPTH` levels; a member relation that is already being
    ///   resolved further up (a cycle) contributes nothing.
    ///
    /// Means are taken in degrees with no antimeridian handling (areas never
    /// wrap; see `Bbox`), then rounded to the 1e-7 storage grid.
    pub fn representative_point(&self, id: OsmId) -> Result<Option<Coordinate>> {
        checked_id(id)?;
        let mut path = BTreeSet::new();
        self.point_of(id, 0, &mut path)
    }

    /// Recursive worker for `representative_point`. `path` holds the
    /// relations currently being resolved (the recursion stack), which is
    /// the cycle guard: a relation reached again through its own members is
    /// skipped instead of recursing forever. It is a stack, not a global
    /// visited set, so a relation shared by two sibling members still counts
    /// for both.
    fn point_of(
        &self,
        id: OsmId,
        depth: usize,
        path: &mut BTreeSet<RelationId>,
    ) -> Result<Option<Coordinate>> {
        match id {
            OsmId::Node(node) => self.node_coordinate(node),
            OsmId::Way(_) => {
                let Some(Object::Way(way)) = self.get(id)? else {
                    return Ok(None);
                };
                let closed = way.nodes.len() >= 2 && way.nodes.first() == way.nodes.last();
                if closed {
                    // Distinct by node ID, so the closing repeat (and any
                    // other repeated vertex) is counted once.
                    let mut seen = BTreeSet::new();
                    let distinct: Vec<NodeId> = way
                        .nodes
                        .iter()
                        .copied()
                        .filter(|node| seen.insert(*node))
                        .collect();
                    let points: Vec<Coordinate> = self
                        .resolve_nodes(&distinct)?
                        .into_iter()
                        .flatten()
                        .collect();
                    Ok(mean(&points))
                } else {
                    let points: Vec<Coordinate> = self
                        .resolve_nodes(&way.nodes)?
                        .into_iter()
                        .flatten()
                        .collect();
                    Ok(polyline_midpoint(&points))
                }
            }
            OsmId::Relation(relation) => {
                // Depth 0 is the root object, so a root relation's members are
                // at depth 1 and at most MAX_RELATION_DEPTH nested levels are
                // followed below it.
                if depth > MAX_RELATION_DEPTH || !path.insert(relation) {
                    return Ok(None);
                }
                let result = self.relation_point(id, depth, path);
                // Pop on every exit path, including errors, so the guard only
                // ever describes the live recursion stack.
                path.remove(&relation);
                result
            }
        }
    }

    /// Mean of a relation's distinct members' points; see `point_of`.
    fn relation_point(
        &self,
        id: OsmId,
        depth: usize,
        path: &mut BTreeSet<RelationId>,
    ) -> Result<Option<Coordinate>> {
        let Some(Object::Relation(relation)) = self.get(id)? else {
            return Ok(None);
        };
        let mut seen = BTreeSet::new();
        let mut points = vec![];
        for member in &relation.members {
            // A member listed twice (e.g. under two roles) counts once.
            if !seen.insert(member.id) {
                continue;
            }
            if let Some(point) = self.point_of(member.id, depth + 1, path)? {
                points.push(point);
            }
        }
        Ok(mean(&points))
    }

    pub fn missing_references(&self, id: OsmId) -> Result<Option<Vec<MissingReference>>> {
        let Some(object) = self.get(id)? else {
            return Ok(None);
        };
        let mut missing = vec![];
        for (position, target) in object.references().into_iter().enumerate() {
            if !self.contains(target)? {
                missing.push(MissingReference { position, target });
            }
        }
        Ok(Some(missing))
    }

    /// Traverse a graph with a hard object budget. Typed identities and a visited
    /// set handle repeated members, shared dependencies and cyclic relations.
    pub fn dependencies(&self, root: OsmId, maximum: usize) -> Result<Dependencies> {
        let mut pending = vec![root];
        let mut seen = BTreeSet::new();
        let mut objects = vec![];
        let mut missing = vec![];
        while let Some(id) = pending.pop() {
            if !seen.insert(id) {
                continue;
            }
            if seen.len() > maximum {
                return Err(Error::Invalid("dependency budget exceeded".into()));
            }
            match self.get(id)? {
                Some(object) => {
                    pending.extend(object.references());
                    objects.push(object);
                }
                None => missing.push(id),
            }
        }
        objects.sort_by_key(Object::id);
        missing.sort();
        Ok(Dependencies { objects, missing })
    }

    // ---- internals ----

    /// A node's coordinate only (no tags or metadata decoded).
    fn node_coordinate(&self, id: NodeId) -> Result<Option<Coordinate>> {
        let mut statement = self
            .connection
            .prepare_cached("SELECT lat, lon FROM nodes WHERE id = ?1")?;
        let point = statement
            .query_row([id.0], |row| Ok((row.get(0)?, row.get(1)?)))
            .optional()?;
        Ok(point.map(|(lat_e7, lon_e7)| Coordinate { lat_e7, lon_e7 }))
    }

    /// Coordinates for `nodes` in order; `None` where a node is not in the area.
    fn resolve_nodes(&self, nodes: &[NodeId]) -> Result<Vec<Option<Coordinate>>> {
        nodes
            .iter()
            .map(|node| self.node_coordinate(*node))
            .collect()
    }

    fn contains(&self, id: OsmId) -> Result<bool> {
        let (kind, id) = checked_id(id)?;
        let sql = [
            "SELECT 1 FROM nodes WHERE id = ?1",
            "SELECT 1 FROM ways WHERE id = ?1",
            "SELECT 1 FROM relations WHERE id = ?1",
        ][kind as usize];
        let mut statement = self.connection.prepare_cached(sql)?;
        Ok(statement.exists([id])?)
    }

    fn estimate(&self, filter: &TagFilter, key: i64) -> Result<i64> {
        let (sql, value) = match filter {
            TagFilter::Equals(_, value) => (COUNT_EQUALS, Some(value.as_str())),
            TagFilter::Exists(_) => (COUNT_EXISTS, None),
            TagFilter::NotExists(_) => unreachable!("NotExists is never estimated as a driver"),
        };
        let mut statement = self.connection.prepare_cached(sql)?;
        Ok(statement.query_row(params![key, value, ESTIMATE_CAP], |row| row.get(0))?)
    }

    fn estimate_bbox(&self, [west, east, south, north]: [i32; 4]) -> Result<i64> {
        let mut statement = self.connection.prepare_cached(COUNT_GEO)?;
        Ok(
            statement.query_row(params![west, east, south, north, ESTIMATE_CAP], |row| {
                row.get(0)
            })?,
        )
    }

    /// Whether `id`'s stored bounds intersect `bounds`. Objects without a geo
    /// row (no resolvable members) never match a bbox.
    fn intersects(&self, id: OsmId, [west, east, south, north]: [i32; 4]) -> Result<bool> {
        let (kind, id) = id.parts();
        let mut statement = self
            .connection
            .prepare_cached("SELECT minx, maxx, miny, maxy FROM geo WHERE id = ?1")?;
        let found = statement
            .query_row([packed(kind, id)], |row| {
                Ok([row.get(0)?, row.get(1)?, row.get(2)?, row.get(3)?])
            })
            .optional()?;
        Ok(found.is_some_and(|b: [i32; 4]| {
            b[0] <= east && b[1] >= west && b[2] <= north && b[3] >= south
        }))
    }

    /// Unfiltered listing across namespaces from the cursor.
    fn scan_all(&self, cursor: (i64, i64), limit: usize, out: &mut Vec<Object>) -> Result<()> {
        for kind in [ObjectKind::Node, ObjectKind::Way, ObjectKind::Relation] {
            let kind_code = kind as i64;
            if cursor.0 > kind_code {
                continue;
            }
            let after = if cursor.0 == kind_code { cursor.1 } else { 0 };
            out.extend(self.scan(kind, after as u64, limit - out.len())?);
            if out.len() == limit {
                break;
            }
        }
        Ok(())
    }

    fn read_nodes(&self, sql: &str, parameters: impl rusqlite::Params) -> Result<Vec<Object>> {
        let mut statement = self.connection.prepare_cached(sql)?;
        let mut rows = statement.query(parameters)?;
        let mut objects = vec![];
        while let Some(row) = rows.next()? {
            let id: i64 = row.get(0)?;
            let version: i64 = row.get(3)?;
            let location_version =
                i32::try_from(version).map_err(|_| corrupt("node version out of range"))?;
            let (tags, metadata) = match row.get_ref(4)?.as_blob_or_null()? {
                // Payload carries tags and all metadata but the version, which
                // is the column (shared with payload-less nodes).
                Some(payload) => {
                    let mut reader = Reader::new(payload);
                    let tags = self.decode_tags(&mut reader)?;
                    let metadata = self.decode_metadata(&mut reader, Some(version))?;
                    (tags, Some(metadata))
                }
                None => (Tags::new(), None),
            };
            objects.push(Object::Node(Node {
                id: NodeId(id),
                coordinate: Coordinate {
                    lat_e7: row.get(1)?,
                    lon_e7: row.get(2)?,
                },
                location_version,
                tags,
                metadata,
            }));
        }
        Ok(objects)
    }

    fn read_ways(&self, sql: &str, parameters: impl rusqlite::Params) -> Result<Vec<Object>> {
        let mut statement = self.connection.prepare_cached(sql)?;
        let mut rows = statement.query(parameters)?;
        let mut objects = vec![];
        while let Some(row) = rows.next()? {
            let mut refs = Reader::new(row.get_ref(1)?.as_blob()?);
            let mut nodes = vec![];
            let mut previous = 0i64;
            while !refs.is_done() {
                previous = previous.wrapping_add(refs.svar()?);
                nodes.push(NodeId(previous));
            }
            let mut payload = Reader::new(row.get_ref(2)?.as_blob()?);
            let tags = self.decode_tags(&mut payload)?;
            let metadata = self.decode_metadata(&mut payload, None)?;
            objects.push(Object::Way(Way {
                id: WayId(row.get(0)?),
                nodes,
                tags,
                metadata: Some(metadata),
            }));
        }
        Ok(objects)
    }

    fn read_relations(&self, sql: &str, parameters: impl rusqlite::Params) -> Result<Vec<Object>> {
        let mut statement = self.connection.prepare_cached(sql)?;
        let mut rows = statement.query(parameters)?;
        let mut objects = vec![];
        while let Some(row) = rows.next()? {
            let mut encoded = Reader::new(row.get_ref(1)?.as_blob()?);
            let mut members = vec![];
            let mut previous = 0i64;
            while !encoded.is_done() {
                let role_and_kind = encoded.uvar()?;
                previous = previous.wrapping_add(encoded.svar()?);
                let role = self.word(role_and_kind >> 2)?.to_owned();
                members.push(Member {
                    id: typed((role_and_kind & 3) as i64, previous)?,
                    role,
                });
            }
            let mut payload = Reader::new(row.get_ref(2)?.as_blob()?);
            let tags = self.decode_tags(&mut payload)?;
            let metadata = self.decode_metadata(&mut payload, None)?;
            objects.push(Object::Relation(Relation {
                id: RelationId(row.get(0)?),
                members,
                tags,
                metadata: Some(metadata),
            }));
        }
        Ok(objects)
    }

    fn word(&self, id: u64) -> Result<&str> {
        usize::try_from(id)
            .ok()
            .and_then(|id| self.dict.get(id))
            .map(String::as_str)
            .ok_or_else(|| corrupt("dictionary id out of range"))
    }

    fn decode_tags(&self, reader: &mut Reader) -> Result<Tags> {
        let count = reader.uvar()?;
        let mut tags = Tags::new();
        for _ in 0..count {
            let key = self.word(reader.uvar()?)?.to_owned();
            let value = reader.text()?.to_owned();
            tags.insert(key, value);
        }
        Ok(tags)
    }

    /// `version` is `Some` for nodes, whose version lives in a column.
    fn decode_metadata(&self, reader: &mut Reader, version: Option<i64>) -> Result<Metadata> {
        let narrow = |value: u64, what: &str| {
            u32::try_from(value).map_err(|_| corrupt(&format!("{what} out of range")))
        };
        let version = match version {
            Some(version) => u32::try_from(version).map_err(|_| corrupt("version out of range"))?,
            None => narrow(reader.uvar()?, "version")?,
        };
        let timestamp = reader.uvar()?;
        let changeset = narrow(reader.uvar()?, "changeset")?;
        let uid = narrow(reader.uvar()?, "uid")?;
        Ok(Metadata {
            version,
            timestamp,
            changeset,
            uid,
            user: self.users.get(&uid).cloned().unwrap_or_default(),
        })
    }
}

/// Arithmetic mean in degrees, rounded to the e7 grid; `None` for no points.
/// Summing i32 e7 values in f64 is exact far beyond any realistic vertex
/// count, so the only rounding is the final one.
fn mean(points: &[Coordinate]) -> Option<Coordinate> {
    if points.is_empty() {
        return None;
    }
    let count = points.len() as f64;
    let lat = points.iter().map(|p| p.lat_e7 as f64).sum::<f64>() / count;
    let lon = points.iter().map(|p| p.lon_e7 as f64).sum::<f64>() / count;
    // A mean of valid coordinates is itself within WGS84 bounds, so the
    // rounded values fit the i32 storage range.
    Some(Coordinate {
        lat_e7: lat.round() as i32,
        lon_e7: lon.round() as i32,
    })
}

/// The point at half the length of the polyline through `points`, measured
/// on a local equirectangular projection (x = longitude scaled by the cosine
/// of the mean latitude, y = latitude). `None` for no points; the first point
/// when the total length is zero (one point, or all points equal).
fn polyline_midpoint(points: &[Coordinate]) -> Option<Coordinate> {
    let first = *points.first()?;
    let mean_lat = points.iter().map(|p| p.lat()).sum::<f64>() / points.len() as f64;
    let scale = mean_lat.to_radians().cos();
    // Segment lengths in projected degree units; only ratios matter, so no
    // conversion to metres is needed.
    let length = |a: Coordinate, b: Coordinate| {
        let dx = (b.lon() - a.lon()) * scale;
        let dy = b.lat() - a.lat();
        (dx * dx + dy * dy).sqrt()
    };
    let total: f64 = points.windows(2).map(|pair| length(pair[0], pair[1])).sum();
    if total == 0.0 {
        return Some(first);
    }
    let mut remaining = total / 2.0;
    for pair in points.windows(2) {
        let (a, b) = (pair[0], pair[1]);
        let segment = length(a, b);
        if segment > 0.0 && remaining <= segment {
            // Linear interpolation along this segment; t is in [0, 1].
            let t = remaining / segment;
            let lat = a.lat_e7 as f64 + t * (b.lat_e7 as f64 - a.lat_e7 as f64);
            let lon = a.lon_e7 as f64 + t * (b.lon_e7 as f64 - a.lon_e7 as f64);
            return Some(Coordinate {
                lat_e7: lat.round() as i32,
                lon_e7: lon.round() as i32,
            });
        }
        remaining -= segment;
    }
    // Floating-point leftovers can step just past the last segment.
    points.last().copied()
}

fn typed(kind: i64, id: i64) -> Result<OsmId> {
    Ok(match kind {
        NODE => OsmId::Node(NodeId(id)),
        WAY => OsmId::Way(WayId(id)),
        RELATION => OsmId::Relation(RelationId(id)),
        _ => return Err(corrupt("invalid object kind")),
    })
}

fn unpack(packed: i64) -> Result<OsmId> {
    typed(packed & 3, packed >> 2)
}

fn checked_id(id: OsmId) -> Result<(i64, i64)> {
    let (kind, id) = id.parts();
    if id <= 0 {
        return Err(Error::Invalid("snapshot IDs must be positive".into()));
    }
    Ok((kind, id))
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Query plans for the tag drivers must be primary-key searches on
    /// `tag_index`, never table scans; `Equals` must also avoid a sort.
    #[test]
    fn tag_drivers_use_the_index() {
        let directory = tempfile::tempdir().unwrap();
        let area = directory.path().join("area.sqlite");
        let fixture = concat!(env!("CARGO_MANIFEST_DIR"), "/tests/fixtures/snapshot.osm");
        import_area(fixture, &area, ImportOptions::default()).unwrap();
        let store = Store::open(&area).unwrap();
        let plan = |sql: &str| -> String {
            let mut statement = store
                .connection
                .prepare(&format!("EXPLAIN QUERY PLAN {sql}"))
                .unwrap();
            let rows = statement
                .query_map(params![0, "cafe", -1, 0], |row| row.get::<_, String>(3))
                .unwrap();
            rows.map(|row| row.unwrap()).collect::<Vec<_>>().join("; ")
        };
        let equals = plan(TAG_EQUALS);
        assert!(
            equals.contains("SEARCH tag_index USING PRIMARY KEY"),
            "{equals}"
        );
        assert!(!equals.contains("TEMP B-TREE"), "{equals}");
        let exists = plan(TAG_EXISTS);
        assert!(
            exists.contains("SEARCH tag_index USING PRIMARY KEY"),
            "{exists}"
        );
        assert!(!exists.contains("SCAN"), "{exists}");
    }
}
