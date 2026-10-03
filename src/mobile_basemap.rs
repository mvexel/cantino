//! C ABI for the basemap extract engine (`crate::basemap`), for the platform
//! adapters (Kotlin through `android.rs`, Swift through the header).
//!
//! The engine is sans-IO: the adapter performs every HTTP range request and
//! feeds the responses back. Two owned handles model the two phases:
//!
//! ```text
//! plan_new ─▶ FrameworkBasemapPlan
//!   plan_first_request → ByteRange JSON          (always id 0, 16 KiB)
//!   plan_feed(id, bytes) → Step JSON             ({"fetch":[…]} | "wait" | {"tiles_ready":{…}})
//!   plan_outstanding → [ByteRange…]              (requests issued and not fed yet)
//! plan_into_assembler(plan, staging) ─▶ FrameworkBasemapAssembler   (plan consumed)
//!   asm_write_range(id, bytes) / asm_write_range_file(id, path)
//!   asm_remaining → [ByteRange…], asm_progress → Progress JSON
//!   asm_finish(output)                           (header last, fsync, atomic rename)
//! plan_free / asm_free                           (dropping an unfinished assembler deletes its staging file)
//! ```
//!
//! Ownership and threading follow `FrameworkStore` exactly: a handle belongs
//! to the thread that created it (an assembler to the thread that called
//! `plan_into_assembler`), every call including `*_free` must come from that
//! thread, and a call from any other thread is rejected with an error instead
//! of touching the handle. The engine does no I/O except the assembler's
//! staging/output files, so an adapter typically owns both handles on one
//! dedicated thread and fetches on as many other threads as it likes,
//! handing each response over (as bytes, or as a file it streamed the
//! response into, which keeps large tile ranges out of the JVM heap).
//!
//! Errors and panics become status -1 plus an owned error string, as in
//! `mobile_api`. Feeding a wrong id or a response of the wrong length is
//! rejected without changing state (the adapter may re-fetch); any other
//! plan error is fatal for that plan (free it and start over).
use crate::basemap::format::{HEADER_LEN, Header};
use crate::basemap::{Assembler, BBox, ExtractPlan};
use crate::mobile_api::{output, protect, text};
use crate::{Error, Result};
use std::{ffi::c_char, io::Read, thread::ThreadId};

/// A value confined to the thread that created it.
pub struct Confined<T> {
    value: T,
    thread: ThreadId,
}

impl<T> Confined<T> {
    fn new(value: T) -> Box<Self> {
        Box::new(Self {
            value,
            thread: std::thread::current().id(),
        })
    }
}

/// Planning handle (`FrameworkBasemapPlan *` in the header).
pub type FrameworkBasemapPlan = Confined<ExtractPlan>;
/// Download/assembly handle (`FrameworkBasemapAssembler *` in the header).
pub type FrameworkBasemapAssembler = Confined<Assembler>;

/// Borrows a live handle after checking it is non-NULL and on its thread.
///
/// # Safety
/// `input` is NULL or a live handle returned by this ABI and not yet freed.
unsafe fn confined<'a, T>(input: *mut Confined<T>, what: &str) -> Result<&'a mut Confined<T>> {
    // SAFETY: per the contract above; the handle is only reached through
    // this one call at a time because it is confined to one thread.
    let handle =
        unsafe { input.as_mut() }.ok_or_else(|| Error::Invalid(format!("null {what} handle")))?;
    if handle.thread != std::thread::current().id() {
        return Err(Error::Invalid(format!(
            "{what} belongs to a different thread"
        )));
    }
    Ok(handle)
}

/// Sets an output pointer slot to NULL before any work, so callers never see
/// a stale value after an error.
fn clear<T>(slot: *mut *mut T) {
    if !slot.is_null() {
        // SAFETY: the ABI contract requires a writable slot or NULL.
        unsafe { *slot = std::ptr::null_mut() };
    }
}

/// A (pointer, length) pair from C as a slice; NULL is allowed for length 0.
///
/// # Safety
/// `bytes` must be readable for `len` bytes for the duration of the call.
unsafe fn slice<'a>(bytes: *const u8, len: usize) -> Result<&'a [u8]> {
    if len == 0 {
        return Ok(&[]);
    }
    if bytes.is_null() {
        return Err(Error::Invalid(
            "null byte buffer with non-zero length".into(),
        ));
    }
    // SAFETY: per the contract above.
    Ok(unsafe { std::slice::from_raw_parts(bytes, len) })
}

