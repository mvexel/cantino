//! Cross-platform golden parity corpus: the reference runner.
//!
//! `tests/parity/calls.json` is an ordered list of calls; this runner executes
//! each one through the **C ABI** (the same `extern "C"` functions the Kotlin
//! JNI layer and the Swift adapter call, declared below from
//! `include/cantino.h`, not the internal Rust API), canonicalises the outcome
//! and compares the whole result file byte for byte with
//! `tests/parity/expected.json`. The Kotlin and Swift runners do the same
//! through their public APIs and must produce the identical file. The
//! contract (call shapes, outcome envelope, canonical JSON) is
//! `tests/parity/README.md`; keep this file and the README in step.
//!
//! Regenerate the golden file after an intended behaviour change with
//! `UPDATE_PARITY=1 cargo test --test parity` and review the diff: every
//! changed line is a behaviour change every platform will see.
use serde_json::{Map, Value, json};
use std::{
    collections::{HashMap, HashSet},
    ffi::{CStr, CString, c_char},
    path::{Path, PathBuf},
    ptr::{null, null_mut},
};

// Links the library, so the `#[no_mangle]` symbols declared below resolve
// against the crate's rlib (the same object code as the shipped cdylib and
// staticlib).
use cantino as _;

/// Opaque handle types, as in the header (`typedef struct ... ;`).
#[repr(C)]
struct CantinoStore {
    _private: [u8; 0],
}
#[repr(C)]
struct CantinoBasemapPlan {
    _private: [u8; 0],
}
#[repr(C)]
struct CantinoBasemapAssembler {
    _private: [u8; 0],
}

