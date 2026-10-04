//! Small language-neutral ABI for a platform adapter to call the Rust core.
//! Handles are confined to their creating thread. Buffers have explicit ownership;
//! errors and panics return status codes instead of unwinding through C/Swift/JNI.
//!
//! Error reporting: a failing call returns -1 and writes an owned message to
//! `*error`; its category (`ErrorKind` as a `CANTINO_ERROR_*` code) is kept
//! per thread and read with `cantino_last_error_code`, errno-style. The
//! category travels out of band so the 0.1 contract ("-1 is an error") stays
//! intact for existing callers.
use crate::*;
use std::{
    cell::Cell,
    ffi::{CStr, CString},
    os::raw::c_char,
    panic::{AssertUnwindSafe, catch_unwind},
    thread::ThreadId,
};

pub struct CantinoStore {
    store: Store,
    thread: ThreadId,
}
// `CANTINO_ERROR_*` code of the last ABI call on this thread: 0 after a
// success, the error's `ErrorKind` after a failure. Thread-local because
// every handle is thread-confined anyway and the adapter reads it right
// after the failing call on the same thread; no lock, no cross-thread races.
thread_local! {
    // The initializer is const already; Clippy 1.99 still reports it on the
    // Android targets (std expands thread_local! differently there).
    #[allow(clippy::missing_const_for_thread_local)]
    static LAST_ERROR: Cell<i32> = const { Cell::new(0) };
}

/// The `CANTINO_ERROR_*` code of the most recent ABI call made on the
/// calling thread: 0 (`CANTINO_ERROR_NONE`) if it succeeded, otherwise the
/// failure's category. Only meaningful right after a call returned -1;
/// any later call on the thread overwrites it.
#[unsafe(no_mangle)]
pub extern "C" fn cantino_last_error_code() -> i32 {
    LAST_ERROR.with(Cell::get)
}

/// Runs one ABI call body: catches panics, turns an `Err` into status -1
/// plus an owned message in `*error`, and records the error's category for
/// `cantino_last_error_code`. Every exported function that can fail goes
/// through here, so classification happens in exactly one place.
pub(crate) fn protect(error: *mut *mut c_char, function: impl FnOnce() -> Result<i32>) -> i32 {
    // SAFETY: the ABI contract requires error to be NULL or a writable slot.
    if !error.is_null() {
        unsafe {
            *error = std::ptr::null_mut();
        }
    }
    let result = catch_unwind(AssertUnwindSafe(function));
    let (kind, message) = match result {
        Ok(Ok(code)) => {
            LAST_ERROR.with(|last| last.set(0));
            return code;
        }
        Ok(Err(error)) => (error.kind(), error.to_string()),
        // A panic is a bug in the core by definition.
        Err(_) => (ErrorKind::Internal, "Rust core panicked".into()),
    };
    LAST_ERROR.with(|last| last.set(kind.code()));
    if !error.is_null() {
        let message = CString::new(message.replace('\0', "\\0")).expect("NUL escaped");
        // SAFETY: the caller owns the allocated string and frees it through this ABI.
        unsafe {
            *error = message.into_raw();
        }
    }
    -1
}
pub(crate) unsafe fn text<'a>(input: *const c_char) -> Result<&'a str> {
    if input.is_null() {
        return Err(Error::Invalid("null string argument".into()));
    }
    // SAFETY: the caller supplies a live NUL-terminated string for this call.
    unsafe { CStr::from_ptr(input) }
        .to_str()
        .map_err(|_| Error::Invalid("argument must be UTF-8".into()))
}
unsafe fn handle<'a>(input: *mut CantinoStore) -> Result<&'a CantinoStore> {
    // SAFETY: the caller supplies NULL or a live handle returned by open.
    let handle =
        unsafe { input.as_ref() }.ok_or_else(|| Error::Invalid("null store handle".into()))?;
    if handle.thread != std::thread::current().id() {
        return Err(Error::WrongThread(
            "store belongs to a different thread".into(),
        ));
    }
    Ok(handle)
}
pub(crate) unsafe fn output(slot: *mut *mut c_char, value: &impl serde::Serialize) -> Result<i32> {
    if slot.is_null() {
        return Err(Error::Invalid("null output slot".into()));
    }
    // Serializing our own result types cannot fail and serde_json escapes
    // NUL, so both failures would be bugs (Internal), not caller errors.
    let json = serde_json::to_string(value)
        .map_err(|error| Error::Internal(format!("serializing a result: {error}")))?;
    let json = CString::new(json).map_err(|_| Error::Internal("NUL in result JSON".into()))?;
    // SAFETY: the caller supplies a writable pointer slot, and now owns this buffer.
    unsafe {
        *slot = json.into_raw();
    }
    Ok(0)
}