fn zoom(value: i32, what: &str) -> Result<Option<u8>> {
    match value {
        -1 => Ok(None),
        0..=31 => Ok(Some(value as u8)),
        _ => Err(Error::Invalid(format!(
            "{what} must be -1 (archive's) or 0..=31, got {value}"
        ))),
    }
}

// ------------------------------------------------------------------ plan --

/// Creates an extract plan. `bbox` is `{"west","south","east","north"}`
/// (degrees; `west > east` crosses the antimeridian). `min_zoom`/`max_zoom`
/// are -1 for the source archive's own, otherwise clamped to it.
/// `overfetch` is the extra bytes allowed per wanted byte to save requests
/// (go-pmtiles uses 0.05).
///
/// # Safety
/// `bbox` is a live UTF-8 string; output slots obey the header contract.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn osm_framework_basemap_plan_new(
    bbox: *const c_char,
    min_zoom: i32,
    max_zoom: i32,
    overfetch: f64,
    plan: *mut *mut FrameworkBasemapPlan,
    error: *mut *mut c_char,
) -> i32 {
    clear(plan);
    protect(error, || {
        if plan.is_null() {
            return Err(Error::Invalid("null plan output".into()));
        }
        let bbox: BBox = serde_json::from_str(unsafe { text(bbox)? })?;
        let value = ExtractPlan::new(
            bbox,
            zoom(min_zoom, "min_zoom")?,
            zoom(max_zoom, "max_zoom")?,
            overfetch as f32,
        )?;
        // SAFETY: checked non-NULL above; the caller now owns the handle.
        unsafe { *plan = Box::into_raw(Confined::new(value)) };
        Ok(0)
    })
}

/// Writes the first request (`{"id":0,"offset":0,"length":16384}`).
///
/// # Safety
/// `plan` is a live handle on its owner thread; output slots obey the header.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn osm_framework_basemap_plan_first_request(
    plan: *mut FrameworkBasemapPlan,
    out: *mut *mut c_char,
    error: *mut *mut c_char,
) -> i32 {
    clear(out);
    protect(error, || {
        let plan = unsafe { confined(plan, "basemap plan")? };
        unsafe { output(out, &plan.value.first_request()) }
    })
}

/// Feeds the response to request `id` and writes the next Step as JSON:
/// `{"fetch":[ByteRange…]}` (issue these too), `"wait"` (keep feeding the
/// outstanding ones) or `{"tiles_ready":TilePlan}` (create the assembler).
///
/// # Safety
/// `plan` is a live handle on its owner thread; `bytes` is readable for
/// `len` bytes (NULL allowed when `len` is 0); output slots obey the header.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn osm_framework_basemap_plan_feed(
    plan: *mut FrameworkBasemapPlan,
    id: u64,
    bytes: *const u8,
    len: usize,
    out: *mut *mut c_char,
    error: *mut *mut c_char,
) -> i32 {
    clear(out);
    protect(error, || {
        let plan = unsafe { confined(plan, "basemap plan")? };
        let bytes = unsafe { slice(bytes, len)? };
        let step = plan.value.feed(id, bytes)?;
        unsafe { output(out, &step) }
    })
}

/// Writes the requests issued and not yet fed, as a JSON array of ByteRange.
///
/// # Safety
/// As for `osm_framework_basemap_plan_first_request`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn osm_framework_basemap_plan_outstanding(
    plan: *mut FrameworkBasemapPlan,
    out: *mut *mut c_char,
    error: *mut *mut c_char,
) -> i32 {
    clear(out);
    protect(error, || {
        let plan = unsafe { confined(plan, "basemap plan")? };
        unsafe { output(out, &plan.value.outstanding()) }
    })
}

