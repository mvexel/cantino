//! On-disk layout and crash-safe publication of an app's downloaded areas.
//!
//! One root directory per app holds every published area and everything
//! needed to replace one safely. Both platform adapters (Kotlin
//! `AreaStorage`, the Swift adapter) delegate here through the C ABI
//! (`cantino_area_*`), so staging, the commit journal, recovery after a kill
//! and the reading of the published area behave identically by construction.
//!
//! ```text
//! <root>/<areaId>.sqlite        published OSM data
//! <root>/<areaId>.pmtiles       published basemap (only when the version has one)
//! <root>/<areaId>.json          metadata sidecar describing both
//! <root>/<areaId>.commit        commit journal, present only while publishing
//! <root>/<areaId>.lock          lock file (empty; new after 0.2.0, see "Locking")
//! <root>/.staging/<areaId>/<workId>/
//!     area.sqlite, basemap.pmtiles, area.json   the next version, built by run <workId>
//! ```
//!
//! **Compatibility.** Every name above except `<areaId>.lock`, the journal
//! text and the sidecar JSON are exactly what Cantino 0.2.0's Kotlin
//! `AreaStorage` wrote, so an app upgrading from 0.2.0 reads and recovers its
//! areas unchanged (tests: `tests/area_storage.rs` over
//! `tests/fixtures/area-storage-0.2.0/`). The lock file is new and ignored by
//! 0.2.0 (it never lists the directory), so a downgrade still works too.
//!
//! ## Publishing (the area is data + basemap + sidecar)
//!
//! A run (identified by a work ID, a UUID: WorkManager's on Android) builds
//! the complete next version in its own staging directory, on the same file
//! system as the published files so each part moves by `rename(2)`. Three
//! files cannot be renamed atomically together, so [`AreaStorage::commit`]
//! uses a roll-forward journal:
//!
//! 1. Under the area lock, a journal left by a dead process is rolled forward
//!    first; then the caller's hook runs at [`CommitStage::BeforeCommit`]
//!    (the adapter's last cancellation check). If it says stop, nothing has
//!    changed.
//! 2. `<areaId>.commit` is written atomically and durably (temporary file,
//!    fsync, rename, directory fsync). **The rename is the commit point.**
//!    From there on the new version is published, whatever happens to the
//!    process.
//! 3. Roll forward: rename basemap (or delete the old one when the new
//!    version has none), data, sidecar into place; fsync the directory;
//!    delete the journal and the staging directory.
//!
//! Every reader ([`AreaStorage::published`]) and every new run
//! ([`AreaStorage::prepare_staging`]) first finishes step 3 for a journal
//! left by a dead process ([`AreaStorage::recover`]). Step 3 is idempotent:
//! a part whose staged file is gone was already moved.
//!
//! ## Locking
//!
//! Every operation that reads or changes published files of an area holds
//! that area's lock, so nobody going through this module observes a
//! half-renamed area: a reader sees the old version or waits (milliseconds)
//! for the new one. The lock has two layers:
//!
//! - **In-process** (always): one lock per lock-file path for the whole
//!   process, like 0.2.0's Kotlin `synchronized` lock. It cannot fail, so no
//!   operation gained a failure mode by moving here.
//! - **Cross-process** (best effort): an exclusive `flock(2)` on
//!   `<areaId>.lock`, taken after the in-process lock. It extends the
//!   guarantee to other processes of the same app (an iOS app extension
//!   reading the area, a multi-process Android app). `flock` locks belong to
//!   the open file description, are released by the kernel when a process
//!   dies (no stale locks after a kill) and work on Android and Darwin. If
//!   the lock file cannot be opened (read-only or full file system on the
//!   very first use) the operation proceeds under the in-process lock alone,
//!   which is exactly 0.2.0's guarantee.
//!
//! The lock is not reentrant: the commit hook must not call back into this
//! module for the same area.
//!
//! ## Durability
//!
//! Same fsync points as 0.2.0: each atomically written file is fsynced
//! before its rename; the root directory is fsynced after the journal rename
//! and after the roll-forward renames (best effort: a failing directory
//! fsync is ignored, as on 0.2.0). One addition: the staging directory is
//! fsynced before the journal is written, so a power loss right after the
//! commit point cannot lose a staged file whose rename into staging was not
//! yet durable (process kills were already safe without it).
use crate::{Error, Result};
use serde::Serialize;
use serde_json::Value;
use std::{
    collections::HashSet,
    fs::{self, File, OpenOptions},
    hash::{BuildHasher, Hasher, RandomState},
    io::{self, Write},
    path::{Path, PathBuf},
    sync::{Condvar, Mutex, MutexGuard, OnceLock},
    time::{SystemTime, UNIX_EPOCH},
};

/// Longest area ID, in bytes (all allowed characters are ASCII).
pub const MAX_AREA_ID_LEN: usize = 64;

