use crate::{ffi, *};
use serde::{Deserialize, Serialize};
use std::{
    collections::{BTreeSet, HashSet},
    marker::PhantomData,
    os::raw::c_void,
    path::{Path, PathBuf},
    ptr::NonNull,
    rc::Rc,
    sync::{Mutex, OnceLock},
};

// LMDB forbids opening the same environment twice in one process. Register a
// canonical path and keep a lease for the entire native handle's lifetime.
fn environments() -> &'static Mutex<HashSet<PathBuf>> {
    static ENVIRONMENTS: OnceLock<Mutex<HashSet<PathBuf>>> = OnceLock::new();
    ENVIRONMENTS.get_or_init(|| Mutex::new(HashSet::new()))
}
struct Lease(PathBuf);
impl Lease {
    fn acquire(path: &Path) -> Result<Self> {
        let path = path.canonicalize()?;
        let mut environments = environments()
            .lock()
            .map_err(|_| Error::Native("environment registry poisoned".into()))?;
        if !environments.insert(path.clone()) {
            return Err(Error::Invalid(
                "this area is already open; reuse its Store".into(),
            ));
        }
        Ok(Self(path))
    }
}
impl Drop for Lease {
    fn drop(&mut self) {
        if let Ok(mut registry) = environments().lock() {
            registry.remove(&self.0);
        }
    }
}