/// Turns a plan that reached `tiles_ready` into an assembler writing to
/// `staging` (created or truncated; must be on the same file system as the
/// eventual output, since `finish` renames it).
///
/// Ownership: the plan is **consumed by every call that gets past the handle
/// check**, successful or not; only a NULL handle or a call from the wrong
/// thread leaves it alive. On success the new assembler belongs to the
/// calling thread (which is the plan's).
///
/// # Safety
/// `plan` is a live handle; `staging` a live UTF-8 path; output slots obey
/// the header contract.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn osm_framework_basemap_plan_into_assembler(
    plan: *mut FrameworkBasemapPlan,
    staging: *const c_char,
    assembler: *mut *mut FrameworkBasemapAssembler,
    error: *mut *mut c_char,
) -> i32 {
    clear(assembler);
    protect(error, || {
        unsafe { confined(plan, "basemap plan")? };
        // SAFETY: checked live and on this thread; ownership moves here, so
        // the plan is dropped on every path below.
        let owned = unsafe { Box::from_raw(plan) };
        if assembler.is_null() {
            return Err(Error::Invalid("null assembler output".into()));
        }
        let staging = unsafe { text(staging)? };
        let value = owned.value.into_assembler(staging)?;
        // SAFETY: checked non-NULL above.
        unsafe { *assembler = Box::into_raw(Confined::new(value)) };
        Ok(0)
    })
}

/// Frees a plan (no-op for NULL). Fails, leaving the plan alive, when
/// called from a thread other than its owner.
///
/// # Safety
/// `plan` is NULL or a live handle, freed at most once.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn osm_framework_basemap_plan_free(
    plan: *mut FrameworkBasemapPlan,
    error: *mut *mut c_char,
) -> i32 {
    protect(error, || {
        if plan.is_null() {
            return Ok(0);
        }
        unsafe {
            confined(plan, "basemap plan")?;
            drop(Box::from_raw(plan));
        }
        Ok(0)
    })
}

// ------------------------------------------------------------- assembler --

/// Writes the response to tile request `id`; it must be exactly the
/// requested length. Idempotent: a range may be written again.
///
/// # Safety
/// `assembler` is a live handle on its owner thread; `bytes` readable for
/// `len` bytes (NULL allowed when 0).
#[unsafe(no_mangle)]
pub unsafe extern "C" fn osm_framework_basemap_asm_write_range(
    assembler: *mut FrameworkBasemapAssembler,
    id: u64,
    bytes: *const u8,
    len: usize,
    error: *mut *mut c_char,
) -> i32 {
    protect(error, || {
        let asm = unsafe { confined(assembler, "basemap assembler")? };
        let bytes = unsafe { slice(bytes, len)? };
        asm.value.write_range(id, bytes)?;
        Ok(0)
    })
}

/// Like `asm_write_range`, but streams the response from the file at `path`
/// (which must hold exactly the requested bytes; the adapter downloads the
/// range into it and deletes it afterwards). Only a 64 KiB buffer passes
/// through memory, however large the range.
///
/// # Safety
/// `assembler` is a live handle on its owner thread; `path` a live UTF-8
/// string.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn osm_framework_basemap_asm_write_range_file(
    assembler: *mut FrameworkBasemapAssembler,
    id: u64,
    path: *const c_char,
    error: *mut *mut c_char,
) -> i32 {
    protect(error, || {
        let asm = unsafe { confined(assembler, "basemap assembler")? };
        let file = std::fs::File::open(unsafe { text(path)? })?;
        asm.value
            .write_range_from(id, std::io::BufReader::with_capacity(64 * 1024, file))?;
        Ok(0)
    })
}

/// Writes the tile requests not yet written, as a JSON array of ByteRange.
///
/// # Safety
/// `assembler` is a live handle on its owner thread; output slots obey the
/// header contract.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn osm_framework_basemap_asm_remaining(
    assembler: *mut FrameworkBasemapAssembler,
    out: *mut *mut c_char,
    error: *mut *mut c_char,
) -> i32 {
    clear(out);
    protect(error, || {
        let asm = unsafe { confined(assembler, "basemap assembler")? };
        unsafe { output(out, &asm.value.remaining()) }
    })
}

/// Writes `{"ranges_done","ranges_total","bytes_done","bytes_total"}`.
///
/// # Safety
/// As for `osm_framework_basemap_asm_remaining`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn osm_framework_basemap_asm_progress(
    assembler: *mut FrameworkBasemapAssembler,
    out: *mut *mut c_char,
    error: *mut *mut c_char,
) -> i32 {
    clear(out);
    protect(error, || {
        let asm = unsafe { confined(assembler, "basemap assembler")? };
        unsafe { output(out, &asm.value.progress()) }
    })
}

