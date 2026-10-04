//! Area store tests: the commit protocol under fault injection (a simulated
//! kill after every step of commit and of recovery), the lock, and the
//! staging rules. Compatibility with 0.2.0's on-disk layout is tested in
//! `tests/area_storage.rs` against committed fixtures.
use super::*;
use std::{sync::mpsc, thread, time::Duration};

const AREA: &str = "city";
const OLD_RUN: &str = "11111111-1111-4111-8111-111111111111";
const NEW_RUN: &str = "22222222-2222-4222-8222-222222222222";
const NEXT_RUN: &str = "33333333-3333-4333-8333-333333333333";

/// One distinguishable version of the area. Sizes differ between versions
/// so the sidecar's size checks can tell a mixed area apart.
#[derive(Clone)]
struct Version {
    run: &'static str,
    data: Option<Vec<u8>>,
    basemap: Option<Vec<u8>>,
}

/// The shapes a version can have: data only, data and basemap, basemap only.
const SHAPES: [Parts; 3] = [
    Parts::new(true, false),
    Parts::new(true, true),
    Parts::new(false, true),
];

impl Version {
    fn old(basemap: bool) -> Self {
        Self::old_shaped(Parts::new(true, basemap))
    }
    fn new(basemap: bool) -> Self {
        Self::new_shaped(Parts::new(true, basemap))
    }
    fn old_shaped(parts: Parts) -> Self {
        Self {
            run: OLD_RUN,
            data: parts.data.then(|| b"old data".to_vec()),
            basemap: parts.basemap.then(|| b"old basemap".to_vec()),
        }
    }
    fn new_shaped(parts: Parts) -> Self {
        Self {
            run: NEW_RUN,
            data: parts.data.then(|| b"the new data, longer".to_vec()),
            basemap: parts.basemap.then(|| b"the new basemap, longer".to_vec()),
        }
    }
    fn parts(&self) -> Parts {
        Parts::new(self.data.is_some(), self.basemap.is_some())
    }

    /// The sidecar as 0.2.0's Kotlin wrote it (org.json: insertion order,
    /// no whitespace, `/` escaped, integral doubles without `.0`).
    fn sidecar(&self) -> String {
        let basemap = match &self.basemap {
            Some(bytes) => format!(
                r#"{{"kind":"url","source_url":"http:\/\/example.org\/{}.pmtiles","bytes":{},"addressed_tiles":22,"min_zoom":12,"max_zoom":15,"requests":1,"transferred_bytes":{}}}"#,
                self.run,
                bytes.len(),
                bytes.len()
            ),
            None => "null".into(),
        };
        let report = match &self.data {
            Some(data) => format!(
                r#"{{"counts":{{"nodes":3,"ways":1,"relations":0}},"database_bytes":{}}}"#,
                data.len()
            ),
            None => "null".into(),
        };
        format!(
            r#"{{"bbox":{{"west":-111.9,"south":40,"east":-111.8,"north":40.8}},"name":"{}","snapshot_timestamp":"2026-10-03T20:30:01Z","imported_at_millis":1759523401000,"report":{},"basemap":{},"work_id":"{}"}}"#,
            self.run, report, basemap, self.run
        )
    }

    /// Builds this version in its staging directory, as a download run does.
    fn stage(&self, storage: &AreaStorage) {
        let layout = storage.prepare_staging(AREA, self.run).unwrap();
        if let Some(data) = &self.data {
            fs::write(layout.staged_data.unwrap(), data).unwrap();
        }
        if let Some(basemap) = &self.basemap {
            fs::write(layout.staged_basemap.unwrap(), basemap).unwrap();
        }
        storage
            .write_staged_metadata(AREA, self.run, &self.sidecar())
            .unwrap();
    }

    fn publish(&self, storage: &AreaStorage) {
        self.stage(storage);
        let outcome = storage
            .commit(AREA, self.run, self.parts(), |_| true)
            .unwrap();
        assert_eq!(outcome, Commit::Published);
    }
}