// The C ABI exactly as `include/cantino.h` declares it. Declaring it here
// (rather than calling `cantino::...` paths, which are private anyway) makes
// this test a foreign caller like the platform adapters.
unsafe extern "C" {
    fn cantino_last_error_code() -> i32;
    fn cantino_open(
        path: *const c_char,
        store: *mut *mut CantinoStore,
        error: *mut *mut c_char,
    ) -> i32;
    fn cantino_close(store: *mut CantinoStore, error: *mut *mut c_char) -> i32;
    fn cantino_free(value: *mut c_char);
    fn cantino_get(
        store: *mut CantinoStore,
        kind: i32,
        id: i64,
        json: *mut *mut c_char,
        error: *mut *mut c_char,
    ) -> i32;
    fn cantino_get_many(
        store: *mut CantinoStore,
        request: *const c_char,
        json: *mut *mut c_char,
        error: *mut *mut c_char,
    ) -> i32;
    fn cantino_way_coordinates(
        store: *mut CantinoStore,
        way_id: i64,
        json: *mut *mut c_char,
        error: *mut *mut c_char,
    ) -> i32;
    fn cantino_representative_point(
        store: *mut CantinoStore,
        kind: i32,
        id: i64,
        json: *mut *mut c_char,
        error: *mut *mut c_char,
    ) -> i32;
    fn cantino_query(
        store: *mut CantinoStore,
        request: *const c_char,
        json: *mut *mut c_char,
        error: *mut *mut c_char,
    ) -> i32;
    fn cantino_import(
        input: *const c_char,
        destination: *const c_char,
        options: *const c_char,
        report: *mut *mut c_char,
        error: *mut *mut c_char,
    ) -> i32;
    fn cantino_slice_job_request(
        base: *const c_char,
        bbox: *const c_char,
        name: *const c_char,
        json: *mut *mut c_char,
        error: *mut *mut c_char,
    ) -> i32;
    fn cantino_slice_job(
        base: *const c_char,
        response: *const c_char,
        json: *mut *mut c_char,
        error: *mut *mut c_char,
    ) -> i32;
    fn cantino_slice_progress(
        status: *const c_char,
        json: *mut *mut c_char,
        error: *mut *mut c_char,
    ) -> i32;
    fn cantino_basemap_plan_new(
        bbox: *const c_char,
        min_zoom: i32,
        max_zoom: i32,
        overfetch: f64,
        plan: *mut *mut CantinoBasemapPlan,
        error: *mut *mut c_char,
    ) -> i32;
    fn cantino_basemap_plan_first_request(
        plan: *mut CantinoBasemapPlan,
        json: *mut *mut c_char,
        error: *mut *mut c_char,
    ) -> i32;
    fn cantino_basemap_plan_feed(
        plan: *mut CantinoBasemapPlan,
        id: u64,
        bytes: *const u8,
        len: usize,
        json: *mut *mut c_char,
        error: *mut *mut c_char,
    ) -> i32;
    fn cantino_basemap_plan_outstanding(
        plan: *mut CantinoBasemapPlan,
        json: *mut *mut c_char,
        error: *mut *mut c_char,
    ) -> i32;
    fn cantino_basemap_plan_into_assembler(
        plan: *mut CantinoBasemapPlan,
        staging: *const c_char,
        assembler: *mut *mut CantinoBasemapAssembler,
        error: *mut *mut c_char,
    ) -> i32;
    fn cantino_basemap_plan_free(plan: *mut CantinoBasemapPlan, error: *mut *mut c_char) -> i32;
    fn cantino_basemap_asm_write_range(
        assembler: *mut CantinoBasemapAssembler,
        id: u64,
        bytes: *const u8,
        len: usize,
        error: *mut *mut c_char,
    ) -> i32;
    fn cantino_basemap_asm_remaining(
        assembler: *mut CantinoBasemapAssembler,
        json: *mut *mut c_char,
        error: *mut *mut c_char,
    ) -> i32;
    fn cantino_basemap_asm_progress(
        assembler: *mut CantinoBasemapAssembler,
        json: *mut *mut c_char,
        error: *mut *mut c_char,
    ) -> i32;
    fn cantino_basemap_asm_finish(
        assembler: *mut CantinoBasemapAssembler,
        output: *const c_char,
        error: *mut *mut c_char,
    ) -> i32;
    fn cantino_basemap_asm_free(
        assembler: *mut CantinoBasemapAssembler,
        error: *mut *mut c_char,
    ) -> i32;
    fn cantino_basemap_info(
        path: *const c_char,
        json: *mut *mut c_char,
        error: *mut *mut c_char,
    ) -> i32;
    fn cantino_area_validate_id(area_id: *const c_char, error: *mut *mut c_char) -> i32;
    fn cantino_area_layout(
        root: *const c_char,
        area_id: *const c_char,
        work_id: *const c_char,
        json: *mut *mut c_char,
        error: *mut *mut c_char,
    ) -> i32;
    fn cantino_area_recover(
        root: *const c_char,
        area_id: *const c_char,
        error: *mut *mut c_char,
    ) -> i32;
    fn cantino_area_published(
        root: *const c_char,
        area_id: *const c_char,
        json: *mut *mut c_char,
        error: *mut *mut c_char,
    ) -> i32;
    fn cantino_classify_failure(
        input: *const c_char,
        json: *mut *mut c_char,
        error: *mut *mut c_char,
    ) -> i32;
}

// ------------------------------------------------------- canonical JSON --

/// Writes `value` in the corpus's canonical form (README "Canonical form"):
/// object keys sorted by Unicode code point (= UTF-8 byte order, which is
/// what `str`'s `Ord` compares), no insignificant whitespace, integers in
/// plain decimal, floats as serde_json writes them (shortest round-trip,
/// integral values with `.0`), strings escaped as serde_json does (only `"`,
/// `\` and U+0000..U+001F; non-ASCII stays raw UTF-8; `/` is not escaped).
///
/// Written out by hand instead of relying on `serde_json::to_string` so the
/// key order does not depend on whether some dependency turns on
/// serde_json's `preserve_order` feature.
fn canonical(value: &Value) -> String {
    let mut out = String::new();
    write_canonical(value, &mut out);
    out
}
fn write_canonical(value: &Value, out: &mut String) {
    match value {
        // serde_json's Number Display is its serialiser's itoa/ryu output.
        Value::Null | Value::Bool(_) | Value::Number(_) => out.push_str(&value.to_string()),
        Value::String(text) => out.push_str(&serde_json::to_string(text).unwrap()),
        Value::Array(items) => {
            out.push('[');
            for (i, item) in items.iter().enumerate() {
                if i > 0 {
                    out.push(',');
                }
                write_canonical(item, out);
            }
            out.push(']');
        }
        Value::Object(map) => {
            let mut keys: Vec<&String> = map.keys().collect();
            keys.sort();
            out.push('{');
            for (i, key) in keys.into_iter().enumerate() {
                if i > 0 {
                    out.push(',');
                }
                out.push_str(&serde_json::to_string(key).unwrap());
                out.push(':');
                write_canonical(&map[key], out);
            }
            out.push('}');
        }
    }
}

