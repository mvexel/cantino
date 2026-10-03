//! JNI entry points for the Kotlin adapter (`io.github.mvexel.osmframework`).
//!
//! These functions deliberately go through the same C ABI as the iOS adapter
//! (`mobile_api`) instead of calling `Store` directly. That keeps one
//! implementation of thread confinement, panic containment and buffer
//! ownership for both platforms; this module only translates between Java
//! strings and the C ABI's owned UTF-8 buffers.
//!
//! Errors surface in Java as `RuntimeException("Rust error: ...")`, which the
//! Kotlin wrapper rethrows as `OsmFrameworkException`. Store handles cross into
//! Java as a `long` holding the `FrameworkStore` pointer; the Kotlin class owns
//! it and zeroes it after close so a handle is never closed twice.
use crate::mobile_api::*;
use jni::{
    EnvUnowned,
    errors::ThrowRuntimeExAndDefault,
    objects::{JClass, JString},
    sys::{jint, jlong},
};
use std::{
    ffi::{CStr, CString, c_char},
    ptr,
};

/// Failure of one native call. JNI failures (string conversion) and framework
/// errors share one type so every entry point resolves through one policy.
#[derive(Debug, thiserror::Error)]
enum BridgeError {
    #[error("{0}")]
    Jni(#[from] jni::errors::Error),
    #[error("{0}")]
    Framework(String),
    #[error("string argument contains NUL")]
    Nul,
}

/// Takes ownership of a framework buffer, copies it and frees it.
fn take(buffer: *mut c_char) -> Option<String> {
    if buffer.is_null() {
        return None;
    }
    // SAFETY: non-null buffers come from this framework's ABI and are freed
    // exactly once, here, after copying.
    let value = unsafe { CStr::from_ptr(buffer) }
        .to_string_lossy()
        .into_owned();
    unsafe { osm_framework_free(buffer) };
    Some(value)
}

/// Runs one C ABI call with fresh output slots. Returns the status code and the
/// result buffer; a negative status becomes the ABI's error message. The error
/// buffer is always taken so it is freed even when status is 0.
fn call(
    function: impl FnOnce(*mut *mut c_char, *mut *mut c_char) -> i32,
) -> Result<(i32, Option<String>), BridgeError> {
    let mut out = ptr::null_mut();
    let mut error = ptr::null_mut();
    let status = function(&mut out, &mut error);
    let out = take(out);
    let error = take(error);
    if status < 0 {
        return Err(BridgeError::Framework(
            error.unwrap_or_else(|| "unknown framework error".into()),
        ));
    }
    Ok((status, out))
}

fn c_string(value: String) -> Result<CString, BridgeError> {
    CString::new(value).map_err(|_| BridgeError::Nul)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mvexel_osmframework_NativeBridge_open<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    path: JString<'local>,
) -> jlong {
    env.with_env(|env| -> Result<jlong, BridgeError> {
        let path = c_string(path.try_to_string(env)?)?;
        let mut store = ptr::null_mut();
        // SAFETY: path lives for the call; store is a writable slot.
        call(|_, error| unsafe { osm_framework_open(path.as_ptr(), &mut store, error) })?;
        Ok(store as jlong)
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mvexel_osmframework_NativeBridge_close<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    env.with_env(|_| -> Result<(), BridgeError> {
        // SAFETY: the Kotlin owner passes a live handle and forgets it after this.
        call(|_, error| unsafe { osm_framework_close(handle as *mut FrameworkStore, error) })?;
        Ok(())
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

/// Returns the object JSON, or Java `null` when the object is absent (status 1).
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mvexel_osmframework_NativeBridge_get<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    kind: jint,
    id: jlong,
) -> JString<'local> {
    env.with_env(|env| -> Result<JString<'local>, BridgeError> {
        // SAFETY: live handle owned by the Kotlin store on this thread.
        let (status, json) = call(|out, error| unsafe {
            osm_framework_get(handle as *mut FrameworkStore, kind, id, out, error)
        })?;
        match (status, json) {
            (0, Some(json)) => Ok(JString::from_str(env, json)?),
            _ => Ok(JString::default()),
        }
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mvexel_osmframework_NativeBridge_query<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    request: JString<'local>,
) -> JString<'local> {
    env.with_env(|env| -> Result<JString<'local>, BridgeError> {
        let request = c_string(request.try_to_string(env)?)?;
        // SAFETY: live handle; request lives for the call.
        let (_, json) = call(|out, error| unsafe {
            osm_framework_query(handle as *mut FrameworkStore, request.as_ptr(), out, error)
        })?;
        Ok(JString::from_str(env, json.unwrap_or_default())?)
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

/// `options` may be Java `null` for default import options.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mvexel_osmframework_NativeBridge_importArea<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    input: JString<'local>,
    destination: JString<'local>,
    options: JString<'local>,
) -> JString<'local> {
    env.with_env(|env| -> Result<JString<'local>, BridgeError> {
        let input = c_string(input.try_to_string(env)?)?;
        let destination = c_string(destination.try_to_string(env)?)?;
        let options = if options.is_null() {
            None
        } else {
            Some(c_string(options.try_to_string(env)?)?)
        };
        let options = options.as_ref().map_or(ptr::null(), |value| value.as_ptr());
        // SAFETY: all strings live for the call; options may be NULL by contract.
        let (_, report) = call(|out, error| unsafe {
            osm_framework_import(input.as_ptr(), destination.as_ptr(), options, out, error)
        })?;
        Ok(JString::from_str(env, report.unwrap_or_default())?)
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

/// Optional Java string → owned C string (`null` → `None`).
fn optional(env: &mut jni::Env, value: &JString) -> Result<Option<CString>, BridgeError> {
    if value.is_null() {
        Ok(None)
    } else {
        Ok(Some(c_string(value.try_to_string(env)?)?))
    }
}

/// SliceOSM submit request: `{"url","body"}`. `base` may be Java `null`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mvexel_osmframework_NativeBridge_sliceJobRequest<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    base: JString<'local>,
    bbox: JString<'local>,
    name: JString<'local>,
) -> JString<'local> {
    env.with_env(|env| -> Result<JString<'local>, BridgeError> {
        let base = optional(env, &base)?;
        let bbox = c_string(bbox.try_to_string(env)?)?;
        let name = c_string(name.try_to_string(env)?)?;
        let base = base.as_ref().map_or(ptr::null(), |value| value.as_ptr());
        // SAFETY: all strings live for the call; base may be NULL by contract.
        let (_, json) = call(|out, error| unsafe {
            osm_framework_slice_job_request(base, bbox.as_ptr(), name.as_ptr(), out, error)
        })?;
        Ok(JString::from_str(env, json.unwrap_or_default())?)
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

/// SliceOSM job from a submit response or a persisted ID: `{"job_id","status_url","download_url"}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mvexel_osmframework_NativeBridge_sliceJob<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    base: JString<'local>,
    response: JString<'local>,
) -> JString<'local> {
    env.with_env(|env| -> Result<JString<'local>, BridgeError> {
        let base = optional(env, &base)?;
        let response = c_string(response.try_to_string(env)?)?;
        let base = base.as_ref().map_or(ptr::null(), |value| value.as_ptr());
        // SAFETY: strings live for the call; base may be NULL by contract.
        let (_, json) = call(|out, error| unsafe {
            osm_framework_slice_job(base, response.as_ptr(), out, error)
        })?;
        Ok(JString::from_str(env, json.unwrap_or_default())?)
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

/// SliceOSM status document → `{"complete","fraction","size_bytes","timestamp"}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mvexel_osmframework_NativeBridge_sliceProgress<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    status: JString<'local>,
) -> JString<'local> {
    env.with_env(|env| -> Result<JString<'local>, BridgeError> {
        let status = c_string(status.try_to_string(env)?)?;
        // SAFETY: status lives for the call.
        let (_, json) = call(|out, error| unsafe {
            osm_framework_slice_progress(status.as_ptr(), out, error)
        })?;
        Ok(JString::from_str(env, json.unwrap_or_default())?)
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}
