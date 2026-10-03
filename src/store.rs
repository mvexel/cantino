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
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum TagFilter {
    Exists(String),
    Equals(String, String),
}
impl TagFilter {
    fn key(&self) -> &str {
        match self {
            Self::Exists(key) | Self::Equals(key, _) => key,
        }
    }
    fn matches(&self, object: &Object) -> bool {
        match self {
            Self::Exists(key) => object.tag(key).is_some(),
            Self::Equals(key, value) => object.tag(key) == Some(value.as_str()),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default)]
pub struct Query {
    /// All filters must match; missing and empty tag values are distinct.
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
            return Err(Error::Invalid(format!(
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

    pub fn get(&self, id: OsmId) -> Result<Option<Object>> {
        let (kind, id) = checked_id(id)?;
        match kind {
            NODE => self.read_nodes(NODE_BY_ID, params![id]),
            WAY => self.read_ways(WAY_BY_ID, params![id]),
            _ => self.read_relations(RELATION_BY_ID, params![id]),
        }
        .map(|mut objects| objects.pop())
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
    /// - Without any filter, objects are read in primary-key order.
    pub fn query(&self, query: &Query) -> Result<Vec<Object>> {
        if !(1..=10000).contains(&query.limit) {
            return Err(Error::Invalid("query limit must be 1..=10000".into()));
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
        // Resolve keys once. An unknown key cannot match anything, and the
        // filters are ANDed, so the whole query is empty.
        let mut key_ids = Vec::with_capacity(query.tags.len());
        for filter in &query.tags {
            match self.keys.get(filter.key()) {
                Some(id) => key_ids.push(*id),
                None => return Ok(vec![]),
            }
        }

        let mut driver = Driver::Scan;
        let mut best = i64::MAX;
        // Estimates only matter when there is a choice to make.
        let choose = query.tags.len() + usize::from(bounds.is_some()) > 1;
        for (filter, key) in query.tags.iter().zip(&key_ids) {
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

    /// Resolve in original order. A missing node remains `None`, so a renderer
    /// cannot accidentally draw a line across an unresolved gap.
    pub fn way_coordinates(&self, id: WayId) -> Result<Option<Vec<Option<Coordinate>>>> {
        let Some(Object::Way(way)) = self.get(OsmId::Way(id))? else {
            return Ok(None);
        };
        let mut statement = self
            .connection
            .prepare_cached("SELECT lat, lon FROM nodes WHERE id = ?1")?;
        let coordinates = way
            .nodes
            .into_iter()
            .map(|node| {
                let point = statement
                    .query_row([node.0], |row| Ok((row.get(0)?, row.get(1)?)))
                    .optional()?;
                Ok(point.map(|(lat_e7, lon_e7)| Coordinate { lat_e7, lon_e7 }))
            })
            .collect::<Result<_>>()?;
        Ok(Some(coordinates))
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