/// Area IDs become file names, so they are restricted to a safe alphabet:
/// 1 to 64 characters from `[A-Za-z0-9_-]` (0.2.0's Kotlin regex, unchanged).
/// Anything else is an invalid argument.
pub fn validate_area_id(area_id: &str) -> Result<()> {
    let valid = (1..=MAX_AREA_ID_LEN).contains(&area_id.len())
        && area_id
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || byte == b'_' || byte == b'-');
    if valid {
        Ok(())
    } else {
        Err(Error::Invalid(format!(
            "areaId must match [A-Za-z0-9_-]{{1,64}}: {area_id}"
        )))
    }
}

/// Validates a work ID (a UUID in its canonical 8-4-4-4-12 hex form, either
/// case) and returns it in lower case. Lower case is what Java's
/// `UUID.toString()` writes, so staging directory names stay the ones 0.2.0
/// used even when Swift passes `UUID().uuidString` (upper case).
pub fn normalize_work_id(work_id: &str) -> Result<String> {
    parse_uuid(work_id).ok_or_else(|| Error::Invalid(format!("work ID must be a UUID: {work_id}")))
}

fn parse_uuid(text: &str) -> Option<String> {
    let bytes = text.as_bytes();
    let dashes_ok = bytes.len() == 36
        && [8, 13, 18, 23].iter().all(|&at| bytes[at] == b'-')
        && bytes
            .iter()
            .enumerate()
            .all(|(at, byte)| [8, 13, 18, 23].contains(&at) || byte.is_ascii_hexdigit());
    dashes_ok.then(|| text.to_ascii_lowercase())
}

/// Every path of one area (and, with a work ID, of one run's staging
/// directory). Returned by [`AreaStorage::layout`] so adapters never build
/// these names themselves.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct Layout {
    pub root: String,
    /// Published OSM data, `<areaId>.sqlite`.
    pub data: String,
    /// Published basemap, `<areaId>.pmtiles`.
    pub basemap: String,
    /// Published metadata sidecar, `<areaId>.json`.
    pub sidecar: String,
    /// Commit journal, `<areaId>.commit`.
    pub journal: String,
    /// Lock file, `<areaId>.lock`.
    pub lock: String,
    /// `.staging/<areaId>/<workId>`; null without a work ID.
    pub staging_dir: Option<String>,
    /// Where the run's import writes: `<staging_dir>/area.sqlite`.
    pub staged_data: Option<String>,
    /// Where the run's basemap goes: `<staging_dir>/basemap.pmtiles`.
    pub staged_basemap: Option<String>,
    /// The run's sidecar: `<staging_dir>/area.json`
    /// ([`AreaStorage::write_staged_metadata`] writes it).
    pub staged_metadata: Option<String>,
}

/// The two points of a commit where the caller's hook runs.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(i32)]
pub enum CommitStage {
    /// Under the area lock, immediately before the commit point. Returning
    /// `false` from the hook aborts the commit with nothing changed (the
    /// adapter's last cancellation check).
    BeforeCommit = 0,
    /// Right after the commit point, before the roll-forward renames, still
    /// under the lock. The hook's answer is ignored: the version is
    /// committed. Exists so tests can hold a commit at exactly this point.
    AfterCommitPoint = 1,
}

/// How a commit ended (errors aside).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Commit {
    /// The staged version is published.
    Published,
    /// The hook said stop at [`CommitStage::BeforeCommit`]; nothing changed.
    Aborted,
}

/// Every step of a commit and its roll-forward, in order. Production code
/// only surfaces [`CommitStage`]s to the caller; the rest exist so the
/// fault-injection tests can "kill the process" (return an error) after any
/// step and check that recovery yields the old or the new area, never a mix.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Step {
    /// The caller's last check (abort allowed).
    BeforeCommit,
    /// Staging directory fsynced.
    StagingSynced,
    /// Journal written and fsynced under its temporary name, not renamed.
    JournalTempWritten,
    /// Journal renamed into place (**the commit point**), directory not yet fsynced.
    JournalRenamed,
    /// Directory fsynced after the journal rename; the hook's AfterCommitPoint.
    AfterCommitPoint,
    /// Roll-forward: basemap renamed into place (or the old one deleted).
    BasemapPlaced,
    /// Roll-forward: data renamed into place.
    DataPlaced,
    /// Roll-forward: sidecar renamed into place.
    MetadataPlaced,
    /// Roll-forward: directory fsynced after the renames.
    DirectorySynced,
    /// Roll-forward: journal deleted (staging directory not yet deleted).
    JournalDeleted,
}