/// Asserts that exactly `version` is published: data, basemap (or none) and
/// a sidecar that describes them, with no journal left.
fn assert_published(storage: &AreaStorage, version: &Version, context: &str) {
    let published = storage
        .published(AREA)
        .unwrap()
        .unwrap_or_else(|| panic!("{context}: no area"));
    assert_eq!(
        published.data.as_ref().map(|path| fs::read(path).unwrap()),
        version.data,
        "{context}: data"
    );
    assert_eq!(
        published
            .basemap
            .as_ref()
            .map(|path| fs::read(path).unwrap()),
        version.basemap,
        "{context}: basemap"
    );
    let metadata = published
        .metadata
        .unwrap_or_else(|| panic!("{context}: metadata unknown (mixed area?)"));
    assert_eq!(metadata.work_id.as_deref(), Some(version.run), "{context}");
    assert_eq!(
        metadata,
        Sidecar::parse(&version.sidecar()).unwrap(),
        "{context}"
    );
    assert!(!storage.journal(AREA).exists(), "{context}: journal left");
}

fn kill_at(target: Step) -> impl FnMut(Step) -> Result<bool> {
    move |step| {
        if step == target {
            Err(Error::Internal(format!("simulated kill at {step:?}")))
        } else {
            Ok(true)
        }
    }
}

fn committed(step: Step) -> bool {
    let at = |s| Step::ALL.iter().position(|&x| x == s).unwrap();
    at(step) >= at(Step::JournalRenamed)
}

/// The core guarantee: a kill after any step of a commit, followed by
/// recovery (a "restarted process": a new `AreaStorage`, nothing in memory),
/// leaves either the complete old area or the complete new one. Before the
/// journal rename it is the old one, from it on the new one. For every
/// combination of old/new shapes (data, data + basemap, basemap only).
#[test]
fn a_kill_at_any_commit_step_recovers_old_or_new_never_mixed() {
    for old_parts in SHAPES {
        for new_parts in SHAPES {
            for step in Step::ALL {
                let context = format!("old {old_parts:?}, new {new_parts:?}, kill at {step:?}");
                let directory = tempfile::tempdir().unwrap();
                let storage = AreaStorage::new(directory.path());
                let (old, new) = (
                    Version::old_shaped(old_parts),
                    Version::new_shaped(new_parts),
                );
                old.publish(&storage);
                new.stage(&storage);

                let error = storage
                    .commit_steps(AREA, NEW_RUN, new_parts, &mut kill_at(step))
                    .unwrap_err();
                assert!(error.to_string().contains("simulated kill"), "{context}");

                let restarted = AreaStorage::new(directory.path());
                restarted.recover(AREA).unwrap();
                let expected = if committed(step) { &new } else { &old };
                assert_published(&restarted, expected, &context);
                if committed(step) {
                    // Every staged part was moved. A kill between deleting
                    // the journal and the staging directory leaves the
                    // (empty) directory without a journal; recover leaves
                    // it alone and the next run's prepare_staging removes it.
                    let staged = fs::read_dir(restarted.staging_dir(AREA, NEW_RUN))
                        .map_or(0, |entries| entries.count());
                    assert_eq!(staged, 0, "{context}: committed parts left in staging");
                }
                // The next run starts cleanly and removes any uncommitted staging.
                restarted.prepare_staging(AREA, NEXT_RUN).unwrap();
                assert!(!restarted.staging_dir(AREA, NEW_RUN).exists(), "{context}");
                assert_published(&restarted, expected, &context);
            }
        }
    }
}

/// Recovery itself can be killed at any step; recovering again finishes the
/// same (new) version.
#[test]
fn a_kill_during_recovery_still_recovers_the_new_area() {
    for new_parts in SHAPES {
        for commit_step in Step::ALL.into_iter().filter(|&step| committed(step)) {
            for recover_step in Step::ROLL_FORWARD {
                let context = format!(
                    "new {new_parts:?}, commit killed at {commit_step:?}, recover killed at {recover_step:?}"
                );
                let directory = tempfile::tempdir().unwrap();
                let storage = AreaStorage::new(directory.path());
                let (old, new) = (Version::old(true), Version::new_shaped(new_parts));
                old.publish(&storage);
                new.stage(&storage);
                storage
                    .commit_steps(AREA, NEW_RUN, new_parts, &mut kill_at(commit_step))
                    .unwrap_err();
                // The journal is deleted at JournalDeleted: a later recover
                // has nothing to do, so the injected kill never fires.
                let outcome = storage.recover_steps(AREA, &mut kill_at(recover_step));
                if commit_step != Step::JournalDeleted {
                    assert!(outcome.is_err(), "{context}");
                }
                storage.recover(AREA).unwrap();
                assert_published(&storage, &new, &context);
            }
        }
    }
}