/// Completes the archive at `output` (header last, fsync, atomic rename over
/// any existing file). Refuses while ranges are missing, leaving the output
/// untouched and the assembler usable. The handle must still be freed.
///
/// # Safety
/// `assembler` is a live handle on its owner thread; `output` a live UTF-8
/// path.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn osm_framework_basemap_asm_finish(
    assembler: *mut FrameworkBasemapAssembler,
    output: *const c_char,
    error: *mut *mut c_char,
) -> i32 {
    protect(error, || {
        let asm = unsafe { confined(assembler, "basemap assembler")? };
        asm.value.finish(unsafe { text(output)? })?;
        Ok(0)
    })
}

/// Frees an assembler (no-op for NULL). An unfinished assembler deletes its
/// staging file. Fails, leaving it alive, from a non-owner thread.
///
/// # Safety
/// `assembler` is NULL or a live handle, freed at most once.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn osm_framework_basemap_asm_free(
    assembler: *mut FrameworkBasemapAssembler,
    error: *mut *mut c_char,
) -> i32 {
    protect(error, || {
        if assembler.is_null() {
            return Ok(0);
        }
        unsafe {
            confined(assembler, "basemap assembler")?;
            drop(Box::from_raw(assembler));
        }
        Ok(0)
    })
}

// ------------------------------------------------------------ inspection --

/// What `osm_framework_basemap_info` reports about a local archive.
#[derive(serde::Serialize)]
struct ArchiveInfo {
    spec_version: u8,
    /// `[west, south, east, north]` in degrees.
    bounds: [f64; 4],
    /// `[lon, lat, zoom]`.
    center: [f64; 3],
    min_zoom: u8,
    max_zoom: u8,
    addressed_tiles: u64,
    tile_entries: u64,
    tile_contents: u64,
    /// PMTiles tile type: 1 mvt, 2 png, 3 jpeg, 4 webp, 5 avif, 0 unknown.
    tile_type: u8,
    tile_compression: u8,
    clustered: bool,
    file_bytes: u64,
}

/// Validates a local PMTiles file and describes it: the magic number and
/// spec version 3 are required, and every section the header declares must
/// lie within the file (so a truncated download is rejected). Directories
/// are not walked. Pure function, any thread, no handle.
///
/// # Safety
/// `path` is a live UTF-8 string; output slots obey the header contract.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn osm_framework_basemap_info(
    path: *const c_char,
    out: *mut *mut c_char,
    error: *mut *mut c_char,
) -> i32 {
    clear(out);
    protect(error, || {
        let mut file = std::fs::File::open(unsafe { text(path)? })?;
        let file_bytes = file.metadata()?.len();
        let mut head = Vec::with_capacity(HEADER_LEN);
        (&mut file).take(HEADER_LEN as u64).read_to_end(&mut head)?;
        let h = Header::parse(&head)?;
        for (name, offset, length) in [
            ("root directory", h.root_offset, h.root_length),
            ("metadata", h.metadata_offset, h.metadata_length),
            (
                "leaf directories",
                h.leaf_directory_offset,
                h.leaf_directory_length,
            ),
            ("tile data", h.tile_data_offset, h.tile_data_length),
        ] {
            if offset
                .checked_add(length)
                .is_none_or(|end| end > file_bytes)
            {
                return Err(Error::Corrupt(format!(
                    "PMTiles {name} ends beyond the file's {file_bytes} bytes (truncated?)"
                )));
            }
        }
        let deg = |e7: i32| f64::from(e7) / 1e7;
        let info = ArchiveInfo {
            spec_version: h.spec_version,
            bounds: [
                deg(h.min_lon_e7),
                deg(h.min_lat_e7),
                deg(h.max_lon_e7),
                deg(h.max_lat_e7),
            ],
            center: [
                deg(h.center_lon_e7),
                deg(h.center_lat_e7),
                f64::from(h.center_zoom),
            ],
            min_zoom: h.min_zoom,
            max_zoom: h.max_zoom,
            addressed_tiles: h.addressed_tiles_count,
            tile_entries: h.tile_entries_count,
            tile_contents: h.tile_contents_count,
            tile_type: h.tile_type,
            tile_compression: h.tile_compression.to_byte(),
            clustered: h.clustered,
            file_bytes,
        };
        unsafe { output(out, &info) }
    })
}

