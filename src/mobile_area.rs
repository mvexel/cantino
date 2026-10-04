//! C ABI for the area store (`area_storage`) and failure classification
//! (`failure`): `cantino_area_*` and `cantino_classify_failure`.
//!
//! No handles: every call names the app's areas root directory and the area
//! ID, and all state lives on disk, so these functions can be called from
//! any thread. Concurrency between calls (threads or processes) is handled
//! by the area lock inside `area_storage`. Errors follow the rest of the ABI
//! (`protect`): the negative `CANTINO_ERROR_*` category and an owned
//! message.
use crate::Result;
use crate::area_storage::{AreaStorage, Commit, CommitStage, Parts, validate_area_id};
use crate::failure::{Failure, classify};
use crate::mobile_api::{output, protect, text};
use std::{
    ffi::c_void,
    os::raw::c_char,
    panic::{AssertUnwindSafe, catch_unwind},
};

/// The commit hook (`cantino_area_commit_hook` in the header): called with
/// the caller's `context` and a `CANTINO_AREA_STAGE_*` value. At
/// `BEFORE_COMMIT` a non-zero return aborts the commit; at
/// `AFTER_COMMIT_POINT` the return value is ignored.
pub type CantinoAreaCommitHook = Option<unsafe extern "C" fn(*mut c_void, i32) -> i32>;

unsafe fn optional_text<'a>(input: *const c_char) -> Result<Option<&'a str>> {
    if input.is_null() {
        Ok(None)
    } else {
        unsafe { text(input) }.map(Some)
    }
}

/// Clears an output slot before anything can fail, as every ABI call does.
unsafe fn clear(slot: *mut *mut c_char) {
    if !slot.is_null() {
        unsafe {
            *slot = std::ptr::null_mut();
        }
    }
}

/// 0 when `area_id` is a valid area ID, else `-CANTINO_ERROR_INVALID_ARGUMENT`.
///
/// # Safety
/// `area_id` is a live NUL-terminated string; `error` is NULL or writable.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn cantino_area_validate_id(
    area_id: *const c_char,
    error: *mut *mut c_char,
) -> i32 {
    protect(error, || {
        validate_area_id(unsafe { text(area_id)? })?;
        Ok(0)
    })
}

/// Writes the area's (and, with a non-NULL `work_id`, the run's staging)
/// paths as JSON. Touches no file.
///
/// # Safety
/// `root` and `area_id` are live strings, `work_id` is NULL or live; slots
/// obey the header contract.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn cantino_area_layout(
    root: *const c_char,
    area_id: *const c_char,
    work_id: *const c_char,
    json: *mut *mut c_char,
    error: *mut *mut c_char,
) -> i32 {
    unsafe { clear(json) };
    protect(error, || {
        let storage = AreaStorage::new(unsafe { text(root)? });
        let layout = storage.layout(unsafe { text(area_id)? }, unsafe {
            optional_text(work_id)?
        })?;
        unsafe { output(json, &layout) }
    })
}

/// Rolls a pending commit forward, deletes other runs' staging directories,
/// creates this run's, and writes the layout JSON (as `cantino_area_layout`).
///
/// # Safety
/// Strings are live; slots obey the header contract.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn cantino_area_prepare_staging(
    root: *const c_char,
    area_id: *const c_char,
    work_id: *const c_char,
    json: *mut *mut c_char,
    error: *mut *mut c_char,
) -> i32 {
    unsafe { clear(json) };
    protect(error, || {
        let storage = AreaStorage::new(unsafe { text(root)? });
        let layout =
            storage.prepare_staging(unsafe { text(area_id)? }, unsafe { text(work_id)? })?;
        unsafe { output(json, &layout) }
    })
}

/// Deletes the run's staging directory unless its commit is pending.
///
/// # Safety
/// Strings are live; `error` is NULL or writable.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn cantino_area_discard_staging(
    root: *const c_char,
    area_id: *const c_char,
    work_id: *const c_char,
    error: *mut *mut c_char,
) -> i32 {
    protect(error, || {
        AreaStorage::new(unsafe { text(root)? })
            .discard_staging(unsafe { text(area_id)? }, unsafe { text(work_id)? })?;
        Ok(0)
    })
}