/// A reader does not need to call `recover` first: `published` finishes a
/// pending commit itself.
#[test]
fn published_rolls_a_pending_commit_forward() {
    let directory = tempfile::tempdir().unwrap();
    let storage = AreaStorage::new(directory.path());
    Version::old(true).publish(&storage);
    let new = Version::new(false);
    new.stage(&storage);
    storage
        .commit_steps(
            AREA,
            NEW_RUN,
            Parts::new(true, false),
            &mut kill_at(Step::DataPlaced),
        )
        .unwrap_err();
    assert_published(&storage, &new, "published after a kill");
    assert!(!storage.basemap(AREA).exists(), "old basemap must go");
}

/// A roll-forward that cannot finish (here: a directory where the data file
/// goes) fails readers and recovery alike, keeps the journal and the staged
/// files, and completes once the obstruction is gone. No reader ever gets
/// the half-placed version (new basemap next to old data).
#[test]
fn a_failed_roll_forward_fails_closed_and_keeps_the_journal() {
    let directory = tempfile::tempdir().unwrap();
    let storage = AreaStorage::new(directory.path());
    Version::old(true).publish(&storage);
    let new = Version::new(true);
    new.stage(&storage);
    let mut obstruct = |step| {
        if step == Step::JournalRenamed {
            fs::remove_file(storage.data(AREA)).unwrap();
            fs::create_dir(storage.data(AREA)).unwrap();
            fs::write(storage.data(AREA).join("obstruction"), b"x").unwrap();
        }
        Ok(true)
    };
    let error = storage
        .commit_steps(AREA, NEW_RUN, Parts::new(true, true), &mut obstruct)
        .unwrap_err();
    assert_eq!(error.kind(), crate::ErrorKind::Io, "{error}");
    assert_eq!(
        fs::read(storage.basemap(AREA)).unwrap(),
        new.basemap.clone().unwrap()
    );
    assert_eq!(
        storage.recover(AREA).unwrap_err().kind(),
        crate::ErrorKind::Io
    );
    assert_eq!(
        storage.published(AREA).unwrap_err().kind(),
        crate::ErrorKind::Io
    );
    assert!(storage.journal(AREA).exists());
    assert!(storage.staged_data(AREA, NEW_RUN).exists());
    fs::remove_dir_all(storage.data(AREA)).unwrap();
    assert_published(&storage, &new, "after the obstruction is removed");
}

/// A journal is written atomically, so a damaged one is never "no journal":
/// recovery and readers fail with an I/O error and leave it in place, and
/// discarding a run's staging keeps it (the journal might name that run).
#[test]
fn a_damaged_journal_fails_closed() {
    let directory = tempfile::tempdir().unwrap();
    let storage = AreaStorage::new(directory.path());
    Version::old(false).publish(&storage);
    Version::new(false).stage(&storage);
    fs::write(storage.journal(AREA), b"broken journal").unwrap();
    assert_eq!(
        storage.published(AREA).unwrap_err().kind(),
        crate::ErrorKind::Io
    );
    assert_eq!(
        storage.recover(AREA).unwrap_err().kind(),
        crate::ErrorKind::Io
    );
    storage.discard_staging(AREA, NEW_RUN).unwrap();
    assert!(storage.staging_dir(AREA, NEW_RUN).exists());
    assert!(storage.journal(AREA).exists());
}