/// # Safety
/// `path` must be NUL-terminated; output pointers must be NULL or writable slots.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn cantino_open(
    path: *const c_char,
    store: *mut *mut CantinoStore,
    error: *mut *mut c_char,
) -> i32 {
    if !store.is_null() {
        unsafe {
            *store = std::ptr::null_mut();
        }
    }
    protect(error, || {
        if store.is_null() {
            return Err(Error::Invalid("null store output".into()));
        }
        let path = unsafe { text(path)? };
        let handle = Box::new(CantinoStore {
            store: Store::open(path)?,
            thread: std::thread::current().id(),
        });
        unsafe {
            *store = Box::into_raw(handle);
        }
        Ok(0)
    })
}
/// # Safety
/// `store` must be a live handle returned by open; call close once on its owner thread.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn cantino_close(store: *mut CantinoStore, error: *mut *mut c_char) -> i32 {
    protect(error, || {
        unsafe {
            handle(store)?;
            drop(Box::from_raw(store));
        }
        Ok(0)
    })
}
/// # Safety
/// `value` must be NULL or a buffer returned by this framework ABI, freed once.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn cantino_free(value: *mut c_char) {
    if !value.is_null() {
        unsafe {
            drop(CString::from_raw(value));
        }
    }
}
/// # Safety
/// Handle and output pointers must obey the ownership contract in the header.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn cantino_get(
    store: *mut CantinoStore,
    kind: i32,
    id: i64,
    out: *mut *mut c_char,
    error: *mut *mut c_char,
) -> i32 {
    if !out.is_null() {
        unsafe {
            *out = std::ptr::null_mut();
        }
    }
    protect(error, || {
        let id = typed_id(kind, id)?;
        let object = unsafe { handle(store)? }.store.get(id)?;
        match object {
            Some(object) => unsafe { output(out, &object) },
            None => Ok(1),
        }
    })
}

/// The ABI's numeric kind codes (node=0, way=1, relation=2) to a typed ID.
fn typed_id(kind: i32, id: i64) -> Result<OsmId> {
    Ok(match kind {
        0 => OsmId::Node(NodeId(id)),
        1 => OsmId::Way(WayId(id)),
        2 => OsmId::Relation(RelationId(id)),
        _ => return Err(Error::Invalid("invalid object kind".into())),
    })
}

/// Batch lookup: `request` is a JSON array of IDs (`[{"type":"node","id":1},
/// ...]`, the cursor shape); writes a JSON array with one entry per ID in
/// input order, the object or `null` when it is not in the area. At most
/// `MAX_BATCH` IDs; more is an error.
///
/// # Safety
/// `request` must be a live NUL-terminated UTF-8 JSON string for this call;
/// handle and output pointers obey the header contract.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn cantino_get_many(
    store: *mut CantinoStore,
    request: *const c_char,
    out: *mut *mut c_char,
    error: *mut *mut c_char,
) -> i32 {
    if !out.is_null() {
        unsafe {
            *out = std::ptr::null_mut();
        }
    }
    protect(error, || {
        let ids: Vec<OsmId> = serde_json::from_str(unsafe { text(request)? })?;
        let objects = unsafe { handle(store)? }.store.get_many(&ids)?;
        unsafe { output(out, &objects) }
    })
}

/// Way coordinates as one flat JSON array `[lat_e7, lon_e7, lat_e7, lon_e7,
/// ...]`, two numbers per node reference in way order (repeats kept), with
/// `null, null` for a node outside the area. A flat array of numbers instead
/// of one object per node keeps the buffer small and the adapter's parse a
/// single pass. Returns 1 (no JSON) when the way is not in the area.
///
/// # Safety
/// Handle and output pointers obey the header contract.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn cantino_way_coordinates(
    store: *mut CantinoStore,
    id: i64,
    out: *mut *mut c_char,
    error: *mut *mut c_char,
) -> i32 {
    if !out.is_null() {
        unsafe {
            *out = std::ptr::null_mut();
        }
    }
    protect(error, || {
        let store = &unsafe { handle(store)? }.store;
        if id <= 0 {
            return Err(Error::Invalid("snapshot IDs must be positive".into()));
        }
        let Some(coordinates) = store.way_coordinates(WayId(id))? else {
            return Ok(1);
        };
        let flat: Vec<Option<i32>> = coordinates
            .iter()
            .flat_map(|point| match point {
                Some(point) => [Some(point.lat_e7), Some(point.lon_e7)],
                None => [None, None],
            })
            .collect();
        unsafe { output(out, &flat) }
    })
}

