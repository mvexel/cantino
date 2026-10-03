//! JNI entry points for the Kotlin adapter (`io.github.mvexel.cantino`).
//!
//! These functions deliberately go through the same C ABI as the iOS adapter
//! (`mobile_api`) instead of calling `Store` directly. That keeps one
//! implementation of thread confinement, panic containment and buffer
//! ownership for both platforms; this module only translates between Java
//! strings and the C ABI's owned UTF-8 buffers.
//!
//! Errors surface in Java as `RuntimeException("Rust error: ...")`, which the
//! Kotlin wrapper rethrows as `CantinoException`. Store handles cross into
//! Java as a `long` holding the `CantinoStore` pointer; the Kotlin class owns
//! it and zeroes it after close so a handle is never closed twice.
use crate::mobile_api::*;
use crate::mobile_basemap::*;
use jni::{
    EnvUnowned,
    errors::ThrowRuntimeExAndDefault,
    objects::{JByteArray, JClass, JString},
    sys::{jdouble, jint, jlong},
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
    unsafe { cantino_free(buffer) };
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
pub extern "system" fn Java_io_github_mvexel_cantino_NativeBridge_open<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    path: JString<'local>,
) -> jlong {
    env.with_env(|env| -> Result<jlong, BridgeError> {
        let path = c_string(path.try_to_string(env)?)?;
        let mut store = ptr::null_mut();
        // SAFETY: path lives for the call; store is a writable slot.
        call(|_, error| unsafe { cantino_open(path.as_ptr(), &mut store, error) })?;
        Ok(store as jlong)
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mvexel_cantino_NativeBridge_close<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    env.with_env(|_| -> Result<(), BridgeError> {
        // SAFETY: the Kotlin owner passes a live handle and forgets it after this.
        call(|_, error| unsafe { cantino_close(handle as *mut CantinoStore, error) })?;
        Ok(())
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

/// Returns the object JSON, or Java `null` when the object is absent (status 1).
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mvexel_cantino_NativeBridge_get<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    kind: jint,
    id: jlong,
) -> JString<'local> {
    env.with_env(|env| -> Result<JString<'local>, BridgeError> {
        // SAFETY: live handle owned by the Kotlin store on this thread.
        let (status, json) = call(|out, error| unsafe {
            cantino_get(handle as *mut CantinoStore, kind, id, out, error)
        })?;
        match (status, json) {
            (0, Some(json)) => Ok(JString::from_str(env, json)?),
            _ => Ok(JString::default()),
        }
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mvexel_cantino_NativeBridge_query<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    request: JString<'local>,
) -> JString<'local> {
    env.with_env(|env| -> Result<JString<'local>, BridgeError> {
        let request = c_string(request.try_to_string(env)?)?;
        // SAFETY: live handle; request lives for the call.
        let (_, json) = call(|out, error| unsafe {
            cantino_query(handle as *mut CantinoStore, request.as_ptr(), out, error)
        })?;
        Ok(JString::from_str(env, json.unwrap_or_default())?)
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

/// `options` may be Java `null` for default import options.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mvexel_cantino_NativeBridge_importArea<'local>(
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
            cantino_import(input.as_ptr(), destination.as_ptr(), options, out, error)
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
pub extern "system" fn Java_io_github_mvexel_cantino_NativeBridge_sliceJobRequest<'local>(
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
            cantino_slice_job_request(base, bbox.as_ptr(), name.as_ptr(), out, error)
        })?;
        Ok(JString::from_str(env, json.unwrap_or_default())?)
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

/// SliceOSM job from a submit response or a persisted ID: `{"job_id","status_url","download_url"}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mvexel_cantino_NativeBridge_sliceJob<'local>(
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
        let (_, json) =
            call(|out, error| unsafe { cantino_slice_job(base, response.as_ptr(), out, error) })?;
        Ok(JString::from_str(env, json.unwrap_or_default())?)
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

/// SliceOSM status document → `{"complete","fraction","size_bytes","timestamp"}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mvexel_cantino_NativeBridge_sliceProgress<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    status: JString<'local>,
) -> JString<'local> {
    env.with_env(|env| -> Result<JString<'local>, BridgeError> {
        let status = c_string(status.try_to_string(env)?)?;
        // SAFETY: status lives for the call.
        let (_, json) =
            call(|out, error| unsafe { cantino_slice_progress(status.as_ptr(), out, error) })?;
        Ok(JString::from_str(env, json.unwrap_or_default())?)
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

// --- Basemap extract ---------------------------------------------------------
//
// Plan and assembler handles cross into Java as `long` pointers, like store
// handles, and carry the same thread confinement (checked in the C ABI): the
// Kotlin driver (`BasemapExtract`) makes every call for one extract on one
// dedicated thread and fetches on others. Tile ranges come in as file paths
// (`basemapAsmWriteRangeFile`) so multi-megabyte responses never cross JNI as
// `byte[]`; directory responses (small) come in as `byte[]`, copied once.

/// Runs a C ABI call that returns a JSON buffer and converts it to a Java string.
fn json_call<'local>(
    env: &mut jni::Env<'local>,
    function: impl FnOnce(*mut *mut c_char, *mut *mut c_char) -> i32,
) -> Result<JString<'local>, BridgeError> {
    let (_, json) = call(function)?;
    Ok(JString::from_str(env, json.unwrap_or_default())?)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mvexel_cantino_NativeBridge_basemapPlanNew<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    bbox: JString<'local>,
    min_zoom: jint,
    max_zoom: jint,
    overfetch: jdouble,
) -> jlong {
    env.with_env(|env| -> Result<jlong, BridgeError> {
        let bbox = c_string(bbox.try_to_string(env)?)?;
        let mut plan = ptr::null_mut();
        // SAFETY: bbox lives for the call; plan is a writable slot.
        call(|_, error| unsafe {
            cantino_basemap_plan_new(
                bbox.as_ptr(),
                min_zoom,
                max_zoom,
                overfetch,
                &mut plan,
                error,
            )
        })?;
        Ok(plan as jlong)
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mvexel_cantino_NativeBridge_basemapPlanFirstRequest<
    'local,
>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    plan: jlong,
) -> JString<'local> {
    env.with_env(|env| -> Result<JString<'local>, BridgeError> {
        // SAFETY: live plan handle owned by the Kotlin driver on this thread.
        json_call(env, |out, error| unsafe {
            cantino_basemap_plan_first_request(plan as *mut CantinoBasemapPlan, out, error)
        })
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

/// Feeds a directory-phase response (`bytes` copied once out of the JVM heap).
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mvexel_cantino_NativeBridge_basemapPlanFeed<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    plan: jlong,
    id: jlong,
    bytes: JByteArray<'local>,
) -> JString<'local> {
    env.with_env(|env| -> Result<JString<'local>, BridgeError> {
        let bytes = env.convert_byte_array(&bytes)?;
        // SAFETY: live plan handle; bytes live for the call.
        json_call(env, |out, error| unsafe {
            cantino_basemap_plan_feed(
                plan as *mut CantinoBasemapPlan,
                id as u64,
                bytes.as_ptr(),
                bytes.len(),
                out,
                error,
            )
        })
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mvexel_cantino_NativeBridge_basemapPlanOutstanding<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    plan: jlong,
) -> JString<'local> {
    env.with_env(|env| -> Result<JString<'local>, BridgeError> {
        // SAFETY: live plan handle on its owner thread.
        json_call(env, |out, error| unsafe {
            cantino_basemap_plan_outstanding(plan as *mut CantinoBasemapPlan, out, error)
        })
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

/// Consumes the plan (see the header: every call past the handle check) and
/// returns the assembler handle.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mvexel_cantino_NativeBridge_basemapPlanIntoAssembler<
    'local,
>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    plan: jlong,
    staging: JString<'local>,
) -> jlong {
    env.with_env(|env| -> Result<jlong, BridgeError> {
        let staging = c_string(staging.try_to_string(env)?)?;
        let mut assembler = ptr::null_mut();
        // SAFETY: live plan handle; the Kotlin owner forgets it after this
        // call; staging lives for the call; assembler is a writable slot.
        call(|_, error| unsafe {
            cantino_basemap_plan_into_assembler(
                plan as *mut CantinoBasemapPlan,
                staging.as_ptr(),
                &mut assembler,
                error,
            )
        })?;
        Ok(assembler as jlong)
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mvexel_cantino_NativeBridge_basemapPlanFree<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    plan: jlong,
) {
    env.with_env(|_| -> Result<(), BridgeError> {
        // SAFETY: live (or zero) plan handle, freed once by its Kotlin owner.
        call(|_, error| unsafe {
            cantino_basemap_plan_free(plan as *mut CantinoBasemapPlan, error)
        })?;
        Ok(())
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mvexel_cantino_NativeBridge_basemapAsmWriteRange<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    assembler: jlong,
    id: jlong,
    bytes: JByteArray<'local>,
) {
    env.with_env(|env| -> Result<(), BridgeError> {
        let bytes = env.convert_byte_array(&bytes)?;
        // SAFETY: live assembler handle on its owner thread; bytes live for the call.
        call(|_, error| unsafe {
            cantino_basemap_asm_write_range(
                assembler as *mut CantinoBasemapAssembler,
                id as u64,
                bytes.as_ptr(),
                bytes.len(),
                error,
            )
        })?;
        Ok(())
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mvexel_cantino_NativeBridge_basemapAsmWriteRangeFile<
    'local,
>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    assembler: jlong,
    id: jlong,
    path: JString<'local>,
) {
    env.with_env(|env| -> Result<(), BridgeError> {
        let path = c_string(path.try_to_string(env)?)?;
        // SAFETY: live assembler handle on its owner thread; path lives for the call.
        call(|_, error| unsafe {
            cantino_basemap_asm_write_range_file(
                assembler as *mut CantinoBasemapAssembler,
                id as u64,
                path.as_ptr(),
                error,
            )
        })?;
        Ok(())
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mvexel_cantino_NativeBridge_basemapAsmRemaining<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    assembler: jlong,
) -> JString<'local> {
    env.with_env(|env| -> Result<JString<'local>, BridgeError> {
        // SAFETY: live assembler handle on its owner thread.
        json_call(env, |out, error| unsafe {
            cantino_basemap_asm_remaining(assembler as *mut CantinoBasemapAssembler, out, error)
        })
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mvexel_cantino_NativeBridge_basemapAsmProgress<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    assembler: jlong,
) -> JString<'local> {
    env.with_env(|env| -> Result<JString<'local>, BridgeError> {
        // SAFETY: live assembler handle on its owner thread.
        json_call(env, |out, error| unsafe {
            cantino_basemap_asm_progress(assembler as *mut CantinoBasemapAssembler, out, error)
        })
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mvexel_cantino_NativeBridge_basemapAsmFinish<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    assembler: jlong,
    output: JString<'local>,
) {
    env.with_env(|env| -> Result<(), BridgeError> {
        let output = c_string(output.try_to_string(env)?)?;
        // SAFETY: live assembler handle on its owner thread; output lives for the call.
        call(|_, error| unsafe {
            cantino_basemap_asm_finish(
                assembler as *mut CantinoBasemapAssembler,
                output.as_ptr(),
                error,
            )
        })?;
        Ok(())
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mvexel_cantino_NativeBridge_basemapAsmFree<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    assembler: jlong,
) {
    env.with_env(|_| -> Result<(), BridgeError> {
        // SAFETY: live (or zero) assembler handle, freed once by its Kotlin owner.
        call(|_, error| unsafe {
            cantino_basemap_asm_free(assembler as *mut CantinoBasemapAssembler, error)
        })?;
        Ok(())
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

/// Validates a local PMTiles file and describes its header (any thread).
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_mvexel_cantino_NativeBridge_basemapInfo<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    path: JString<'local>,
) -> JString<'local> {
    env.with_env(|env| -> Result<JString<'local>, BridgeError> {
        let path = c_string(path.try_to_string(env)?)?;
        // SAFETY: path lives for the call.
        json_call(env, |out, error| unsafe {
            cantino_basemap_info(path.as_ptr(), out, error)
        })
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}