#[test]
fn a_hook_abort_changes_nothing_and_stages_until_discarded() {
    let directory = tempfile::tempdir().unwrap();
    let storage = AreaStorage::new(directory.path());
    let old = Version::old(true);
    old.publish(&storage);
    Version::new(true).stage(&storage);
    let mut stages = Vec::new();
    let outcome = storage
        .commit(AREA, NEW_RUN, Parts::new(true, true), |stage| {
            stages.push(stage);
            false
        })
        .unwrap();
    assert_eq!(outcome, Commit::Aborted);
    assert_eq!(stages, [CommitStage::BeforeCommit]);
    assert_published(&storage, &old, "after abort");
    assert!(storage.staging_dir(AREA, NEW_RUN).exists());
    storage.discard_staging(AREA, NEW_RUN).unwrap();
    assert!(!storage.staging_dir(AREA, NEW_RUN).exists());
}

#[test]
fn the_hook_sees_both_stages_in_order() {
    let directory = tempfile::tempdir().unwrap();
    let storage = AreaStorage::new(directory.path());
    Version::new(false).stage(&storage);
    let mut stages = Vec::new();
    storage
        .commit(AREA, NEW_RUN, Parts::new(true, false), |stage| {
            if stage == CommitStage::AfterCommitPoint {
                // Committed: the journal exists, nothing renamed yet.
                assert!(storage.journal(AREA).exists());
                assert!(!storage.data(AREA).exists());
            }
            stages.push(stage);
            true
        })
        .unwrap();
    assert_eq!(
        stages,
        [CommitStage::BeforeCommit, CommitStage::AfterCommitPoint]
    );
}

#[test]
fn an_incomplete_staged_version_is_refused_before_the_commit_point() {
    let directory = tempfile::tempdir().unwrap();
    let storage = AreaStorage::new(directory.path());
    let old = Version::old(false);
    old.publish(&storage);
    let layout = storage.prepare_staging(AREA, NEW_RUN).unwrap();
    fs::write(layout.staged_data.unwrap(), b"x").unwrap();
    // No sidecar.
    let error = storage
        .commit(AREA, NEW_RUN, Parts::new(true, false), |_| true)
        .unwrap_err();
    assert_eq!(error.kind(), crate::ErrorKind::InvalidArgument);
    // Sidecar but no basemap although one was announced.
    storage
        .write_staged_metadata(AREA, NEW_RUN, &Version::new(false).sidecar())
        .unwrap();
    let error = storage
        .commit(AREA, NEW_RUN, Parts::new(true, true), |_| true)
        .unwrap_err();
    assert_eq!(
        error.to_string(),
        "invalid argument: staged basemap missing"
    );
    assert_published(&storage, &old, "refused commits");
}

/// `discard_staging` of the run whose commit is pending keeps its staging
/// directory: the journal owns it until the roll-forward.
#[test]
fn discard_keeps_the_staging_of_a_pending_commit() {
    let directory = tempfile::tempdir().unwrap();
    let storage = AreaStorage::new(directory.path());
    Version::old(true).publish(&storage);
    let new = Version::new(true);
    new.stage(&storage);
    storage
        .commit_steps(
            AREA,
            NEW_RUN,
            Parts::new(true, true),
            &mut kill_at(Step::AfterCommitPoint),
        )
        .unwrap_err();
    storage.discard_staging(AREA, NEW_RUN).unwrap();
    assert!(storage.staged_data(AREA, NEW_RUN).exists());
    // And recover does not delete staging dirs without a journal (a live
    // run may be building in one); prepare_staging does.
    storage.recover(AREA).unwrap();
    assert_published(&storage, &new, "after discard + recover");
    let live = Version {
        run: NEXT_RUN,
        ..Version::old(false)
    };
    live.stage(&storage);
    storage.recover(AREA).unwrap();
    assert!(storage.staged_data(AREA, NEXT_RUN).exists());
}