/// The `kind` name of a `CANTINO_ERROR_*` code (README "Outcomes").
fn kind_name(code: i32) -> &'static str {
    match code {
        1 => "invalid_argument",
        2 => "invalid_file",
        3 => "io",
        4 => "wrong_thread",
        5 => "internal",
        _ => panic!("unknown or missing error code {code} after a failed call"),
    }
}

// ------------------------------------------------------------- outcomes --

/// Turns one ABI call's (status, result buffer, error buffer) into the
/// outcome envelope, frees both buffers, and asserts the buffer contract
/// from the header: a result only with status 0, an error message exactly
/// with status -1. `code` must be `cantino_last_error_code()` read right
/// after the call, on the calling thread.
fn outcome(status: i32, code: i32, out: *mut c_char, error: *mut c_char) -> Value {
    // Copy and free first so a failed assertion below does not leak.
    let take = |buffer: *mut c_char| {
        (!buffer.is_null()).then(|| {
            // SAFETY: a non-NULL buffer is a NUL-terminated string returned
            // by the ABI; it is freed exactly once, here.
            let text = unsafe { CStr::from_ptr(buffer) }
                .to_str()
                .unwrap()
                .to_owned();
            unsafe { cantino_free(buffer) };
            text
        })
    };
    let (result, message) = (take(out), take(error));
    match status {
        0 => {
            assert_eq!(code, 0, "success must reset the error code");
            assert!(message.is_none(), "error message with status 0");
            let value = result.map_or(Value::Null, |text| serde_json::from_str(&text).unwrap());
            json!({ "ok": value })
        }
        1 => {
            assert_eq!(code, 0);
            assert!(
                result.is_none() && message.is_none(),
                "status 1 carries no buffers"
            );
            json!({ "missing": true })
        }
        -1 => {
            assert!(result.is_none(), "result buffer with status -1");
            assert!(
                message.is_some_and(|m| !m.is_empty()),
                "status -1 needs a message"
            );
            // The message is deliberately dropped: it is for developers and
            // not part of the contract.
            json!({ "error": { "code": code, "kind": kind_name(code) } })
        }
        other => panic!("undocumented status {other}"),
    }
}

/// Calls a function of the shape `f(..., char **json, char **error)`.
fn call_json(function: impl FnOnce(*mut *mut c_char, *mut *mut c_char) -> i32) -> Value {
    let (mut out, mut error) = (null_mut(), null_mut());
    let status = function(&mut out, &mut error);
    let code = unsafe { cantino_last_error_code() };
    outcome(status, code, out, error)
}

/// Calls a function of the shape `f(..., char **error)` (no result JSON).
fn call_status(function: impl FnOnce(*mut *mut c_char) -> i32) -> Value {
    let mut error = null_mut();
    let status = function(&mut error);
    let code = unsafe { cantino_last_error_code() };
    outcome(status, code, null_mut(), error)
}

// --------------------------------------------------------------- runner --

/// Per-run state: a fresh scratch directory for every `scratch:` path, the
/// store handles opened by `open` calls (by name), and the repository root
/// that every other path is relative to.
struct Runner {
    root: PathBuf,
    scratch: tempfile::TempDir,
    stores: HashMap<String, *mut CantinoStore>,
}

/// A C string argument; `None` (JSON null) is a NULL pointer.
struct Arg(Option<CString>);
impl Arg {
    fn ptr(&self) -> *const c_char {
        self.0.as_ref().map_or(null(), |s| s.as_ptr())
    }
}
fn c(text: &str) -> CString {
    CString::new(text).unwrap()
}