/// Owns one immutable OSMExpress area. Use it on one worker thread; the `Rc`
/// marker deliberately prevents moving or sharing the handle across threads.
/// Objects returned from lookup/query own their data and outlive this handle.
pub struct Store {
    handle: NonNull<c_void>,
    _lease: Lease,
    _thread: PhantomData<Rc<()>>,
}
#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
#[serde(default)]
pub struct ImportOptions {
    /// Maximum database mapping. A full map produces an error, never an abort.
    pub map_size: usize,
    /// Maximum pairs per sorter (five sorters, 16 bytes per pair).
    /// This is a sorting budget, not a cap on the entire import's memory.
    pub sort_pairs: usize,
    pub preserve_untagged_metadata: bool,
}
impl Default for ImportOptions {
    fn default() -> Self {
        Self {
            map_size: 1024 * 1024 * 1024,
            sort_pairs: 65536,
            preserve_untagged_metadata: true,
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
    pub database_bytes: u64,
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
    /// Spatial queries use OSMExpress's node-cell/parent selection. They can
    /// omit crossing or containing geometries without selected member nodes.
    pub bbox: Option<Bbox>,
    pub after: Option<OsmId>,
    pub limit: usize,
    /// Per namespace; bound spatial candidates before they enter Rust.
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

impl Store {
    pub fn open(path: impl AsRef<Path>) -> Result<Self> {
        Self::open_with_map_size(path, ImportOptions::default().map_size)
    }
    pub fn open_with_map_size(path: impl AsRef<Path>, map_size: usize) -> Result<Self> {
        let lease = Lease::acquire(path.as_ref())?;
        let path = ffi::path(&lease.0)?;
        let mut handle = std::ptr::null_mut();
        let mut error = ffi::NativeString::empty();
        // SAFETY: pointers refer to initialized output slots and a live CString.
        let code = unsafe { ffi::osmx_open(path.as_ptr(), map_size, &mut handle, &mut error.0) };
        ffi::status(code, &error)?;
        let handle =
            NonNull::new(handle).ok_or_else(|| Error::Native("null store handle".into()))?;
        Ok(Self {
            handle,
            _lease: lease,
            _thread: PhantomData,
        })
    }
    pub fn get(&self, id: OsmId) -> Result<Option<Object>> {
        let (kind, id) = checked_id(id)?;
        let mut output = ffi::NativeString::empty();
        let mut error = ffi::NativeString::empty();
        // SAFETY: handle stays live; native strings are freed by their RAII wrappers.
        let code =
            unsafe { ffi::osmx_get(self.handle.as_ptr(), kind, id, &mut output.0, &mut error.0) };
        if !ffi::status(code, &error)? {
            return Ok(None);
        }
        Ok(Some(serde_json::from_str(output.text()?)?))
    }
    pub fn counts(&self) -> Result<Counts> {
        let mut output = ffi::NativeString::empty();
        let mut error = ffi::NativeString::empty();
        // SAFETY: handle and output slots stay live through this call.
        let code = unsafe { ffi::osmx_stats(self.handle.as_ptr(), &mut output.0, &mut error.0) };
        ffi::status(code, &error)?;
        Ok(serde_json::from_str(output.text()?)?)
    }
    pub fn scan(&self, kind: ObjectKind, after: u64, limit: usize) -> Result<Vec<Object>> {
        let mut output = ffi::NativeString::empty();
        let mut error = ffi::NativeString::empty();
        // SAFETY: handle and output slots stay live through this call.
        let code = unsafe {
            ffi::osmx_scan(
                self.handle.as_ptr(),
                kind as i32,
                after,
                limit,
                &mut output.0,
                &mut error.0,
            )
        };
        ffi::status(code, &error)?;
        Ok(serde_json::from_str(output.text()?)?)
    }
    pub fn spatial_candidates(&self, bbox: Bbox, maximum: usize) -> Result<Vec<OsmId>> {
        bbox.validate()?;
        let mut output = ffi::NativeString::empty();
        let mut error = ffi::NativeString::empty();
        // SAFETY: arguments are values; handle and outputs live through this call.
        let code = unsafe {
            ffi::osmx_candidates(
                self.handle.as_ptr(),
                bbox.west,
                bbox.south,
                bbox.east,
                bbox.north,
                maximum,
                &mut output.0,
                &mut error.0,
            )
        };
        ffi::status(code, &error)?;
        Ok(serde_json::from_str(output.text()?)?)
    }
    /// Query raw tags with stable `(kind, ID)` ordering and keyset pagination.
    /// Tag-only queries scan bounded pages; no global tag index exists yet.
    pub fn query(&self, query: &Query) -> Result<Vec<Object>> {
        if !(1..=10000).contains(&query.limit) {
            return Err(Error::Invalid("query limit must be 1..=10000".into()));
        }
        if let Some(after) = query.after {
            checked_id(after)?;
        }
        let matches = |object: &Object| query.tags.iter().all(|tag| tag.matches(object));
        let mut objects = vec![];
        if let Some(bbox) = query.bbox {
            for id in self.spatial_candidates(bbox, query.max_candidates)? {
                if query.after.is_some_and(|after| id <= after) {
                    continue;
                }
                if let Some(object) = self.get(id)? {
                    // S2 cells cover the rectangle conservatively. Point queries
                    // can be filtered exactly without assembling way polygons.
                    if let Object::Node(node) = &object {
                        let point = node.coordinate;
                        if point.lon() < bbox.west
                            || point.lon() > bbox.east
                            || point.lat() < bbox.south
                            || point.lat() > bbox.north
                        {
                            continue;
                        }
                    }
                    if matches(&object) {
                        objects.push(object);
                    }
                    if objects.len() == query.limit {
                        break;
                    }
                }
            }
        } else {
            for kind in [ObjectKind::Node, ObjectKind::Way, ObjectKind::Relation] {
                let mut after = 0;
                if let Some(cursor) = query.after {
                    let (cursor_kind, cursor_id) = cursor.parts();
                    if cursor_kind > kind as i64 {
                        continue;
                    }
                    if cursor_kind == kind as i64 {
                        after = cursor_id as u64;
                    }
                }
                loop {
                    let page = self.scan(kind, after, 256)?;
                    if page.is_empty() {
                        break;
                    }
                    for object in page {
                        after = object.id().parts().1 as u64;
                        if query.after.is_some_and(|cursor| object.id() <= cursor) {
                            continue;
                        }
                        if matches(&object) {
                            objects.push(object);
                        }
                        if objects.len() == query.limit {
                            return Ok(objects);
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
        let coordinates = way
            .nodes
            .into_iter()
            .map(|id| {
                Ok(match self.get(OsmId::Node(id))? {
                    Some(Object::Node(node)) => Some(node.coordinate),
                    _ => None,
                })
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
            if self.get(target)?.is_none() {
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
}
impl Drop for Store {
    fn drop(&mut self) {
        // SAFETY: this unique handle has not been closed; the lease drops after it.
        unsafe { ffi::osmx_close(self.handle.as_ptr()) }
    }
}
#[derive(Debug, Clone, Copy)]
#[repr(i32)]
pub enum ObjectKind {
    Node = 0,
    Way = 1,
    Relation = 2,
}
fn checked_id(id: OsmId) -> Result<(i32, u64)> {
    let (kind, id) = id.parts();
    if id <= 0 {
        return Err(Error::Invalid("snapshot IDs must be positive".into()));
    }
    Ok((kind as i32, id as u64))
}

/// Import into a private sibling directory, validate the result, sync it, then
/// publish it with an atomic rename. Failure leaves the old destination intact.
/// An already-open Store keeps reading its previous immutable snapshot; drop it
/// and reopen after publication. Publication is independent of edit journals.
pub fn import_area(
    input: impl AsRef<Path>,
    destination: impl AsRef<Path>,
    options: ImportOptions,
) -> Result<ImportReport> {
    let destination = destination.as_ref();
    let parent = destination
        .parent()
        .filter(|p| !p.as_os_str().is_empty())
        .unwrap_or(Path::new("."));
    if destination.file_name().is_none() {
        return Err(Error::Invalid("destination must be a file".into()));
    }
    let staging = tempfile::Builder::new()
        .prefix(".osmx-stage-")
        .tempdir_in(parent)?;
    let staged_path = staging.path().join("area.osmx");
    let input = ffi::path(input.as_ref())?;
    let output = ffi::path(&staged_path)?;
    let mut error = ffi::NativeString::empty();
    // SAFETY: CStrings and error output live through the synchronous import.
    let code = unsafe {
        ffi::osmx_import(
            input.as_ptr(),
            output.as_ptr(),
            options.map_size,
            options.sort_pairs,
            i32::from(options.preserve_untagged_metadata),
            &mut error.0,
        )
    };
    ffi::status(code, &error)?;
    let store = Store::open_with_map_size(&staged_path, options.map_size)?;
    let counts = store.counts()?;
    drop(store);
    let file = std::fs::File::open(&staged_path)?;
    file.sync_all()?;
    let database_bytes = file.metadata()?.len();
    drop(file);
    std::fs::rename(&staged_path, destination)?;
    // POSIX directory sync makes the new directory entry durable after rename.
    #[cfg(unix)]
    std::fs::File::open(parent)?.sync_all()?;
    Ok(ImportReport {
        counts,
        database_bytes,
    })
}
