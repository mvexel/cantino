//! Small language-neutral ABI for a platform adapter to call the Rust core.
//! Handles are confined to their creating thread. Buffers have explicit ownership;
//! errors and panics return status codes instead of unwinding through C/Swift/JNI.
use crate::*;
use std::{
    ffi::{CStr, CString},
    os::raw::c_char,
    panic::{AssertUnwindSafe, catch_unwind},
    thread::ThreadId,
};

pub struct FrameworkStore {
    store: Store,
    thread: ThreadId,
}
fn protect(error: *mut *mut c_char, function: impl FnOnce() -> Result<i32>) -> i32 {
    // SAFETY: the ABI contract requires error to be NULL or a writable slot.
    if !error.is_null() {
        unsafe {
            *error = std::ptr::null_mut();
        }
    }
    let result = catch_unwind(AssertUnwindSafe(function));
    let message = match result {
        Ok(Ok(code)) => return code,
        Ok(Err(error)) => error.to_string(),
        Err(_) => "Rust core panicked".into(),
    };
    if !error.is_null() {
        let message = CString::new(message.replace('\0', "\\0")).expect("NUL escaped");
        // SAFETY: the caller owns the allocated string and frees it through this ABI.
        unsafe {
            *error = message.into_raw();
        }
    }
    -1
}
unsafe fn text<'a>(input: *const c_char) -> Result<&'a str> {
    if input.is_null() {
        return Err(Error::Invalid("null string argument".into()));
    }
    // SAFETY: the caller supplies a live NUL-terminated string for this call.
    unsafe { CStr::from_ptr(input) }
        .to_str()
        .map_err(|_| Error::Invalid("argument must be UTF-8".into()))
}
unsafe fn handle<'a>(input: *mut FrameworkStore) -> Result<&'a FrameworkStore> {
    // SAFETY: the caller supplies NULL or a live handle returned by open.
    let handle =
        unsafe { input.as_ref() }.ok_or_else(|| Error::Invalid("null store handle".into()))?;
    if handle.thread != std::thread::current().id() {
        return Err(Error::Invalid("store belongs to a different thread".into()));
    }
    Ok(handle)
}
unsafe fn output(slot: *mut *mut c_char, value: &impl serde::Serialize) -> Result<i32> {
    if slot.is_null() {
        return Err(Error::Invalid("null output slot".into()));
    }
    let json = CString::new(serde_json::to_string(value)?)
        .map_err(|_| Error::Invalid("invalid JSON buffer".into()))?;
    // SAFETY: the caller supplies a writable pointer slot, and now owns this buffer.
    unsafe {
        *slot = json.into_raw();
    }
    Ok(0)
}

/// # Safety
/// `path` must be NUL-terminated; output pointers must be NULL or writable slots.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn osm_framework_open(
    path: *const c_char,
    store: *mut *mut FrameworkStore,
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
        let handle = Box::new(FrameworkStore {
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
pub unsafe extern "C" fn osm_framework_close(
    store: *mut FrameworkStore,
    error: *mut *mut c_char,
) -> i32 {
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
pub unsafe extern "C" fn osm_framework_free(value: *mut c_char) {
    if !value.is_null() {
        unsafe {
            drop(CString::from_raw(value));
        }
    }
}
/// # Safety
/// Handle and output pointers must obey the ownership contract in the header.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn osm_framework_get(
    store: *mut FrameworkStore,
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
        let id = match kind {
            0 => OsmId::Node(NodeId(id)),
            1 => OsmId::Way(WayId(id)),
            2 => OsmId::Relation(RelationId(id)),
            _ => return Err(Error::Invalid("invalid object kind".into())),
        };
        let object = unsafe { handle(store)? }.store.get(id)?;
        match object {
            Some(object) => unsafe { output(out, &object) },
            None => Ok(1),
        }
    })
}
/// # Safety
/// `request` must be a live NUL-terminated UTF-8 JSON query for this call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn osm_framework_query(
    store: *mut FrameworkStore,
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
pub unsafe extern "C" fn osm_framework_import(
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
pub unsafe extern "C" fn osm_framework_slice_job_request(
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
/// As for `osm_framework_slice_job_request`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn osm_framework_slice_job(
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
pub unsafe extern "C" fn osm_framework_slice_progress(
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
