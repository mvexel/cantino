use crate::{Error, Result};
use std::{
    ffi::{CStr, CString},
    os::raw::{c_char, c_int, c_void},
    path::Path,
};

// This module is the only place that handles pointers across the C ABI.
// All returned native strings are copied before the LMDB transaction ends.
unsafe extern "C" {
    pub fn osmx_open(
        path: *const c_char,
        map_size: usize,
        handle: *mut *mut c_void,
        error: *mut *mut c_char,
    ) -> c_int;
    pub fn osmx_close(handle: *mut c_void);
    fn osmx_free(value: *mut c_char);
    pub fn osmx_get(
        handle: *mut c_void,
        kind: c_int,
        id: u64,
        json: *mut *mut c_char,
        error: *mut *mut c_char,
    ) -> c_int;
    pub fn osmx_stats(
        handle: *mut c_void,
        json: *mut *mut c_char,
        error: *mut *mut c_char,
    ) -> c_int;
    pub fn osmx_scan(
        handle: *mut c_void,
        kind: c_int,
        after: u64,
        limit: usize,
        json: *mut *mut c_char,
        error: *mut *mut c_char,
    ) -> c_int;
    pub fn osmx_candidates(
        handle: *mut c_void,
        west: f64,
        south: f64,
        east: f64,
        north: f64,
        maximum: usize,
        json: *mut *mut c_char,
        error: *mut *mut c_char,
    ) -> c_int;
    pub fn osmx_import(
        input: *const c_char,
        output: *const c_char,
        map_size: usize,
        sort_pairs: usize,
        preserve_untagged: c_int,
        error: *mut *mut c_char,
    ) -> c_int;
}
pub fn path(path: &Path) -> Result<CString> {
    let text = path
        .to_str()
        .ok_or_else(|| Error::Invalid("path must be UTF-8".into()))?;
    CString::new(text).map_err(|_| Error::Invalid("path contains NUL".into()))
}
/// RAII frees the native allocation even if JSON decoding fails.
pub struct NativeString(pub *mut c_char);
impl NativeString {
    pub fn empty() -> Self {
        Self(std::ptr::null_mut())
    }
    pub fn text(&self) -> Result<&str> {
        if self.0.is_null() {
            return Err(Error::Native("native result is null".into()));
        }
        // SAFETY: C API allocations are NUL-terminated and live until Drop.
        unsafe { CStr::from_ptr(self.0) }
            .to_str()
            .map_err(|_| Error::Native("invalid native UTF-8".into()))
    }
}
impl Drop for NativeString {
    fn drop(&mut self) {
        // SAFETY: osmx_free accepts NULL and allocations from the C API.
        unsafe { osmx_free(self.0) }
    }
}
pub fn status(code: c_int, error: &NativeString) -> Result<bool> {
    match code {
        0 => Ok(true),
        1 => Ok(false),
        _ => Err(Error::Native(
            error
                .text()
                .unwrap_or("native allocation or operation failed")
                .into(),
        )),
    }
}