#[test]
fn prepare_staging_deletes_other_runs_and_rolls_forward() {
    let directory = tempfile::tempdir().unwrap();
    let storage = AreaStorage::new(directory.path());
    Version::old(false).publish(&storage);
    let new = Version::new(true);
    new.stage(&storage);
    storage
        .commit_steps(
            AREA,
            NEW_RUN,
            Parts::new(true, true),
            &mut kill_at(Step::JournalRenamed),
        )
        .unwrap_err();
    // A stale run directory and a stray file under the area's staging root.
    let stale = storage.staging_dir(AREA, OLD_RUN);
    fs::create_dir_all(stale.join("nested")).unwrap();
    fs::write(storage.area_staging(AREA).join("stray"), b"x").unwrap();
    let layout = storage.prepare_staging(AREA, NEXT_RUN).unwrap();
    let left: Vec<_> = fs::read_dir(storage.area_staging(AREA))
        .unwrap()
        .map(|entry| entry.unwrap().file_name().into_string().unwrap())
        .collect();
    assert_eq!(left, [NEXT_RUN]);
    assert!(Path::new(layout.staging_dir.as_ref().unwrap()).is_dir());
    assert_published(&storage, &new, "rolled forward by prepare_staging");
}

#[test]
fn layout_names_and_work_id_case() {
    let storage = AreaStorage::new("/data/areas");
    let layout = storage
        .layout("city_1-b", Some("ABCDEF01-2345-4789-ABCD-EF0123456789"))
        .unwrap();
    assert_eq!(layout.data, "/data/areas/city_1-b.sqlite");
    assert_eq!(layout.basemap, "/data/areas/city_1-b.pmtiles");
    assert_eq!(layout.sidecar, "/data/areas/city_1-b.json");
    assert_eq!(layout.journal, "/data/areas/city_1-b.commit");
    assert_eq!(layout.lock, "/data/areas/city_1-b.lock");
    let staging = "/data/areas/.staging/city_1-b/abcdef01-2345-4789-abcd-ef0123456789";
    assert_eq!(layout.staging_dir.as_deref(), Some(staging));
    assert_eq!(layout.staged_data, Some(format!("{staging}/area.sqlite")));
    assert_eq!(
        layout.staged_basemap,
        Some(format!("{staging}/basemap.pmtiles"))
    );
    assert_eq!(layout.staged_metadata, Some(format!("{staging}/area.json")));
    assert_eq!(storage.layout("city", None).unwrap().staging_dir, None);
    for bad in ["", "a b", "../x", "é", &"x".repeat(65)] {
        assert!(storage.layout(bad, None).is_err(), "{bad:?}");
    }
    assert!(storage.layout("city", Some("not-a-uuid")).is_err());
    assert!(validate_area_id(&"x".repeat(64)).is_ok());
}