impl Runner {
    /// Resolves a corpus path: `scratch:<name>` lives in this run's scratch
    /// directory, anything else is relative to the repository root.
    fn path(&self, path: &str) -> PathBuf {
        match path.strip_prefix("scratch:") {
            Some(name) => self.scratch.path().join(name),
            None => self.root.join(path),
        }
    }
    fn path_arg(&self, value: &Value) -> Arg {
        Arg(value.as_str().map(|p| c(self.path(p).to_str().unwrap())))
    }
    /// A JSON argument: a string is passed verbatim (raw text, for malformed
    /// input), null is NULL, anything else is serialised compactly.
    fn json_arg(value: &Value) -> Arg {
        Arg(match value {
            Value::Null => None,
            Value::String(raw) => Some(c(raw)),
            other => Some(c(&other.to_string())),
        })
    }
    fn text_arg(value: &Value) -> Arg {
        Arg(value.as_str().map(c))
    }
    /// The named store handle; JSON null (or an absent `area`) is NULL.
    fn store(&self, args: &Value) -> *mut CantinoStore {
        match args.get("area").and_then(Value::as_str) {
            Some(name) => *self
                .stores
                .get(name)
                .unwrap_or_else(|| panic!("area {name} not open")),
            None => null_mut(),
        }
    }