/// Writes the run's sidecar (JSON text, verbatim after validation).
///
/// # Safety
/// Strings are live; `error` is NULL or writable.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn cantino_area_write_staged_metadata(
    root: *const c_char,
    area_id: *const c_char,
    work_id: *const c_char,
    metadata: *const c_char,
    error: *mut *mut c_char,
) -> i32 {
    protect(error, || {
        AreaStorage::new(unsafe { text(root)? }).write_staged_metadata(
            unsafe { text(area_id)? },
            unsafe { text(work_id)? },
            unsafe { text(metadata)? },
        )?;
        Ok(0)
    })
}

/// `CANTINO_AREA_PART_*` bits of `cantino_area_commit`'s `parts`.
const AREA_PART_DATA: i32 = 1;
const AREA_PART_BASEMAP: i32 = 2;

/// Publishes the run's staged version (`parts`: `CANTINO_AREA_PART_*` bits,
/// at least one). Returns 0 when published, 1 when
/// `hook` aborted at `CANTINO_AREA_STAGE_BEFORE_COMMIT` (nothing changed),
/// a negative `CANTINO_ERROR_*` on error. `hook` may be NULL (no check); it runs on the calling
/// thread, under the area lock, and must not call `cantino_area_*` for the
/// same area (the lock is not reentrant). A panic in the hook (a Rust
/// caller's) aborts before the commit point or is ignored after it.
///
/// # Safety
/// Strings are live; `hook` is NULL or a function safe to call with
/// `context` during this call; `error` is NULL or writable.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn cantino_area_commit(
    root: *const c_char,
    area_id: *const c_char,
    work_id: *const c_char,
    parts: i32,
    hook: CantinoAreaCommitHook,
    context: *mut c_void,
    error: *mut *mut c_char,
) -> i32 {
    protect(error, || {
        let storage = AreaStorage::new(unsafe { text(root)? });
        let outcome = storage.commit(
            unsafe { text(area_id)? },
            unsafe { text(work_id)? },
            Parts::new(parts & AREA_PART_DATA != 0, parts & AREA_PART_BASEMAP != 0),
            |stage| match hook {
                None => true,
                Some(hook) => {
                    // SAFETY: the caller guarantees hook/context are valid
                    // for this call. A panic must not cross the hook's frame.
                    let answer =
                        catch_unwind(AssertUnwindSafe(|| unsafe { hook(context, stage as i32) }));
                    match (stage, answer) {
                        (CommitStage::BeforeCommit, Ok(0)) => true,
                        (CommitStage::BeforeCommit, _) => false,
                        (CommitStage::AfterCommitPoint, _) => true,
                    }
                }
            },
        )?;
        Ok(match outcome {
            Commit::Published => 0,
            Commit::Aborted => 1,
        })
    })
}

/// Finishes an interrupted commit of the area, if any.
///
/// # Safety
/// Strings are live; `error` is NULL or writable.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn cantino_area_recover(
    root: *const c_char,
    area_id: *const c_char,
    error: *mut *mut c_char,
) -> i32 {
    protect(error, || {
        AreaStorage::new(unsafe { text(root)? }).recover(unsafe { text(area_id)? })?;
        Ok(0)
    })
}

/// Writes the published area `{"data","basemap","metadata"}`, or returns 1
/// (no JSON) when none is published.
///
/// # Safety
/// Strings are live; slots obey the header contract.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn cantino_area_published(
    root: *const c_char,
    area_id: *const c_char,
    json: *mut *mut c_char,
    error: *mut *mut c_char,
) -> i32 {
    unsafe { clear(json) };
    protect(error, || {
        match AreaStorage::new(unsafe { text(root)? }).published(unsafe { text(area_id)? })? {
            Some(published) => unsafe { output(json, &published) },
            None => Ok(1),
        }
    })
}

/// Classifies a download failure (see `failure`): writes
/// `{"class","reason","inline_retry","scheduler_retry"}`, or returns 1 (no
/// JSON) when the input is not a failure (an accepted status).
///
/// # Safety
/// `input` is a live string; slots obey the header contract.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn cantino_classify_failure(
    input: *const c_char,
    json: *mut *mut c_char,
    error: *mut *mut c_char,
) -> i32 {
    unsafe { clear(json) };
    protect(error, || {
        let failure = Failure::from_json(unsafe { text(input)? })?;
        match classify(failure) {
            Some(classification) => unsafe { output(json, &classification) },
            None => Ok(1),
        }
    })
}