/// Representative (label/anchor) point of an object: writes
/// `{"lat_e7":..,"lon_e7":..}`, or returns 1 (no JSON) when the object is not
/// in the area or none of its geometry is. See `Store::representative_point`
/// for the definition; it is not a guaranteed point-on-surface.
///
/// # Safety
/// Handle and output pointers obey the header contract.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn cantino_representative_point(
    store: *mut CantinoStore,
    kind: i32,
    id: i64,
    out: *mut *mut c_char,
    error: *mut *mut c_char,
) -> i32 {
    if !out.is_null() {
        unsafe {
            *out = std::ptr::null_mut();
        }
    }
    protect(error, || {
        let id = typed_id(kind, id)?;
        match unsafe { handle(store)? }.store.representative_point(id)? {
            Some(point) => unsafe { output(out, &point) },
            None => Ok(1),
        }
    })
}
/// # Safety
/// `request` must be a live NUL-terminated UTF-8 JSON query for this call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn cantino_query(
    store: *mut CantinoStore,
    request: *const c_char,
    out: *mut *mut c_char,
    error: *mut *mut c_char,
) -> i32 {
    if !out.is_null() {
        unsafe {
            *out = std::ptr::null_mut();
        }
    }
    protect(error, || {
        let query: Query = serde_json::from_str(unsafe { text(request)? })?;
        let objects = unsafe { handle(store)? }.store.query(&query)?;
        unsafe { output(out, &objects) }
    })
}
/// # Safety
/// Paths must be live NUL-terminated UTF-8 strings; output slots must be writable.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn cantino_import(
    input: *const c_char,
    destination: *const c_char,
    options: *const c_char,
    out: *mut *mut c_char,
    error: *mut *mut c_char,
) -> i32 {
    if !out.is_null() {
        unsafe {
            *out = std::ptr::null_mut();
        }
    }
    protect(error, || {
        if out.is_null() {
            return Err(Error::Invalid("null import report output".into()));
        }
        let options = if options.is_null() {
            ImportOptions::default()
        } else {
            serde_json::from_str(unsafe { text(options)? })?
        };
        let report = import_area(
            unsafe { text(input)? },
            unsafe { text(destination)? },
            options,
        )?;
        unsafe { output(out, &report) }
    })
}

// SliceOSM protocol helpers. These are pure functions (no I/O, no handles, no
// thread confinement): the platform adapter performs HTTP itself and asks the
// core how to build requests and how to read responses, so the protocol is
// implemented once for Android and iOS. `base` may be NULL for the public
// service; otherwise it is an http(s) base URL such as a local test server.

fn slice_service(base: *const c_char) -> Result<slice::Service> {
    if base.is_null() {
        Ok(slice::Service::default())
    } else {
        slice::Service::new(unsafe { text(base)? })
    }
}

/// Writes `{"url": submit URL, "body": JSON text to POST}`.
///
/// # Safety
/// `base` is NULL or a live UTF-8 string; `bbox` (`{"west","south","east","north"}`)
/// and `name` are live UTF-8 strings; output slots obey the header contract.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn cantino_slice_job_request(
    base: *const c_char,
    bbox: *const c_char,
    name: *const c_char,
    out: *mut *mut c_char,
    error: *mut *mut c_char,
) -> i32 {
    if !out.is_null() {
        unsafe {
            *out = std::ptr::null_mut();
        }
    }
    protect(error, || {
        let service = slice_service(base)?;
        let bbox: Bbox = serde_json::from_str(unsafe { text(bbox)? })?;
        let request = slice::job_request(&service, unsafe { text(name)? }, bbox)?;
        unsafe { output(out, &request) }
    })
}

/// Parses a submit response body (the job UUID) and writes
/// `{"job_id","status_url","download_url"}`. Also used to re-derive the URLs
/// of a job ID the adapter persisted, since the ID is re-validated here.
///
/// # Safety
/// As for `cantino_slice_job_request`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn cantino_slice_job(
    base: *const c_char,
    response: *const c_char,
    out: *mut *mut c_char,
    error: *mut *mut c_char,
) -> i32 {
    if !out.is_null() {
        unsafe {
            *out = std::ptr::null_mut();
        }
    }
    protect(error, || {
        let service = slice_service(base)?;
        let urls = slice::job_urls(&service, unsafe { text(response)? })?;
        unsafe { output(out, &urls) }
    })
}