impl Step {
    /// Every step, in execution order.
    #[cfg(test)]
    pub(crate) const ALL: [Step; 10] = [
        Step::BeforeCommit,
        Step::StagingSynced,
        Step::JournalTempWritten,
        Step::JournalRenamed,
        Step::AfterCommitPoint,
        Step::BasemapPlaced,
        Step::DataPlaced,
        Step::MetadataPlaced,
        Step::DirectorySynced,
        Step::JournalDeleted,
    ];

    /// Steps of the roll-forward alone (what `recover` runs).
    #[cfg(test)]
    pub(crate) const ROLL_FORWARD: [Step; 5] = [
        Step::BasemapPlaced,
        Step::DataPlaced,
        Step::MetadataPlaced,
        Step::DirectorySynced,
        Step::JournalDeleted,
    ];
}

/// A callback at each [`Step`]: `Ok(false)` at `BeforeCommit` aborts, an
/// error stops the operation on the spot (a simulated kill in tests).
pub(crate) type Steps<'a> = dyn FnMut(Step) -> Result<bool> + 'a;

/// No hooks: every step proceeds.
fn no_steps(_: Step) -> Result<bool> {
    Ok(true)
}

/// The published area: file paths plus the metadata, if it describes them.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct Published {
    /// The OSM data file (always present: no data file means no area).
    pub data: String,
    /// The basemap, or null when none is published.
    pub basemap: Option<String>,
    /// The sidecar, or null when it is missing, malformed or does not
    /// describe the files present (see [`AreaStorage::published`]).
    pub metadata: Option<Sidecar>,
}

/// The metadata sidecar (`<areaId>.json`), as 0.2.0's Kotlin
/// `AreaMetadata.toJson` writes it. Field names and order are a stored
/// format: never rename them.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct Sidecar {
    pub bbox: SidecarBbox,
    pub name: String,
    /// The server's timestamp string as received (not parsed here).
    pub snapshot_timestamp: Option<String>,
    pub imported_at_millis: i64,
    pub report: SidecarReport,
    pub basemap: Option<SidecarBasemap>,
    /// The run that published the area, lower-case UUID; null when the area
    /// was published by hand.
    pub work_id: Option<String>,
}

/// The requested bbox. Not validated: the sidecar records what was asked.
#[derive(Debug, Clone, Copy, PartialEq, Serialize)]
pub struct SidecarBbox {
    pub west: f64,
    pub south: f64,
    pub east: f64,
    pub north: f64,
}

/// The import report (same shape as [`crate::ImportReport`]).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
pub struct SidecarReport {
    pub counts: SidecarCounts,
    pub database_bytes: i64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
pub struct SidecarCounts {
    pub nodes: i64,
    pub ways: i64,
    pub relations: i64,
}

/// The published basemap as recorded at download time.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct SidecarBasemap {
    /// `"url"` or `"extract"`.
    pub kind: String,
    pub source_url: String,
    /// Size of the published PMTiles file: checked against the file.
    pub bytes: i64,
    pub addressed_tiles: i64,
    pub min_zoom: i32,
    pub max_zoom: i32,
    pub requests: i64,
    pub transferred_bytes: i64,
}

impl Sidecar {
    /// Parses a sidecar as 0.2.0's Kotlin `AreaMetadata.fromJson` did, or
    /// `None` where that threw (a malformed sidecar reads as "metadata
    /// unknown", never as an error). Deliberately strict where org.json was
    /// lenient in ways no Cantino writer ever used (numbers as strings,
    /// fractional values for integer fields): such hand-made files read as
    /// unknown metadata. Lenient where Kotlin was: unknown keys are ignored,
    /// and a `basemap` that is not an object reads as "no basemap"
    /// (`optJSONObject`).
    pub fn parse(text: &str) -> Option<Self> {
        let json: Value = serde_json::from_str(text).ok()?;
        let json = json.as_object()?;
        let bbox = json.get("bbox")?.as_object()?;
        let float = |key: &str| bbox.get(key)?.as_f64();
        let report = json.get("report")?.as_object()?;
        let counts = report.get("counts")?.as_object()?;
        let basemap = match json.get("basemap") {
            Some(Value::Object(basemap)) => Some(SidecarBasemap {
                kind: match basemap.get("kind")?.as_str()? {
                    kind @ ("url" | "extract") => kind.to_owned(),
                    _ => return None, // Kotlin: NoSuchElementException, caught
                },
                source_url: basemap.get("source_url")?.as_str()?.to_owned(),
                bytes: basemap.get("bytes")?.as_i64()?,
                addressed_tiles: basemap.get("addressed_tiles")?.as_i64()?,
                min_zoom: i32::try_from(basemap.get("min_zoom")?.as_i64()?).ok()?,
                max_zoom: i32::try_from(basemap.get("max_zoom")?.as_i64()?).ok()?,
                requests: basemap.get("requests")?.as_i64()?,
                transferred_bytes: basemap.get("transferred_bytes")?.as_i64()?,
            }),
            // optJSONObject: absent, null or not an object → no basemap.
            _ => None,
        };
        Some(Self {
            bbox: SidecarBbox {
                west: float("west")?,
                south: float("south")?,
                east: float("east")?,
                north: float("north")?,
            },
            name: json.get("name")?.as_str()?.to_owned(),
            // isNull(): absent or JSON null → null; otherwise a string.
            snapshot_timestamp: optional(json.get("snapshot_timestamp"), |value| {
                value.as_str().map(str::to_owned)
            })?,
            imported_at_millis: json.get("imported_at_millis")?.as_i64()?,
            report: SidecarReport {
                counts: SidecarCounts {
                    nodes: counts.get("nodes")?.as_i64()?,
                    ways: counts.get("ways")?.as_i64()?,
                    relations: counts.get("relations")?.as_i64()?,
                },
                database_bytes: report.get("database_bytes")?.as_i64()?,
            },
            basemap,
            work_id: optional(json.get("work_id"), |value| parse_uuid(value.as_str()?))?,
        })
    }
}