#[cfg(test)]
mod tests {
    //! The ABI driven the way an adapter drives it: raw pointers, JSON out,
    //! buffers freed through `osm_framework_free`, against the committed
    //! Protomaps fixture. The result must be byte-identical to driving the
    //! Rust engine directly.
    use super::*;
    use crate::basemap::{ByteRange, Step};
    use crate::mobile_api::osm_framework_free;
    use std::ffi::{CStr, CString};
    use std::path::{Path, PathBuf};
    use std::ptr::null_mut;

    fn fixture() -> Vec<u8> {
        std::fs::read(
            PathBuf::from(env!("CARGO_MANIFEST_DIR"))
                .join("tests/fixtures/basemap/slc-nw-z12-15.pmtiles"),
        )
        .unwrap()
    }

    fn serve(src: &[u8], r: ByteRange) -> &[u8] {
        let start = (r.offset as usize).min(src.len());
        let end = ((r.offset + r.length) as usize).min(src.len());
        &src[start..end]
    }

    /// Runs one ABI call with output and error slots; returns (status,
    /// output JSON, error text), freeing both buffers.
    fn call(
        f: impl FnOnce(*mut *mut c_char, *mut *mut c_char) -> i32,
    ) -> (i32, Option<String>, Option<String>) {
        let (mut out, mut err) = (null_mut(), null_mut());
        let status = f(&mut out, &mut err);
        let take = |p: *mut c_char| {
            (!p.is_null()).then(|| {
                let s = unsafe { CStr::from_ptr(p) }.to_str().unwrap().to_owned();
                unsafe { osm_framework_free(p) };
                s
            })
        };
        (status, take(out), take(err))
    }

    fn ok_json<T: serde::de::DeserializeOwned>(r: (i32, Option<String>, Option<String>)) -> T {
        assert_eq!(r.0, 0, "error: {:?}", r.2);
        serde_json::from_str(&r.1.expect("output")).unwrap()
    }

    fn ok(status: i32, err: *mut c_char) {
        if status != 0 {
            let msg = unsafe { CStr::from_ptr(err) }
                .to_string_lossy()
                .into_owned();
            unsafe { osm_framework_free(err) };
            panic!("status {status}: {msg}");
        }
        assert!(err.is_null());
    }

    fn c(s: &str) -> CString {
        CString::new(s).unwrap()
    }

    const BBOX: &str = r#"{"west":-112.085,"south":40.835,"east":-112.07,"north":40.842}"#;

    fn new_plan(max_zoom: i32) -> *mut FrameworkBasemapPlan {
        let bbox = c(BBOX);
        let mut plan = null_mut();
        let mut err = null_mut();
        let status = unsafe {
            osm_framework_basemap_plan_new(bbox.as_ptr(), -1, max_zoom, 0.05, &mut plan, &mut err)
        };
        ok(status, err);
        plan
    }

    /// Drives the directory phase through the ABI; returns the plan, ready.
    fn plan_until_ready(src: &[u8], plan: *mut FrameworkBasemapPlan) {
        let first: ByteRange = ok_json(call(|o, e| unsafe {
            osm_framework_basemap_plan_first_request(plan, o, e)
        }));
        let mut queue = vec![first];
        while let Some(r) = queue.pop() {
            let body = serve(src, r);
            let step: Step = ok_json(call(|o, e| unsafe {
                osm_framework_basemap_plan_feed(plan, r.id, body.as_ptr(), body.len(), o, e)
            }));
            match step {
                Step::Fetch(more) => queue.extend(more),
                Step::Wait => assert!(!queue.is_empty()),
                Step::TilesReady(_) => assert!(queue.is_empty()),
            }
        }
        let outstanding: Vec<ByteRange> = ok_json(call(|o, e| unsafe {
            osm_framework_basemap_plan_outstanding(plan, o, e)
        }));
        assert!(outstanding.is_empty());
    }