/// Reads a job status document and writes
/// `{"complete":bool,"fraction":0..1|null,"size_bytes":n|null,"timestamp":"..."|null}`.
///
/// # Safety
/// `status` is a live UTF-8 string; output slots obey the header contract.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn cantino_slice_progress(
    status: *const c_char,
    out: *mut *mut c_char,
    error: *mut *mut c_char,
) -> i32 {
    if !out.is_null() {
        unsafe {
            *out = std::ptr::null_mut();
        }
    }
    protect(error, || {
        let progress: slice::Progress = serde_json::from_str(unsafe { text(status)? })?;
        unsafe { output(out, &progress.summary()) }
    })
}

#[cfg(test)]
mod tests {
    //! The error category travels with every failure (`cantino_last_error_code`).
    //! The per-variant classification is in `tests/errors.rs`; this checks
    //! the ABI side: the code is set per thread, reset by a success, and the
    //! two kinds only the ABI can produce (WrongThread, Internal) appear.
    use super::*;
    use std::ptr::null_mut;

    fn c(s: &str) -> CString {
        CString::new(s).unwrap()
    }

    /// Calls an ABI function, frees both buffers, returns (status, code).
    fn status_and_code(
        function: impl FnOnce(*mut *mut c_char, *mut *mut c_char) -> i32,
    ) -> (i32, i32) {
        let (mut out, mut error) = (null_mut(), null_mut());
        let status = function(&mut out, &mut error);
        let code = cantino_last_error_code();
        unsafe {
            cantino_free(out);
            cantino_free(error);
        }
        (status, code)
    }

    fn open(path: &str) -> (i32, i32, *mut CantinoStore) {
        let path = c(path);
        let mut store = null_mut();
        let (status, code) =
            status_and_code(|_, error| unsafe { cantino_open(path.as_ptr(), &mut store, error) });
        (status, code, store)
    }

    #[test]
    fn every_failure_carries_its_category_per_thread() {
        let directory = tempfile::tempdir().unwrap();
        let area = directory.path().join("area.sqlite");
        let fixture = concat!(env!("CARGO_MANIFEST_DIR"), "/tests/fixtures/snapshot.osm");
        import_area(fixture, &area, ImportOptions::default()).unwrap();

        // Io: missing file. InvalidFile: the XML fixture is no area.
        let missing = directory.path().join("missing.sqlite");
        assert_eq!(open(missing.to_str().unwrap()).1, ErrorKind::Io.code());
        assert_eq!(open(fixture).1, ErrorKind::InvalidFile.code());
        // NULL path argument: InvalidArgument.
        let mut store = null_mut();
        let (status, code) = status_and_code(|_, error| unsafe {
            cantino_open(std::ptr::null(), &mut store, error)
        });
        assert_eq!((status, code), (-1, ErrorKind::InvalidArgument.code()));

        let (status, code, store) = open(area.to_str().unwrap());
        assert_eq!((status, code), (0, 0), "success resets the code");
        // InvalidArgument: a bad query; then a success resets to 0.
        let query = c(r#"{"limit":0}"#);
        let (status, code) = status_and_code(|out, error| unsafe {
            cantino_query(store, query.as_ptr(), out, error)
        });
        assert_eq!((status, code), (-1, ErrorKind::InvalidArgument.code()));
        assert_eq!(
            status_and_code(|out, error| unsafe { cantino_get(store, 0, 1, out, error) }),
            (0, 0)
        );

        // WrongThread: the handle used from another thread. That thread
        // sees its own code; this thread's code is untouched.
        let address = store as usize;
        let foreign = std::thread::spawn(move || {
            let store = address as *mut CantinoStore;
            status_and_code(|out, error| unsafe { cantino_get(store, 0, 1, out, error) })
        })
        .join()
        .unwrap();
        assert_eq!(foreign, (-1, ErrorKind::WrongThread.code()));
        assert_eq!(cantino_last_error_code(), 0);

        assert_eq!(
            status_and_code(|_, error| unsafe { cantino_close(store, error) }),
            (0, 0)
        );
    }

    #[test]
    fn panics_are_internal() {
        let (status, code) = status_and_code(|_, error| protect(error, || panic!("boom")));
        assert_eq!((status, code), (-1, ErrorKind::Internal.code()));
    }
}