/// org.json's `isNull` + typed getter: `Some(None)` for absent or null,
/// `Some(Some(v))` when `read` accepts the value, `None` (malformed) otherwise.
fn optional<T>(value: Option<&Value>, read: impl FnOnce(&Value) -> Option<T>) -> Option<Option<T>> {
    match value {
        None | Some(Value::Null) => Some(None),
        Some(value) => read(value).map(Some),
    }
}

/// The commit journal, `{"work_id":"<uuid>","basemap":<bool>}`.
#[derive(Debug, Clone, PartialEq, Eq)]
struct Journal {
    work_id: String,
    basemap: bool,
}

impl Journal {
    /// The exact bytes 0.2.0 wrote (Android's org.json keeps insertion
    /// order and has no whitespace), so a journal written here is also
    /// readable by 0.2.0 after a downgrade.
    fn text(&self) -> String {
        format!(
            r#"{{"work_id":"{}","basemap":{}}}"#,
            self.work_id, self.basemap
        )
    }

    /// Reads the journal: `None` when there is none. A journal is written
    /// atomically, so one that exists but cannot be read or parsed is not a
    /// torn write: it is damage, and recovery fails closed (an I/O error,
    /// the journal kept) rather than publishing a guessed version or a mix.
    /// Android's `getBoolean` accepted the strings "true"/"false" (any
    /// case), and so does this.
    fn read(path: &Path) -> Result<Option<Self>> {
        let text = match fs::read_to_string(path) {
            Ok(text) => text,
            Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(None),
            Err(error) => return Err(error.into()),
        };
        let parse = || {
            let json: Value = serde_json::from_str(&text).ok()?;
            let work_id = parse_uuid(json.get("work_id")?.as_str()?)?;
            let basemap = match json.get("basemap")? {
                Value::Bool(value) => *value,
                Value::String(text) if text.eq_ignore_ascii_case("true") => true,
                Value::String(text) if text.eq_ignore_ascii_case("false") => false,
                _ => return None,
            };
            Some(Self { work_id, basemap })
        };
        parse().map(Some).ok_or_else(|| {
            Error::Io(io::Error::new(
                io::ErrorKind::InvalidData,
                format!("invalid commit journal {}", path.display()),
            ))
        })
    }
}

/// One app's areas under one root directory. Cheap to create (holds only
/// the path); all state is on disk, so any number of instances (and
/// processes) may coexist. Pass the same root spelling everywhere: the
/// in-process lock is keyed by path text.
#[derive(Debug, Clone)]
pub struct AreaStorage {
    root: PathBuf,
}

impl AreaStorage {
    pub fn new(root: impl Into<PathBuf>) -> Self {
        Self { root: root.into() }
    }

    fn data(&self, area_id: &str) -> PathBuf {
        self.root.join(format!("{area_id}.sqlite"))
    }
    fn basemap(&self, area_id: &str) -> PathBuf {
        self.root.join(format!("{area_id}.pmtiles"))
    }
    fn sidecar(&self, area_id: &str) -> PathBuf {
        self.root.join(format!("{area_id}.json"))
    }
    fn journal(&self, area_id: &str) -> PathBuf {
        self.root.join(format!("{area_id}.commit"))
    }
    fn lock_file(&self, area_id: &str) -> PathBuf {
        self.root.join(format!("{area_id}.lock"))
    }
    /// `.staging/<areaId>`: the parent of every run's staging directory.
    fn area_staging(&self, area_id: &str) -> PathBuf {
        self.root.join(".staging").join(area_id)
    }
    /// `work_id` must already be normalized (lower case).
    fn staging_dir(&self, area_id: &str, work_id: &str) -> PathBuf {
        self.area_staging(area_id).join(work_id)
    }
    fn staged_data(&self, area_id: &str, work_id: &str) -> PathBuf {
        self.staging_dir(area_id, work_id).join("area.sqlite")
    }
    fn staged_basemap(&self, area_id: &str, work_id: &str) -> PathBuf {
        self.staging_dir(area_id, work_id).join("basemap.pmtiles")
    }
    fn staged_metadata(&self, area_id: &str, work_id: &str) -> PathBuf {
        self.staging_dir(area_id, work_id).join("area.json")
    }

