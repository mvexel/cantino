//! Compatibility of the Rust area store with Cantino 0.2.0's on-disk layout.
//!
//! `tests/fixtures/area-storage-0.2.0/` holds one directory per scenario,
//! each laid out exactly as 0.2.0's Kotlin `AreaStorage` left
//! `filesDir/cantino-areas/` (derived line by line from
//! `android/cantino/src/main/java/lol/osm/cantino/AreaStorage.kt` at
//! v0.2.0): file names, the journal text `{"work_id":"…","basemap":…}`, and
//! sidecars as Android's org.json writes them (insertion order, no
//! whitespace, `/` escaped as `\/`, integral doubles without `.0`, non-ASCII
//! raw). The `.sqlite`/`.pmtiles` files are small placeholders: the store
//! never opens them, it only moves them and compares their sizes with the
//! sidecar. Old and new versions differ in size so a mix is detectable.
//!
//! | Scenario | State 0.2.0 left |
//! | --- | --- |
//! | `published` | data + basemap + sidecar, nothing pending |
//! | `pending-commit` | killed after the commit point, before any rename |
//! | `partial-roll-forward` | killed after basemap and data were renamed, before the sidecar |
//! | `pending-commit-no-basemap` | pending commit of a version without basemap over one with |
//! | `killed-before-commit-point` | killed while writing the journal (temporary file left), staging left |
//! | `metadata-unknown` | data replaced by hand: the sidecar no longer describes it |
//! | `hand-published` | data only, no sidecar |
//!
//! Every test copies its scenario into a temporary directory first; the
//! fixture itself is never modified.
use cantino::area_storage::{AreaStorage, Parts, Sidecar};
use std::{
    collections::BTreeMap,
    fs,
    path::{Path, PathBuf},
};

const OLD: &str = "0f8fad5b-d9cb-469f-a165-70867728950e";
const NEW: &str = "7c9e6679-7425-40de-944b-e07fc1f90ae7";
const OLD_DATA: &[u8] = b"0.2.0 fixture: OLD area data (placeholder for a SQLite file)\n";
const NEW_DATA: &[u8] =
    b"0.2.0 fixture: NEW area data, a different size (placeholder for a SQLite file)\n";
const OLD_BASEMAP: &[u8] = b"0.2.0 fixture: OLD basemap (PMTiles placeholder)\n";
const NEW_BASEMAP: &[u8] =
    b"0.2.0 fixture: NEW basemap, also a different size (PMTiles placeholder)\n";

fn fixture(scenario: &str) -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("tests/fixtures/area-storage-0.2.0")
        .join(scenario)
}

fn copy_tree(from: &Path, to: &Path) {
    fs::create_dir_all(to).unwrap();
    for entry in fs::read_dir(from).unwrap() {
        let entry = entry.unwrap();
        let target = to.join(entry.file_name());
        if entry.file_type().unwrap().is_dir() {
            copy_tree(&entry.path(), &target);
        } else {
            fs::copy(entry.path(), target).unwrap();
        }
    }
}

/// A scratch copy of `scenario`; the directory guard keeps it alive.
fn scenario(name: &str) -> (tempfile::TempDir, AreaStorage, PathBuf) {
    let directory = tempfile::tempdir().unwrap();
    let root = directory.path().join(name);
    copy_tree(&fixture(name), &root);
    let storage = AreaStorage::new(&root);
    (directory, storage, root)
}

/// Every regular file under `root`, by relative path, with its contents.
fn files(root: &Path) -> BTreeMap<String, Vec<u8>> {
    fn walk(root: &Path, at: &Path, out: &mut BTreeMap<String, Vec<u8>>) {
        for entry in fs::read_dir(at).unwrap() {
            let path = entry.unwrap().path();
            if path.is_dir() {
                walk(root, &path, out);
            } else {
                let relative = path
                    .strip_prefix(root)
                    .unwrap()
                    .to_string_lossy()
                    .into_owned();
                out.insert(relative, fs::read(&path).unwrap());
            }
        }
    }
    let mut out = BTreeMap::new();
    walk(root, root, &mut out);
    out
}

fn sidecar_text(root: &Path) -> String {
    fs::read_to_string(root.join("city.json")).unwrap()
}

/// The published area is exactly `(data, basemap)` with the sidecar of `run`.
fn assert_area(storage: &AreaStorage, data: &[u8], basemap: Option<&[u8]>, run: &str) -> Sidecar {
    let published = storage.published("city").unwrap().expect("an area");
    assert_eq!(fs::read(published.data.as_ref().unwrap()).unwrap(), data);
    assert_eq!(
        published
            .basemap
            .as_ref()
            .map(|path| fs::read(path).unwrap()),
        basemap.map(<[u8]>::to_vec)
    );
    let metadata = published.metadata.expect("metadata describing the files");
    assert_eq!(metadata.work_id.as_deref(), Some(run));
    metadata
}

#[test]
fn reads_a_published_0_2_0_area() {
    let (_guard, storage, root) = scenario("published");
    let before = files(&root);
    let metadata = assert_area(&storage, OLD_DATA, Some(OLD_BASEMAP), OLD);
    // org.json's escapes and number forms read back as the values Kotlin wrote.
    assert_eq!(metadata.name, "Café / old");
    assert_eq!(metadata.bbox.south, 40.0);
    assert_eq!(metadata.bbox.west, -111.895);
    assert_eq!(
        metadata.snapshot_timestamp.as_deref(),
        Some("2026-10-03T20:30:01Z")
    );
    assert_eq!(metadata.imported_at_millis, 1_759_523_401_000);
    assert_eq!(metadata.report.unwrap().counts.nodes, 15912);
    assert_eq!(
        metadata.report.unwrap().database_bytes,
        OLD_DATA.len() as i64
    );
    let basemap = metadata.basemap.expect("basemap metadata");
    assert_eq!(basemap.kind, "extract");
    assert_eq!(
        basemap.source_url,
        "https://build.protomaps.com/20261003.pmtiles"
    );
    assert_eq!(
        (basemap.min_zoom, basemap.max_zoom, basemap.requests),
        (0, 15, 24)
    );
    // Reading changed nothing but adding the (new, empty) lock file.
    let mut after = files(&root);
    assert_eq!(after.remove("city.lock"), Some(Vec::new()));
    assert_eq!(after, before);
}