    fn run(&mut self, op: &str, args: &Value) -> Value {
        let int = |key: &str| {
            args[key]
                .as_i64()
                .unwrap_or_else(|| panic!("{op}: {key} must be an integer"))
        };
        match op {
            "import" => {
                let (input, destination) = (
                    self.path_arg(&args["input"]),
                    self.path_arg(&args["destination"]),
                );
                let options = Self::json_arg(args.get("options").unwrap_or(&Value::Null));
                let mut result = call_json(|out, error| unsafe {
                    cantino_import(input.ptr(), destination.ptr(), options.ptr(), out, error)
                });
                // database_bytes depends on SQLite's page allocation and is
                // not part of the contract (README); only the counts are.
                if let Some(report) = result.get_mut("ok").and_then(Value::as_object_mut) {
                    report
                        .remove("database_bytes")
                        .expect("import report has database_bytes");
                }
                result
            }
            "open" => {
                let path = self.path_arg(&args["path"]);
                let mut store = null_mut();
                let result =
                    call_status(|error| unsafe { cantino_open(path.ptr(), &mut store, error) });
                if result.get("ok").is_some() {
                    let name = args["area"].as_str().unwrap().to_owned();
                    assert!(
                        self.stores.insert(name, store).is_none(),
                        "area opened twice"
                    );
                } else {
                    assert!(store.is_null(), "failed open must leave *store NULL");
                }
                result
            }
            "close" => {
                let store = self.store(args);
                let result = call_status(|error| unsafe { cantino_close(store, error) });
                if result.get("ok").is_some() {
                    self.stores.remove(args["area"].as_str().unwrap());
                }
                result
            }
            "get" => {
                let store = self.store(args);
                let (kind, id) = (int("kind") as i32, int("id"));
                call_json(|out, error| unsafe { cantino_get(store, kind, id, out, error) })
            }
            "get_from_other_thread" => {
                // The handle crosses to a fresh thread as an address; the
                // ABI must refuse it there (WrongThread) without touching
                // it. The error code is per thread, so the outcome is built
                // on the foreign thread.
                let address = self.store(args) as usize;
                let (kind, id) = (int("kind") as i32, int("id"));
                std::thread::spawn(move || {
                    let store = address as *mut CantinoStore;
                    call_json(|out, error| unsafe { cantino_get(store, kind, id, out, error) })
                })
                .join()
                .unwrap()
            }
            "get_many" => {
                let store = self.store(args);
                // `repeat` {"id","count"} stands for an array of `count`
                // copies of `id`, so the batch-cap case need not spell out
                // 10001 IDs in calls.json.
                let request = match args.get("repeat") {
                    Some(repeat) => {
                        let count = repeat["count"].as_u64().unwrap() as usize;
                        Self::json_arg(&Value::Array(vec![repeat["id"].clone(); count]))
                    }
                    None => Self::json_arg(&args["request"]),
                };
                call_json(|out, error| unsafe {
                    cantino_get_many(store, request.ptr(), out, error)
                })
            }
            "query" => {
                let store = self.store(args);
                let request = Self::json_arg(&args["request"]);
                call_json(|out, error| unsafe { cantino_query(store, request.ptr(), out, error) })
            }
            "way_coordinates" => {
                let store = self.store(args);
                let id = int("id");
                call_json(|out, error| unsafe { cantino_way_coordinates(store, id, out, error) })
            }
            "representative_point" => {
                let store = self.store(args);
                let (kind, id) = (int("kind") as i32, int("id"));
                call_json(|out, error| unsafe {
                    cantino_representative_point(store, kind, id, out, error)
                })
            }
            "slice_job_request" => {
                let (base, bbox, name) = (
                    Self::text_arg(&args["base"]),
                    Self::json_arg(&args["bbox"]),
                    Self::text_arg(&args["name"]),
                );
                call_json(|out, error| unsafe {
                    cantino_slice_job_request(base.ptr(), bbox.ptr(), name.ptr(), out, error)
                })
            }
            "slice_job" => {
                let (base, response) = (
                    Self::text_arg(&args["base"]),
                    Self::text_arg(&args["response"]),
                );
                call_json(|out, error| unsafe {
                    cantino_slice_job(base.ptr(), response.ptr(), out, error)
                })
            }
            "slice_progress" => {
                let status = Self::text_arg(&args["status"]);
                call_json(|out, error| unsafe { cantino_slice_progress(status.ptr(), out, error) })
            }
            "basemap_info" => {
                let path = self.path_arg(&args["path"]);
                call_json(|out, error| unsafe { cantino_basemap_info(path.ptr(), out, error) })
            }
            "basemap_plan_new" => {
                let mut plan = null_mut();
                let result = plan_new(args, &mut plan);
                if result.get("ok").is_some() {
                    assert_eq!(
                        call_status(|error| unsafe { cantino_basemap_plan_free(plan, error) }),
                        json!({"ok":null})
                    );
                } else {
                    assert!(plan.is_null());
                }
                result
            }
            "area_validate_id" => {
                let area_id = Self::text_arg(&args["area_id"]);
                call_status(|error| unsafe { cantino_area_validate_id(area_id.ptr(), error) })
            }
            "area_layout" => {
                let root = self.path(args["root"].as_str().unwrap());
                let (root_arg, area_id, work_id) = (
                    Arg(Some(c(root.to_str().unwrap()))),
                    Self::text_arg(&args["area_id"]),
                    Self::text_arg(&args["work_id"]),
                );
                let result = call_json(|out, error| unsafe {
                    cantino_area_layout(root_arg.ptr(), area_id.ptr(), work_id.ptr(), out, error)
                });
                relative_paths(result, &root)
            }
            "area_published" | "area_recover" => {
                let root = self.area_root(args);
                let (root_arg, area_id) = (
                    Arg(Some(c(root.to_str().unwrap()))),
                    Self::text_arg(&args["area_id"]),
                );
                let result = if op == "area_published" {
                    call_json(|out, error| unsafe {
                        cantino_area_published(root_arg.ptr(), area_id.ptr(), out, error)
                    })
                } else {
                    call_status(|error| unsafe {
                        cantino_area_recover(root_arg.ptr(), area_id.ptr(), error)
                    })
                };
                json!({ "result": relative_paths(result, &root), "files": file_sizes(&root) })
            }
            "classify_failure" => {
                let input = Self::json_arg(&args["input"]);
                call_json(|out, error| unsafe { cantino_classify_failure(input.ptr(), out, error) })
            }
            "basemap_bad_feed" => self.basemap_bad_feed(args),
            "basemap_extract" => self.basemap_extract(args),
            other => panic!("unknown op {other}"),
        }
    }

    /// The areas root of an `area_published`/`area_recover` call: `root`
    /// (a `scratch:` path, fresh per call), first filled with a copy of the
    /// `fixture` directory unless that is null (then the root stays absent).
    fn area_root(&self, args: &Value) -> PathBuf {
        let root = self.path(args["root"].as_str().expect("area ops need a root"));
        assert!(
            !root.exists(),
            "area roots must be fresh: {}",
            root.display()
        );
        if let Some(fixture) = args["fixture"].as_str() {
            copy_tree(&self.path(fixture), &root);
        }
        root
    }