    /// Every path of `area_id` (and of run `work_id`'s staging directory
    /// when given). Pure: validates the IDs, touches no file.
    pub fn layout(&self, area_id: &str, work_id: Option<&str>) -> Result<Layout> {
        validate_area_id(area_id)?;
        let work_id = work_id.map(normalize_work_id).transpose()?;
        let text = |path: PathBuf| path.to_string_lossy().into_owned();
        let staged = |path: fn(&Self, &str, &str) -> PathBuf| {
            work_id
                .as_deref()
                .map(|work| text(path(self, area_id, work)))
        };
        Ok(Layout {
            root: text(self.root.clone()),
            data: text(self.data(area_id)),
            basemap: text(self.basemap(area_id)),
            sidecar: text(self.sidecar(area_id)),
            journal: text(self.journal(area_id)),
            lock: text(self.lock_file(area_id)),
            staging_dir: staged(Self::staging_dir),
            staged_data: staged(Self::staged_data),
            staged_basemap: staged(Self::staged_basemap),
            staged_metadata: staged(Self::staged_metadata),
        })
    }

    /// Takes the area lock (see the module comment). Never fails: the
    /// cross-process layer is skipped if the lock file cannot be opened.
    fn lock(&self, area_id: &str) -> AreaLock {
        AreaLock::acquire(self.lock_file(area_id))
    }

    /// Creates an empty staging directory for run `work_id` and deletes
    /// those of other runs (left by killed processes or cancelled runs).
    /// Called at the start of a run; it rolls a pending commit forward first,
    /// so a committed version is never deleted. Only one run per area is
    /// active (unique work); a cancelled run still unwinding loses its
    /// directory, which it was about to discard anyway.
    ///
    /// As on 0.2.0, failing to create the directory is not an error here
    /// (the import into it then fails with an I/O error the adapter already
    /// classifies as storage); failing to roll a pending commit forward is.
    pub fn prepare_staging(&self, area_id: &str, work_id: &str) -> Result<Layout> {
        let layout = self.layout(area_id, Some(work_id))?;
        let work_id = normalize_work_id(work_id)?;
        // 0.2.0: `prepare()` created the root (mkdirs, failure ignored).
        let _ = fs::create_dir_all(&self.root);
        let _lock = self.lock(area_id);
        self.roll_forward(area_id, &mut no_steps)?;
        if let Ok(entries) = fs::read_dir(self.area_staging(area_id)) {
            for entry in entries.flatten() {
                delete_recursively(&entry.path());
            }
        }
        let _ = fs::create_dir_all(self.staging_dir(area_id, &work_id));
        Ok(layout)
    }

    /// Discards run `work_id`'s staging directory unless its commit is
    /// pending (the journal owns the directory then; the roll-forward
    /// deletes it). Best effort: deletion failures are ignored.
    pub fn discard_staging(&self, area_id: &str, work_id: &str) -> Result<()> {
        validate_area_id(area_id)?;
        let work_id = normalize_work_id(work_id)?;
        let _lock = self.lock(area_id);
        // An unreadable journal might name this run: keep its staging then.
        let pending = match Journal::read(&self.journal(area_id)) {
            Ok(journal) => journal.map(|journal| journal.work_id),
            Err(_) => return Ok(()),
        };
        if pending.as_deref() != Some(work_id.as_str()) {
            delete_recursively(&self.staging_dir(area_id, &work_id));
        }
        Ok(())
    }

    /// Writes run `work_id`'s sidecar (atomically: temporary file, fsync,
    /// rename); it is published together with the run's data and basemap.
    /// `metadata` is the sidecar JSON text, written **verbatim** (so the
    /// Kotlin adapter's files stay byte-identical to 0.2.0's) after checking
    /// that [`Sidecar::parse`] accepts it: a sidecar that would read back as
    /// "metadata unknown" is refused as an invalid argument. No lock: the
    /// staging directory belongs to the run.
    pub fn write_staged_metadata(
        &self,
        area_id: &str,
        work_id: &str,
        metadata: &str,
    ) -> Result<()> {
        validate_area_id(area_id)?;
        let work_id = normalize_work_id(work_id)?;
        if Sidecar::parse(metadata).is_none() {
            return Err(Error::Invalid(
                "metadata is not a valid area sidecar (see AreaMetadata)".into(),
            ));
        }
        write_atomically(
            &self.staged_metadata(area_id, &work_id),
            metadata.as_bytes(),
            &mut || Ok(()),
        )
    }