#[test]
fn rolls_a_0_2_0_pending_commit_forward() {
    let (_guard, storage, root) = scenario("pending-commit");
    storage.recover("city").unwrap();
    let metadata = assert_area(&storage, NEW_DATA, Some(NEW_BASEMAP), NEW);
    assert_eq!(metadata.name, "Café / new");
    let after = files(&root);
    assert!(!after.contains_key("city.commit"));
    assert!(!root.join(".staging/city").join(NEW).exists());
    // The staged sidecar was moved verbatim (byte-identical to what 0.2.0 wrote).
    let staged = fs::read_to_string(
        fixture("pending-commit").join(format!(".staging/city/{NEW}/area.json")),
    )
    .unwrap();
    assert_eq!(sidecar_text(&root), staged);
}

#[test]
fn finishes_a_0_2_0_roll_forward_killed_halfway() {
    let (_guard, storage, root) = scenario("partial-roll-forward");
    // Before recovery the sidecar (old) does not describe the data (new):
    // the 0.2.0 reader would have flagged it, and a reader here never sees
    // it because published() recovers first.
    let old = Sidecar::parse(&sidecar_text(&root)).unwrap();
    assert_ne!(old.report.unwrap().database_bytes, NEW_DATA.len() as i64);
    assert_area(&storage, NEW_DATA, Some(NEW_BASEMAP), NEW);
    assert!(!root.join("city.commit").exists());
}

#[test]
fn a_0_2_0_commit_without_basemap_removes_the_old_basemap() {
    let (_guard, storage, root) = scenario("pending-commit-no-basemap");
    storage.recover("city").unwrap();
    let metadata = assert_area(&storage, NEW_DATA, None, NEW);
    assert_eq!(metadata.basemap, None);
    assert!(!root.join("city.pmtiles").exists());
}

#[test]
fn a_0_2_0_kill_before_the_commit_point_keeps_the_old_area() {
    let (_guard, storage, root) = scenario("killed-before-commit-point");
    let before = files(&root);
    storage.recover("city").unwrap();
    assert_area(&storage, OLD_DATA, Some(OLD_BASEMAP), OLD);
    // Recover leaves the staging directory (no journal: a live run might
    // own it) and the temporary journal (never read).
    let mut after = files(&root);
    after.remove("city.lock");
    assert_eq!(after, before);
    // The next run removes the dead run's staging directory.
    let next = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
    storage.prepare_staging("city", next).unwrap();
    let staged: Vec<_> = fs::read_dir(root.join(".staging/city"))
        .unwrap()
        .map(|entry| entry.unwrap().file_name().into_string().unwrap())
        .collect();
    assert_eq!(staged, [next]);
}

#[test]
fn a_0_2_0_sidecar_that_does_not_describe_the_files_is_unknown() {
    let (_guard, storage, _root) = scenario("metadata-unknown");
    let published = storage.published("city").unwrap().expect("an area");
    assert_eq!(
        fs::read(published.data.as_ref().unwrap()).unwrap(),
        NEW_DATA
    );
    assert_eq!(published.metadata, None);

    let (_guard, storage, _root) = scenario("hand-published");
    let published = storage.published("city").unwrap().expect("an area");
    assert_eq!((published.basemap, published.metadata), (None, None));
}

/// A new commit over a 0.2.0 area, with the same layout: the published
/// files keep 0.2.0's names and the journal 0.2.0's text.
#[test]
fn commits_over_a_0_2_0_area_with_the_same_layout() {
    let (_guard, storage, root) = scenario("published");
    let run = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
    let layout = storage.prepare_staging("city", run).unwrap();
    fs::write(layout.staged_data.as_ref().unwrap(), NEW_DATA).unwrap();
    let sidecar = fs::read_to_string(
        fixture("pending-commit").join(format!(".staging/city/{NEW}/area.json")),
    )
    .unwrap()
    .replace(NEW, run)
    .replace(&format!(r#""bytes":{}"#, NEW_BASEMAP.len()), r#""bytes":0"#);
    // A version without basemap: sidecar says null.
    let sidecar = {
        let start = sidecar.find(r#""basemap":{"#).unwrap() + r#""basemap":"#.len();
        let end = sidecar[start..].find('}').unwrap() + start + 1;
        format!("{}null{}", &sidecar[..start], &sidecar[end..])
    };
    storage
        .write_staged_metadata("city", run, &sidecar)
        .unwrap();
    let mut journal = None;
    storage
        .commit("city", run, Parts::new(true, false), |stage| {
            if stage == cantino::area_storage::CommitStage::AfterCommitPoint {
                journal = Some(fs::read_to_string(root.join("city.commit")).unwrap());
            }
            true
        })
        .unwrap();
    assert_eq!(
        journal.as_deref(),
        Some(r#"{"work_id":"aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee","basemap":false}"#)
    );
    assert_area(&storage, NEW_DATA, None, run);
    let names: Vec<_> = files(&root).into_keys().collect();
    assert_eq!(names, ["city.json", "city.lock", "city.sqlite"]);
}