    /// `basemap_bad_feed`: plan_new, first_request, then each listed feed of
    /// `source[offset..offset+length]` under `id`, recording every outcome;
    /// the plan is freed afterwards.
    fn basemap_bad_feed(&self, args: &Value) -> Value {
        let source = std::fs::read(self.path(args["source"].as_str().unwrap())).unwrap();
        let mut plan = null_mut();
        let created = plan_new(args, &mut plan);
        assert_eq!(
            created,
            json!({"ok":null}),
            "basemap_bad_feed needs a valid plan"
        );
        let first =
            call_json(|out, error| unsafe { cantino_basemap_plan_first_request(plan, out, error) });
        let feeds: Vec<Value> = args["feeds"]
            .as_array()
            .unwrap()
            .iter()
            .map(|feed| {
                let bytes = serve(
                    &source,
                    feed["offset"].as_u64().unwrap(),
                    feed["length"].as_u64().unwrap(),
                );
                let id = feed["id"].as_u64().unwrap();
                call_json(|out, error| unsafe {
                    cantino_basemap_plan_feed(plan, id, bytes.as_ptr(), bytes.len(), out, error)
                })
            })
            .collect();
        assert_eq!(
            call_status(|error| unsafe { cantino_basemap_plan_free(plan, error) }),
            json!({"ok":null})
        );
        json!({ "first_request": first, "feeds": feeds })
    }

    /// `basemap_extract`: the whole sans-IO extract against a local PMTiles
    /// file standing in for the HTTP server, in the fixed order the README
    /// defines (FIFO request queue, tile ranges written in `remaining`
    /// order), recording every engine answer.
    fn basemap_extract(&self, args: &Value) -> Value {
        let source = std::fs::read(self.path(args["source"].as_str().unwrap())).unwrap();
        let mut plan = null_mut();
        let created = plan_new(args, &mut plan);
        assert_eq!(
            created,
            json!({"ok":null}),
            "basemap_extract needs a valid plan"
        );
        let first =
            call_json(|out, error| unsafe { cantino_basemap_plan_first_request(plan, out, error) });
        let mut queue = std::collections::VecDeque::from([first["ok"].clone()]);
        let mut feeds = Vec::new();
        let mut ready = false;
        // Plan phase: answer requests first in, first out until tiles_ready
        // (or an engine error, which is fatal for the plan).
        while let Some(range) = queue.pop_front() {
            let bytes = serve(
                &source,
                range["offset"].as_u64().unwrap(),
                range["length"].as_u64().unwrap(),
            );
            let id = range["id"].as_u64().unwrap();
            let step = call_json(|out, error| unsafe {
                cantino_basemap_plan_feed(plan, id, bytes.as_ptr(), bytes.len(), out, error)
            });
            let ok = step["ok"].clone();
            feeds.push(json!({ "id": id, "step": step }));
            if let Some(more) = ok.get("fetch").and_then(Value::as_array) {
                queue.extend(more.iter().cloned());
            } else if ok.get("tiles_ready").is_some() {
                ready = true;
                break;
            } else if step.get("error").is_some() {
                break;
            } else {
                assert_eq!(ok, json!("wait"), "unexpected step");
            }
        }
        let outstanding =
            call_json(|out, error| unsafe { cantino_basemap_plan_outstanding(plan, out, error) });
        if !ready {
            // The plan failed: free it and stop; there is nothing to assemble.
            let free = call_status(|error| unsafe { cantino_basemap_plan_free(plan, error) });
            return json!({ "first_request": first, "feeds": feeds, "outstanding": outstanding, "free": free });
        }

        // Assembly phase. The plan is consumed by into_assembler.
        let staging = c(self.scratch.path().join("basemap.part").to_str().unwrap());
        let output_path = self.scratch.path().join("basemap.pmtiles");
        let output = c(output_path.to_str().unwrap());
        let mut assembler = null_mut();
        let into = call_status(|error| unsafe {
            cantino_basemap_plan_into_assembler(plan, staging.as_ptr(), &mut assembler, error)
        });
        assert_eq!(into, json!({"ok":null}));
        let remaining =
            call_json(|out, error| unsafe { cantino_basemap_asm_remaining(assembler, out, error) });
        let progress_before =
            call_json(|out, error| unsafe { cantino_basemap_asm_progress(assembler, out, error) });
        // Finishing with ranges missing must be refused.
        let finish_early = call_status(|error| unsafe {
            cantino_basemap_asm_finish(assembler, output.as_ptr(), error)
        });
        let writes: Vec<Value> = remaining["ok"]
            .as_array()
            .unwrap()
            .iter()
            .map(|range| {
                let bytes = serve(
                    &source,
                    range["offset"].as_u64().unwrap(),
                    range["length"].as_u64().unwrap(),
                );
                let id = range["id"].as_u64().unwrap();
                call_status(|error| unsafe {
                    cantino_basemap_asm_write_range(
                        assembler,
                        id,
                        bytes.as_ptr(),
                        bytes.len(),
                        error,
                    )
                })
            })
            .collect();
        let progress_after =
            call_json(|out, error| unsafe { cantino_basemap_asm_progress(assembler, out, error) });
        let finish = call_status(|error| unsafe {
            cantino_basemap_asm_finish(assembler, output.as_ptr(), error)
        });
        let free = call_status(|error| unsafe { cantino_basemap_asm_free(assembler, error) });
        let info =
            call_json(|out, error| unsafe { cantino_basemap_info(output.as_ptr(), out, error) });
        json!({
            "first_request": first,
            "feeds": feeds,
            "outstanding": outstanding,
            "into_assembler": into,
            "remaining": remaining,
            "progress_before": progress_before,
            "finish_early": finish_early,
            "writes": writes,
            "progress_after": progress_after,
            "finish": finish,
            "free": free,
            "info": info,
        })
    }
}