    fn reference(src: &[u8], dir: &Path, max_zoom: u8) -> Vec<u8> {
        let bbox: BBox = serde_json::from_str(BBOX).unwrap();
        let mut plan = ExtractPlan::new(bbox, None, Some(max_zoom), 0.05).unwrap();
        let mut queue = vec![plan.first_request()];
        while let Some(r) = queue.pop() {
            if let Step::Fetch(more) = plan.feed(r.id, serve(src, r)).unwrap() {
                queue.extend(more)
            }
        }
        let mut asm = plan.into_assembler(dir.join("ref.part")).unwrap();
        for r in asm.remaining() {
            asm.write_range(r.id, serve(src, r)).unwrap();
        }
        asm.finish(dir.join("ref.pmtiles")).unwrap();
        std::fs::read(dir.join("ref.pmtiles")).unwrap()
    }

    #[test]
    fn full_extract_through_the_abi_matches_the_engine() {
        let src = fixture();
        let dir = tempfile::tempdir().unwrap();
        let plan = new_plan(14);
        plan_until_ready(&src, plan);

        let staging = c(dir.path().join("out.part").to_str().unwrap());
        let mut asm = null_mut();
        let mut err = null_mut();
        ok(
            unsafe {
                osm_framework_basemap_plan_into_assembler(
                    plan,
                    staging.as_ptr(),
                    &mut asm,
                    &mut err,
                )
            },
            err,
        );
        // plan is consumed now.
        let remaining: Vec<ByteRange> = ok_json(call(|o, e| unsafe {
            osm_framework_basemap_asm_remaining(asm, o, e)
        }));
        assert!(!remaining.is_empty());
        // Alternate the two write paths: bytes and file.
        for (i, r) in remaining.iter().enumerate() {
            let body = serve(&src, *r);
            let status = if i % 2 == 0 {
                unsafe {
                    osm_framework_basemap_asm_write_range(
                        asm,
                        r.id,
                        body.as_ptr(),
                        body.len(),
                        &mut err,
                    )
                }
            } else {
                let tmp = dir.path().join(format!("range-{}", r.id));
                std::fs::write(&tmp, body).unwrap();
                let path = c(tmp.to_str().unwrap());
                unsafe {
                    osm_framework_basemap_asm_write_range_file(asm, r.id, path.as_ptr(), &mut err)
                }
            };
            ok(status, err);
        }
        let progress: crate::basemap::Progress = ok_json(call(|o, e| unsafe {
            osm_framework_basemap_asm_progress(asm, o, e)
        }));
        assert_eq!(progress.ranges_done, progress.ranges_total);
        let out = dir.path().join("out.pmtiles");
        let out_c = c(out.to_str().unwrap());
        ok(
            unsafe { osm_framework_basemap_asm_finish(asm, out_c.as_ptr(), &mut err) },
            err,
        );
        ok(
            unsafe { osm_framework_basemap_asm_free(asm, &mut err) },
            err,
        );

        assert_eq!(
            std::fs::read(&out).unwrap(),
            reference(&src, dir.path(), 14)
        );
        assert!(!dir.path().join("out.part").exists());

        let info: serde_json::Value = ok_json(call(|o, e| unsafe {
            osm_framework_basemap_info(out_c.as_ptr(), o, e)
        }));
        assert_eq!(info["spec_version"], 3);
        assert_eq!(info["max_zoom"], 14);
        assert_eq!(info["bounds"][0], -112.085);
        assert_eq!(info["file_bytes"], std::fs::metadata(&out).unwrap().len());
    }