    /// Publishes run `work_id`'s staged version (see the module comment).
    /// `hook` runs under the lock at [`CommitStage::BeforeCommit`] (return
    /// `false` to abort with nothing changed) and at
    /// [`CommitStage::AfterCommitPoint`] (answer ignored). `has_basemap` says
    /// whether the version includes `basemap.pmtiles`; without one, a
    /// previously published basemap is removed.
    ///
    /// Errors: invalid IDs, or a staged version missing its data, sidecar or
    /// (with `has_basemap`) basemap, are invalid arguments, reported before
    /// the commit point with nothing changed. I/O errors can happen before
    /// the commit point (finishing an earlier journal, writing this one:
    /// nothing changed) or after it (a roll-forward rename: the version is
    /// committed and the next [`Self::recover`] finishes it).
    pub fn commit(
        &self,
        area_id: &str,
        work_id: &str,
        has_basemap: bool,
        mut hook: impl FnMut(CommitStage) -> bool,
    ) -> Result<Commit> {
        self.commit_steps(area_id, work_id, has_basemap, &mut |step| {
            Ok(match step {
                Step::BeforeCommit => hook(CommitStage::BeforeCommit),
                Step::AfterCommitPoint => {
                    hook(CommitStage::AfterCommitPoint);
                    true
                }
                _ => true,
            })
        })
    }

    /// [`Self::commit`] with a callback at every [`Step`] (fault injection).
    pub(crate) fn commit_steps(
        &self,
        area_id: &str,
        work_id: &str,
        has_basemap: bool,
        steps: &mut Steps,
    ) -> Result<Commit> {
        validate_area_id(area_id)?;
        let work_id = normalize_work_id(work_id)?;
        let _lock = self.lock(area_id);
        // A journal of an earlier run (a dead process) completes first, so
        // this journal never overwrites one that is still owed a roll-forward.
        self.roll_forward(area_id, &mut no_steps)?;
        if !steps(Step::BeforeCommit)? {
            return Ok(Commit::Aborted);
        }
        let staged_data = self.staged_data(area_id, &work_id);
        let staged_metadata = self.staged_metadata(area_id, &work_id);
        if !staged_data.is_file() || !staged_metadata.is_file() {
            return Err(Error::Invalid("staged area incomplete".into()));
        }
        if has_basemap && !self.staged_basemap(area_id, &work_id).is_file() {
            return Err(Error::Invalid("staged basemap missing".into()));
        }
        // Not in 0.2.0 (see "Durability"): make the staged entries durable
        // before the journal that points at them.
        sync_directory(&self.staging_dir(area_id, &work_id));
        steps(Step::StagingSynced)?;
        let journal = Journal {
            work_id: work_id.clone(),
            basemap: has_basemap,
        };
        write_atomically(
            &self.journal(area_id),
            journal.text().as_bytes(),
            &mut || steps(Step::JournalTempWritten).map(drop),
        )?;
        // ---- committed: the journal is in place ----
        steps(Step::JournalRenamed)?;
        sync_directory(&self.root);
        steps(Step::AfterCommitPoint)?;
        self.roll_forward(area_id, steps)?;
        Ok(Commit::Published)
    }

    /// Finishes a commit left half-done by a dead process (or by a failed
    /// rename). Cheap when there is nothing to do; blocks while a commit of
    /// this area is running. Does **not** delete staging directories without
    /// a journal: a live run in this process may be building in one
    /// ([`Self::prepare_staging`] deletes stale ones at the start of the next
    /// run). An I/O error (including a damaged journal) leaves the journal
    /// for the next attempt; [`Self::published`] fails the same way meanwhile,
    /// so no reader sees a mix of old and new parts.
    pub fn recover(&self, area_id: &str) -> Result<()> {
        self.recover_steps(area_id, &mut no_steps)
    }

    pub(crate) fn recover_steps(&self, area_id: &str, steps: &mut Steps) -> Result<()> {
        validate_area_id(area_id)?;
        // No journal, nothing to finish. Checked without the lock so a
        // reader never creates files (the lock file) for an area that has
        // nothing pending. Linearizable: a commit that writes its journal
        // after this check starts after this call.
        if !self.journal(area_id).exists() {
            return Ok(());
        }
        let _lock = self.lock(area_id);
        self.roll_forward(area_id, steps)
    }