/// `cantino_basemap_plan_new` with `bbox`, `min_zoom`, `max_zoom`,
/// `overfetch` from the call's args.
fn plan_new(args: &Value, plan: &mut *mut CantinoBasemapPlan) -> Value {
    let bbox = Runner::json_arg(&args["bbox"]);
    let (min_zoom, max_zoom) = (
        args["min_zoom"].as_i64().unwrap() as i32,
        args["max_zoom"].as_i64().unwrap() as i32,
    );
    let overfetch = args["overfetch"].as_f64().unwrap();
    call_status(|error| unsafe {
        cantino_basemap_plan_new(bbox.ptr(), min_zoom, max_zoom, overfetch, plan, error)
    })
}

fn copy_tree(from: &Path, to: &Path) {
    std::fs::create_dir_all(to).unwrap();
    for entry in std::fs::read_dir(from).unwrap() {
        let entry = entry.unwrap();
        let target = to.join(entry.file_name());
        if entry.file_type().unwrap().is_dir() {
            copy_tree(&entry.path(), &target);
        } else {
            std::fs::copy(entry.path(), target).unwrap();
        }
    }
}

/// Projection for area ops (README): every string in `value` that is a path
/// under `root` becomes relative to it (`root` itself becomes `"."`), so the
/// outcome does not depend on where the runner's scratch directory is.
fn relative_paths(value: Value, root: &Path) -> Value {
    let prefix = root.to_str().unwrap();
    match value {
        Value::String(text) if text == prefix => Value::String(".".into()),
        Value::String(text) => match text.strip_prefix(prefix).and_then(|t| t.strip_prefix('/')) {
            Some(relative) => Value::String(relative.into()),
            None => Value::String(text),
        },
        Value::Array(items) => Value::Array(
            items
                .into_iter()
                .map(|item| relative_paths(item, root))
                .collect(),
        ),
        Value::Object(map) => Value::Object(
            map.into_iter()
                .map(|(key, item)| (key, relative_paths(item, root)))
                .collect(),
        ),
        other => other,
    }
}

/// Every regular file under `root` (relative path, `/`-separated, to its
/// size in bytes), or `null` when `root` does not exist.
fn file_sizes(root: &Path) -> Value {
    fn walk(root: &Path, at: &Path, out: &mut Map<String, Value>) {
        for entry in std::fs::read_dir(at).unwrap() {
            let path = entry.unwrap().path();
            if path.is_dir() {
                walk(root, &path, out);
            } else {
                let relative = path
                    .strip_prefix(root)
                    .unwrap()
                    .to_str()
                    .unwrap()
                    .to_owned();
                out.insert(relative, json!(std::fs::metadata(&path).unwrap().len()));
            }
        }
    }
    if !root.exists() {
        return Value::Null;
    }
    let mut out = Map::new();
    walk(root, root, &mut out);
    Value::Object(out)
}