#[test]
fn the_journal_is_byte_identical_to_0_2_0() {
    let directory = tempfile::tempdir().unwrap();
    let storage = AreaStorage::new(directory.path());
    Version::new(true).stage(&storage);
    storage
        .commit_steps(
            AREA,
            NEW_RUN,
            Parts::new(true, true),
            &mut kill_at(Step::AfterCommitPoint),
        )
        .unwrap_err();
    assert_eq!(
        fs::read_to_string(storage.journal(AREA)).unwrap(),
        format!(r#"{{"work_id":"{NEW_RUN}","basemap":true}}"#)
    );
    // No temporary file is left next to it.
    let names: Vec<_> = fs::read_dir(directory.path())
        .unwrap()
        .map(|entry| entry.unwrap().file_name().into_string().unwrap())
        .collect();
    assert!(
        !names.iter().any(|name| name.ends_with(".tmp")),
        "{names:?}"
    );
}

#[test]
fn staged_metadata_is_written_verbatim_and_validated() {
    let directory = tempfile::tempdir().unwrap();
    let storage = AreaStorage::new(directory.path());
    let version = Version::new(true);
    version.stage(&storage);
    assert_eq!(
        fs::read_to_string(storage.staged_metadata(AREA, NEW_RUN)).unwrap(),
        version.sidecar()
    );
    for bad in ["{}", "[]", "{malformed", r#"{"bbox":1}"#] {
        let error = storage
            .write_staged_metadata(AREA, NEW_RUN, bad)
            .unwrap_err();
        assert_eq!(error.kind(), crate::ErrorKind::InvalidArgument, "{bad}");
    }
}

#[test]
fn metadata_that_does_not_describe_the_files_reads_as_unknown() {
    let directory = tempfile::tempdir().unwrap();
    let storage = AreaStorage::new(directory.path());
    let version = Version::new(true);
    version.publish(&storage);
    // Basemap replaced by hand: sizes disagree.
    fs::write(storage.basemap(AREA), b"other").unwrap();
    assert_eq!(storage.published(AREA).unwrap().unwrap().metadata, None);
    // Basemap removed although the sidecar records one.
    fs::remove_file(storage.basemap(AREA)).unwrap();
    assert_eq!(storage.published(AREA).unwrap().unwrap().metadata, None);
    // No data: no area at all, sidecar or not.
    fs::remove_file(storage.data(AREA)).unwrap();
    assert_eq!(storage.published(AREA).unwrap(), None);
}

#[test]
fn nothing_is_created_by_readers_of_a_missing_root() {
    let directory = tempfile::tempdir().unwrap();
    let root = directory.path().join("never");
    let storage = AreaStorage::new(&root);
    assert_eq!(storage.published(AREA).unwrap(), None);
    storage.recover(AREA).unwrap();
    assert!(!root.exists());
}

/// Readers never observe a half-published area while commits run on
/// another thread: every `published` sees a sidecar that describes the
/// files (data renamed before its sidecar would read as unknown).
#[test]
fn concurrent_readers_see_old_or_new_never_mixed() {
    let directory = tempfile::tempdir().unwrap();
    let root = directory.path().to_path_buf();
    Version::old(true).publish(&AreaStorage::new(&root));
    let writer = {
        let root = root.clone();
        thread::spawn(move || {
            let storage = AreaStorage::new(&root);
            for round in 0..60 {
                let run = format!("{:08x}-0000-4000-8000-000000000000", round);
                let base = if round % 2 == 0 {
                    Version::new(round % 3 == 0)
                } else {
                    Version::old(true)
                };
                let layout = storage.prepare_staging(AREA, &run).unwrap();
                fs::write(layout.staged_data.unwrap(), base.data.as_ref().unwrap()).unwrap();
                if let Some(basemap) = &base.basemap {
                    fs::write(layout.staged_basemap.unwrap(), basemap).unwrap();
                }
                let sidecar = base.sidecar().replace(base.run, &run);
                storage.write_staged_metadata(AREA, &run, &sidecar).unwrap();
                storage
                    .commit(AREA, &run, Parts::new(true, base.basemap.is_some()), |_| {
                        true
                    })
                    .unwrap();
            }
        })
    };
    let readers: Vec<_> = (0..3)
        .map(|_| {
            let root = root.clone();
            thread::spawn(move || {
                let storage = AreaStorage::new(&root);
                for _ in 0..400 {
                    let published = storage.published(AREA).unwrap().expect("an area");
                    assert!(published.metadata.is_some(), "mixed area observed");
                }
            })
        })
        .collect();
    writer.join().unwrap();
    for reader in readers {
        reader.join().unwrap();
    }
}

/// The cross-process layer: an `flock` held through another open file
/// description (what another process holds) blocks `published` until it is
/// released.
#[test]
fn an_external_flock_holder_blocks_readers() {
    let directory = tempfile::tempdir().unwrap();
    let storage = AreaStorage::new(directory.path());
    Version::old(false).publish(&storage);
    let holder = File::options()
        .read(true)
        .write(true)
        .open(storage.lock_file(AREA))
        .unwrap();
    holder.lock().unwrap();
    let (tx, rx) = mpsc::channel();
    let root = directory.path().to_path_buf();
    let reader = thread::spawn(move || {
        let published = AreaStorage::new(root).published(AREA).unwrap();
        tx.send(published.is_some()).unwrap();
    });
    assert!(
        rx.recv_timeout(Duration::from_millis(300)).is_err(),
        "reader must wait for the external lock"
    );
    holder.unlock().unwrap();
    assert!(rx.recv_timeout(Duration::from_secs(10)).unwrap());
    reader.join().unwrap();
}

#[test]
fn sidecar_parsing_follows_kotlin_from_json() {
    let version = Version::new(true);
    let parsed = Sidecar::parse(&version.sidecar()).unwrap();
    assert_eq!(
        parsed.bbox.south, 40.0,
        "integral double written without .0"
    );
    assert_eq!(
        parsed.basemap.as_ref().unwrap().source_url,
        format!("http://example.org/{NEW_RUN}.pmtiles"),
        "org.json's \\/ escape"
    );
    // optJSONObject: a basemap that is not an object is "no basemap".
    let text = version.sidecar();
    let basemap_start = text.find(r#""basemap":{"#).unwrap() + r#""basemap":"#.len();
    let basemap_end = text[basemap_start..].find('}').unwrap() + basemap_start + 1;
    let not_object = format!("{}\"x\"{}", &text[..basemap_start], &text[basemap_end..]);
    assert_eq!(Sidecar::parse(&not_object).unwrap().basemap, None);
    // isNull: absent work_id and snapshot_timestamp are null.
    let minimal = r#"{"bbox":{"west":0,"south":0,"east":1,"north":1},"name":"n","imported_at_millis":1,"report":{"counts":{"nodes":0,"ways":0,"relations":0},"database_bytes":5}}"#;
    let parsed = Sidecar::parse(minimal).unwrap();
    assert_eq!((parsed.work_id, parsed.snapshot_timestamp), (None, None));
    // Unknown basemap kind, malformed work ID, missing field: unknown metadata.
    assert!(Sidecar::parse(&text.replace(r#""kind":"url""#, r#""kind":"ftp""#)).is_none());
    assert!(Sidecar::parse(&text.replace(NEW_RUN, "nope")).is_none());
    assert!(Sidecar::parse(&text.replace(r#""name":"#, r#""nom":"#)).is_none());
}

/// The report's import profile (written by the adapters since 0.3.0) is
/// parsed, normalized and serialized back, so `cantino_area_published`
/// returns it; absent or null is a full import, a malformed one makes the
/// metadata unknown (and is refused at staging).
#[test]
fn sidecar_report_keeps_the_import_profile() {
    let version = Version::new(false);
    let plain = version.sidecar();
    let with_profile = |profile: &str| {
        plain.replace(
            r#""database_bytes":20}"#,
            &format!(r#""database_bytes":20,"profile":{profile}}}"#),
        )
    };
    assert_ne!(with_profile("null"), plain, "the fixture has this report");

    // Round trip: parsed (kinds normalized) and serialized back in the report.
    let parsed = Sidecar::parse(&with_profile(
        r#"{"keep":[{"kinds":"rn","key":"amenity","values":["cafe"]},{"kinds":"w","key":"highway"}]}"#,
    ))
    .unwrap();
    let profile = parsed.report.as_ref().unwrap().profile.as_ref().unwrap();
    assert_eq!(profile.keep[0].kinds, "nr");
    assert_eq!(profile.keep[1].values, None);
    let json = serde_json::to_value(&parsed).unwrap();
    assert_eq!(
        json["report"]["profile"],
        serde_json::json!({"keep":[{"kinds":"nr","key":"amenity","values":["cafe"]},{"kinds":"w","key":"highway"}]})
    );
    assert_eq!(
        Sidecar::parse(&json.to_string()).as_ref(),
        Some(&parsed),
        "the serialized form parses to the same sidecar"
    );

    // Absent or null: a full import, and no key in the serialized report.
    for text in [plain.clone(), with_profile("null")] {
        let parsed = Sidecar::parse(&text).unwrap();
        assert_eq!(parsed.report.as_ref().unwrap().profile, None);
        let json = serde_json::to_value(&parsed).unwrap();
        assert!(json["report"].get("profile").is_none(), "{json}");
    }

    // Malformed (wrong shape, or a profile the core would refuse): unknown
    // metadata, and refused as staged metadata.
    let directory = tempfile::tempdir().unwrap();
    let storage = AreaStorage::new(directory.path());
    for profile in [
        r#""poi""#,
        r#"{}"#,
        r#"{"keep":[]}"#,
        r#"{"keep":[{"kinds":"x","key":"amenity"}]}"#,
        r#"{"keep":[{"kinds":"n","key":""}]}"#,
        r#"{"keep":[{"kinds":"n","key":"amenity","values":[]}]}"#,
        r#"{"keep":[{"kinds":"n","key":"amenity","values":"cafe"}]}"#,
    ] {
        let text = with_profile(profile);
        assert!(Sidecar::parse(&text).is_none(), "{profile}");
        assert!(matches!(
            storage.write_staged_metadata(AREA, NEW_RUN, &text),
            Err(Error::Invalid(_))
        ));
    }
}

/// A basemap-only version publishes without data and removes data an older
/// version had; a later full version brings data back. The journal of a
/// version with data keeps its 0.2.0 bytes; `data:false` appears otherwise.
#[test]
fn a_basemap_only_version_replaces_data_and_back() {
    let directory = tempfile::tempdir().unwrap();
    let storage = AreaStorage::new(directory.path());
    Version::old(true).publish(&storage);
    let basemap_only = Version::new_shaped(Parts::new(false, true));
    basemap_only.stage(&storage);
    let mut journal = None;
    storage
        .commit(AREA, NEW_RUN, basemap_only.parts(), |stage| {
            if stage == CommitStage::AfterCommitPoint {
                journal = Some(fs::read_to_string(storage.journal(AREA)).unwrap());
            }
            true
        })
        .unwrap();
    assert_eq!(
        journal.unwrap(),
        format!(r#"{{"work_id":"{NEW_RUN}","basemap":true,"data":false}}"#)
    );
    assert_published(&storage, &basemap_only, "basemap only");
    assert!(!storage.data(AREA).exists());
    let published = storage.published(AREA).unwrap().unwrap();
    assert_eq!(published.data, None);
    assert_eq!(published.metadata.unwrap().report, None);

    let full = Version {
        run: NEXT_RUN,
        data: Some(b"data again".to_vec()),
        basemap: Some(b"basemap again".to_vec()),
    };
    full.stage(&storage);
    storage
        .commit(AREA, NEXT_RUN, full.parts(), |_| true)
        .unwrap();
    assert_published(&storage, &full, "full again");
}

/// A version must have at least one part, and every part it names must be
/// staged; both are refused before the commit point.
#[test]
fn parts_are_checked_before_the_commit_point() {
    let directory = tempfile::tempdir().unwrap();
    let storage = AreaStorage::new(directory.path());
    let old = Version::old(false);
    old.publish(&storage);
    let basemap_only = Version::new_shaped(Parts::new(false, true));
    basemap_only.stage(&storage);
    let none = storage.commit(AREA, NEW_RUN, Parts::new(false, false), |_| true);
    assert_eq!(none.unwrap_err().kind(), crate::ErrorKind::InvalidArgument);
    // Names data that was never staged.
    let missing = storage.commit(AREA, NEW_RUN, Parts::new(true, true), |_| true);
    assert_eq!(
        missing.unwrap_err().kind(),
        crate::ErrorKind::InvalidArgument
    );
    assert_published(&storage, &old, "refused commits");
}

/// A sidecar whose report and data disagree (a report but no data file, or
/// data but no report) reads as unknown metadata, never as wrong metadata.
#[test]
fn a_report_must_match_the_presence_of_data() {
    let directory = tempfile::tempdir().unwrap();
    let storage = AreaStorage::new(directory.path());
    let basemap_only = Version::new_shaped(Parts::new(false, true));
    basemap_only.publish(&storage);
    // A sidecar claiming data, next to a basemap-only area.
    fs::write(storage.sidecar(AREA), Version::new(true).sidecar()).unwrap();
    assert_eq!(storage.published(AREA).unwrap().unwrap().metadata, None);
    // A data file appears next to a basemap-only sidecar.
    fs::write(storage.sidecar(AREA), basemap_only.sidecar()).unwrap();
    fs::write(storage.data(AREA), b"stray").unwrap();
    assert_eq!(storage.published(AREA).unwrap().unwrap().metadata, None);
}