    /// The published area, or `None` when no data file exists. Completes a
    /// pending commit first and fails (I/O error, journal kept) if it cannot:
    /// a half-finished commit is never returned. Reads under the area lock,
    /// so the answer describes one version. The metadata is returned only if it describes the files
    /// present: the recorded database size must equal the data file's, and
    /// the recorded basemap size (or its absence) must match the basemap
    /// file. Anything else reads as "metadata unknown" (null), never as
    /// wrong metadata.
    ///
    /// The paths are valid after the lock is released, but a later commit
    /// may replace the files: open them right away (an open SQLite file keeps
    /// its snapshot).
    pub fn published(&self, area_id: &str) -> Result<Option<Published>> {
        validate_area_id(area_id)?;
        // Neither a journal nor data: nothing is published (or being
        // published) at this instant. Answered without the lock, so reading
        // an unknown area ID creates no lock file. A commit whose journal
        // appears after this check is ordered after this read.
        if !self.journal(area_id).exists() && !self.data(area_id).exists() {
            return Ok(None);
        }
        let _lock = self.lock(area_id);
        self.roll_forward(area_id, &mut no_steps)?;
        let data = self.data(area_id);
        if !data.is_file() {
            return Ok(None);
        }
        let basemap = Some(self.basemap(area_id)).filter(|path| path.is_file());
        // Java's File.length(): 0 when unreadable.
        let length = |path: &Path| fs::metadata(path).map_or(0, |meta| meta.len() as i64);
        let sidecar = self.sidecar(area_id);
        let metadata = sidecar
            .is_file()
            .then(|| fs::read_to_string(&sidecar).ok())
            .flatten()
            .and_then(|text| Sidecar::parse(&text))
            .filter(|metadata| {
                metadata.report.database_bytes == length(&data)
                    && metadata.basemap.as_ref().map(|basemap| basemap.bytes)
                        == basemap.as_deref().map(length)
            });
        Ok(Some(Published {
            data: data.to_string_lossy().into_owned(),
            basemap: basemap.map(|path| path.to_string_lossy().into_owned()),
            metadata,
        }))
    }

    /// Step 3 of the commit. Caller holds the lock. Idempotent: every part
    /// whose staged file is gone was moved by an earlier, interrupted run of
    /// this function, so running it again after a kill at any point finishes
    /// the same version.
    fn roll_forward(&self, area_id: &str, steps: &mut Steps) -> Result<()> {
        let Some(journal) = Journal::read(&self.journal(area_id))? else {
            return Ok(());
        };
        let work_id = journal.work_id.as_str();
        if journal.basemap {
            move_if_present(
                &self.staged_basemap(area_id, work_id),
                &self.basemap(area_id),
            )?;
        } else {
            // The new version has no basemap: the old one must not survive
            // next to the new data.
            remove_if_present(&self.basemap(area_id))?;
        }
        steps(Step::BasemapPlaced)?;
        move_if_present(&self.staged_data(area_id, work_id), &self.data(area_id))?;
        steps(Step::DataPlaced)?;
        move_if_present(
            &self.staged_metadata(area_id, work_id),
            &self.sidecar(area_id),
        )?;
        steps(Step::MetadataPlaced)?;
        sync_directory(&self.root);
        steps(Step::DirectorySynced)?;
        // Only now: until the renames are durable, the journal must survive
        // so a crash replays them.
        remove_if_present(&self.journal(area_id))?;
        steps(Step::JournalDeleted)?;
        delete_recursively(&self.staging_dir(area_id, work_id));
        Ok(())
    }
}

/// Deletes a file; one that is already gone is fine (idempotent replay).
fn remove_if_present(path: &Path) -> Result<()> {
    match fs::remove_file(path) {
        Err(error) if error.kind() != io::ErrorKind::NotFound => Err(error.into()),
        _ => Ok(()),
    }
}

/// Renames `from` to `to` (replacing `to`) unless `from` is gone, which
/// means an earlier, interrupted roll-forward already moved it.
fn move_if_present(from: &Path, to: &Path) -> Result<()> {
    if !from.exists() {
        return Ok(());
    }
    fs::rename(from, to).map_err(|error| {
        Error::Io(io::Error::new(
            error.kind(),
            format!(
                "cannot rename {} to {}: {error}",
                from.display(),
                to.display()
            ),
        ))
    })
}

/// Makes renames in `directory` durable. Best effort, as on 0.2.0: a crash
/// before it may undo them, which the journal covers. (On Apple platforms
/// `sync_all` is `F_FULLFSYNC`.)
fn sync_directory(directory: &Path) {
    if let Ok(handle) = File::open(directory) {
        let _ = handle.sync_all();
    }
}

/// Deletes a file or a directory tree, continuing past failures like
/// Kotlin's `File.deleteRecursively`. A missing path is fine.
fn delete_recursively(path: &Path) {
    match fs::symlink_metadata(path) {
        Ok(meta) if meta.is_dir() => {
            if let Ok(entries) = fs::read_dir(path) {
                for entry in entries.flatten() {
                    delete_recursively(&entry.path());
                }
            }
            let _ = fs::remove_dir(path);
        }
        Ok(_) => {
            let _ = fs::remove_file(path);
        }
        Err(_) => {}
    }
}