/// What an HTTP server answers to `Range: bytes=offset-(offset+length-1)`:
/// the bytes that exist, so a range past the end comes back short.
fn serve(source: &[u8], offset: u64, length: u64) -> &[u8] {
    let start = (offset as usize).min(source.len());
    let end = ((offset + length) as usize).min(source.len());
    &source[start..end]
}

/// The expected-file layout (README "Result file"): a JSON array with one
/// canonical `{"id","result"}` object per line.
fn result_file(results: &[(String, Value)]) -> String {
    let lines: Vec<String> = results
        .iter()
        .map(|(id, result)| canonical(&json!({ "id": id, "result": result })))
        .collect();
    format!("[\n{}\n]\n", lines.join(",\n"))
}

fn corpus() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/parity")
}

/// Runs calls.json and returns (id, outcome) in order.
fn run_corpus() -> Vec<(String, Value)> {
    let calls: Vec<Value> =
        serde_json::from_str(&std::fs::read_to_string(corpus().join("calls.json")).unwrap())
            .unwrap();
    let mut runner = Runner {
        root: PathBuf::from(env!("CARGO_MANIFEST_DIR")),
        scratch: tempfile::tempdir().unwrap(),
        stores: HashMap::new(),
    };
    let mut seen = HashSet::new();
    let results = calls
        .iter()
        .map(|call| {
            let call: &Map<String, Value> = call.as_object().expect("a call is an object");
            let id = call["id"].as_str().expect("id").to_owned();
            assert!(seen.insert(id.clone()), "duplicate call id {id}");
            let result = runner.run(call["op"].as_str().expect("op"), &call["args"]);
            (id, result)
        })
        .collect();
    assert!(
        runner.stores.is_empty(),
        "calls.json must close every area it opens"
    );
    results
}

#[test]
fn canonical_form_rules() {
    // Pins the README's canonical-form rules on the Rust side.
    let value: Value = serde_json::from_str(
        r#"{"b":1,"a":[1.0,0.25,-112.09,0.3333333333333333,-5],"ｚ":"x","😀":"y","Z":"q\"\\\n\t/é","n":null}"#,
    )
    .unwrap();
    assert_eq!(
        canonical(&value),
        r#"{"Z":"q\"\\\n\t/é","a":[1.0,0.25,-112.09,0.3333333333333333,-5],"b":1,"n":null,"ｚ":"x","😀":"y"}"#
    );
    assert_eq!(canonical(&json!("\u{1}")), r#""\u0001""#);
}

#[test]
fn import_is_deterministic() {
    // Same input -> identical query outputs. The .sqlite files themselves
    // are not required to be byte-identical (and are not compared): the
    // corpus contract is outputs only. Two full corpus runs, each with its
    // own fresh imports, must agree exactly.
    assert_eq!(result_file(&run_corpus()), result_file(&run_corpus()));
}

#[test]
fn parity_corpus_matches_expected() {
    let actual = result_file(&run_corpus());
    let path = corpus().join("expected.json");
    if std::env::var_os("UPDATE_PARITY").is_some() {
        std::fs::write(&path, &actual).unwrap();
        eprintln!("wrote {}", path.display());
        return;
    }
    let expected = std::fs::read_to_string(&path).unwrap_or_else(|_| {
        panic!(
            "{} missing; run UPDATE_PARITY=1 cargo test --test parity",
            path.display()
        )
    });
    if actual != expected {
        // Name the differing lines (one line per call) before failing.
        let differing: Vec<String> = actual
            .lines()
            .zip(expected.lines())
            .filter(|(a, e)| a != e)
            .take(10)
            .map(|(a, e)| format!("expected {e}\n  actual {a}"))
            .collect();
        panic!(
            "parity corpus differs from expected.json ({} vs {} lines); if intended, regenerate with \
             UPDATE_PARITY=1 cargo test --test parity and review the diff:\n{}",
            actual.lines().count(),
            expected.lines().count(),
            differing.join("\n")
        );
    }
}