    #[test]
    fn handles_are_thread_confined_and_errors_are_reported() {
        let plan = new_plan(-1) as usize;
        // Another thread may not use or free the plan.
        std::thread::spawn(move || {
            let plan = plan as *mut FrameworkBasemapPlan;
            let r = call(|o, e| unsafe { osm_framework_basemap_plan_first_request(plan, o, e) });
            assert_eq!(r.0, -1);
            assert!(r.2.unwrap().contains("different thread"));
            let r = call(|_, e| unsafe { osm_framework_basemap_plan_free(plan, e) });
            assert_eq!(r.0, -1);
        })
        .join()
        .unwrap();
        let plan = plan as *mut FrameworkBasemapPlan;
        // Wrong id and garbage are rejected; the plan stays usable.
        let r = call(|o, e| unsafe {
            osm_framework_basemap_plan_feed(plan, 7, [1u8].as_ptr(), 1, o, e)
        });
        assert_eq!(r.0, -1);
        assert!(r.1.is_none());
        let r =
            call(|o, e| unsafe { osm_framework_basemap_plan_feed(plan, 0, null_mut(), 0, o, e) });
        assert_eq!(r.0, -1, "short header");
        // into_assembler before tiles_ready fails and consumes the plan.
        let dir = tempfile::tempdir().unwrap();
        let staging = c(dir.path().join("x.part").to_str().unwrap());
        let mut asm = null_mut();
        let r = call(|_, e| unsafe {
            osm_framework_basemap_plan_into_assembler(plan, staging.as_ptr(), &mut asm, e)
        });
        assert_eq!(r.0, -1);
        assert!(r.2.unwrap().contains("not ready"));
        assert!(asm.is_null());
        // Bad arguments to plan_new.
        let mut p = null_mut();
        let bad = c(r#"{"west":1}"#);
        let r = call(|_, e| unsafe {
            osm_framework_basemap_plan_new(bad.as_ptr(), -1, 15, 0.05, &mut p, e)
        });
        assert_eq!(r.0, -1);
        assert!(p.is_null());
        let bbox = c(BBOX);
        let r = call(|_, e| unsafe {
            osm_framework_basemap_plan_new(bbox.as_ptr(), -1, 40, 0.05, &mut p, e)
        });
        assert_eq!(r.0, -1);
        // Freeing NULL is a no-op.
        assert_eq!(
            unsafe { osm_framework_basemap_plan_free(null_mut(), null_mut()) },
            0
        );
        assert_eq!(
            unsafe { osm_framework_basemap_asm_free(null_mut(), null_mut()) },
            0
        );
    }

    #[test]
    fn unfinished_assembler_freed_removes_staging_and_info_rejects_bad_files() {
        let src = fixture();
        let dir = tempfile::tempdir().unwrap();
        let plan = new_plan(-1);
        plan_until_ready(&src, plan);
        let staging_path = dir.path().join("y.part");
        let staging = c(staging_path.to_str().unwrap());
        let mut asm = null_mut();
        let mut err = null_mut();
        ok(
            unsafe {
                osm_framework_basemap_plan_into_assembler(
                    plan,
                    staging.as_ptr(),
                    &mut asm,
                    &mut err,
                )
            },
            err,
        );
        let out = c(dir.path().join("y.pmtiles").to_str().unwrap());
        let r = call(|_, e| unsafe { osm_framework_basemap_asm_finish(asm, out.as_ptr(), e) });
        assert_eq!(r.0, -1, "ranges missing");
        // Too long a body (a server ignoring Range) is rejected.
        let remaining: Vec<ByteRange> = ok_json(call(|o, e| unsafe {
            osm_framework_basemap_asm_remaining(asm, o, e)
        }));
        let whole = dir.path().join("whole");
        std::fs::write(&whole, &src).unwrap();
        let whole_c = c(whole.to_str().unwrap());
        let r = call(|_, e| unsafe {
            osm_framework_basemap_asm_write_range_file(asm, remaining[0].id, whole_c.as_ptr(), e)
        });
        assert_eq!(r.0, -1);
        assert!(r.2.unwrap().contains("ignore the Range"));
        assert!(staging_path.exists());
        ok(
            unsafe { osm_framework_basemap_asm_free(asm, &mut err) },
            err,
        );
        assert!(!staging_path.exists());

        // info: truncated archive and non-PMTiles file.
        let truncated = dir.path().join("t.pmtiles");
        std::fs::write(&truncated, &src[..src.len() - 10]).unwrap();
        let t = c(truncated.to_str().unwrap());
        let r = call(|o, e| unsafe { osm_framework_basemap_info(t.as_ptr(), o, e) });
        assert_eq!(r.0, -1);
        assert!(r.2.unwrap().contains("truncated"));
        let r = call(|o, e| unsafe { osm_framework_basemap_info(whole_c.as_ptr(), o, e) });
        assert_eq!(r.0, 0);
        std::fs::write(&truncated, b"<html>not found</html>").unwrap();
        let r = call(|o, e| unsafe { osm_framework_basemap_info(t.as_ptr(), o, e) });
        assert_eq!(r.0, -1);
    }
}