/// Writes `bytes` to `target` atomically and durably, as 0.2.0's
/// `writeAtomically`: a temporary `<name>.<uuid>.tmp` beside the target
/// (parent created if needed), written and fsynced, then renamed over the
/// target. The temporary file is removed on failure; a killed process leaves
/// it behind, which is harmless (nothing reads `*.tmp`). The directory is
/// not fsynced here; callers that need the rename durable do that.
/// `before_rename` runs between the fsync and the rename (fault injection).
fn write_atomically(
    target: &Path,
    bytes: &[u8],
    before_rename: &mut dyn FnMut() -> Result<()>,
) -> Result<()> {
    let parent = target
        .parent()
        .ok_or_else(|| Error::Invalid(format!("no parent directory: {}", target.display())))?;
    let _ = fs::create_dir_all(parent);
    let name = target
        .file_name()
        .ok_or_else(|| Error::Invalid(format!("no file name: {}", target.display())))?
        .to_string_lossy();
    let temporary = parent.join(format!("{name}.{}.tmp", random_uuid()));
    let result = (|| -> Result<()> {
        let mut file = OpenOptions::new()
            .write(true)
            .create(true)
            .truncate(true)
            .open(&temporary)?;
        file.write_all(bytes)?;
        file.sync_all()?;
        drop(file);
        before_rename()?;
        fs::rename(&temporary, target).map_err(|error| {
            Error::Io(io::Error::new(
                error.kind(),
                format!(
                    "cannot rename {} to {}: {error}",
                    temporary.display(),
                    target.display()
                ),
            ))
        })
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temporary);
    }
    result
}

/// A random UUID-shaped name for temporary files (std only: the randomly
/// keyed SipHash of `RandomState`, mixed with time, process and a counter).
/// Uniqueness matters, unpredictability does not.
fn random_uuid() -> String {
    use std::sync::atomic::{AtomicU64, Ordering};
    static COUNTER: AtomicU64 = AtomicU64::new(0);
    let word = || {
        let mut hasher = RandomState::new().build_hasher();
        hasher.write_u64(COUNTER.fetch_add(1, Ordering::Relaxed));
        hasher.write_u128(
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .map_or(0, |time| time.as_nanos()),
        );
        hasher.write_u32(std::process::id());
        hasher.finish()
    };
    let hex = format!("{:016x}{:016x}", word(), word());
    format!(
        "{}-{}-{}-{}-{}",
        &hex[0..8],
        &hex[8..12],
        &hex[12..16],
        &hex[16..20],
        &hex[20..32]
    )
}

// ---------------------------------------------------------------- lock --

/// Lock-file paths currently held by a thread of this process. A path is
/// in the set exactly while some [`AreaLock`] for it is alive.
fn held() -> &'static (Mutex<HashSet<PathBuf>>, Condvar) {
    static HELD: OnceLock<(Mutex<HashSet<PathBuf>>, Condvar)> = OnceLock::new();
    HELD.get_or_init(|| (Mutex::new(HashSet::new()), Condvar::new()))
}

/// Poison-tolerant: the set stays consistent because every mutation is a
/// single insert/remove, so a panic elsewhere cannot leave it half-updated.
fn held_set() -> MutexGuard<'static, HashSet<PathBuf>> {
    held().0.lock().unwrap_or_else(|poison| poison.into_inner())
}

/// The area lock (see the module comment, "Locking"). Field order matters:
/// fields drop in declaration order, so the `flock` (closing the file
/// releases it) is released before another thread of this process can take
/// the in-process lock and block on `flock`.
struct AreaLock {
    /// Holds the exclusive `flock` while open; `None` when the lock file
    /// could not be opened or locked (in-process exclusion only).
    file: Option<File>,
    path: PathBuf,
}

impl AreaLock {
    fn acquire(path: PathBuf) -> Self {
        // 1. In-process: wait until no other thread holds this path.
        let (_, released) = held();
        let mut set = held_set();
        while set.contains(&path) {
            set = released
                .wait(set)
                .unwrap_or_else(|poison| poison.into_inner());
        }
        set.insert(path.clone());
        drop(set);
        // 2. Cross-process, best effort. The file is created on first use
        // and never deleted (deleting a lock file races with its users).
        let file = OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .truncate(false)
            .open(&path)
            .ok()
            .filter(|file| {
                loop {
                    match file.lock() {
                        Ok(()) => break true,
                        Err(error) if error.kind() == io::ErrorKind::Interrupted => continue,
                        Err(_) => break false,
                    }
                }
            });
        Self { file, path }
    }
}

impl Drop for AreaLock {
    fn drop(&mut self) {
        // Release the flock before letting the next thread in.
        self.file.take();
        held_set().remove(&self.path);
        held().1.notify_all();
    }
}

#[cfg(test)]
mod tests;
