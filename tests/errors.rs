//! Every `ErrorKind` is produced by a representative real failure. The kinds
//! are what the platform adapters see (C ABI code, Kotlin exception class),
//! so these tests pin the classification, not just "it fails".
//!
//! WrongThread and Internal need the C ABI (handles, panics); they are
//! covered in `src/mobile_api.rs`'s unit tests and by
//! `scripts/mobile-api-smoke.py`.
use cantino::basemap::format::Header;
use cantino::*;
use std::path::{Path, PathBuf};

fn fixture(name: &str) -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("tests/fixtures")
        .join(name)
}

/// Imports the XML fixture into a fresh directory; returns it and the area path.
fn area() -> (tempfile::TempDir, PathBuf) {
    let directory = tempfile::tempdir().unwrap();
    let destination = directory.path().join("area.sqlite");
    import_area(
        fixture("snapshot.osm"),
        &destination,
        ImportOptions::default(),
    )
    .unwrap();
    (directory, destination)
}

fn kind<T>(result: Result<T>) -> ErrorKind {
    result.err().expect("expected an error").kind()
}

#[test]
fn bad_queries_ids_and_bboxes_are_invalid_arguments() {
    let (_directory, path) = area();
    let store = Store::open(&path).unwrap();
    let invalid = ErrorKind::InvalidArgument;
    // Limit out of range.
    assert_eq!(
        kind(store.query(&Query {
            limit: 0,
            ..Query::default()
        })),
        invalid
    );
    // Only NotExists filters and no bbox: nothing drives the query.
    assert_eq!(
        kind(store.query(&Query {
            tags: vec![TagFilter::NotExists("name".into())],
            ..Query::default()
        })),
        invalid
    );
    // Too many spatial candidates.
    let world = Bbox::new(-180., -85., 180., 85.).unwrap();
    assert_eq!(kind(store.spatial_candidates(world, 1)), invalid);
    // Batch over the cap, non-positive ID, inverted bbox.
    let too_many = vec![OsmId::Node(NodeId(1)); MAX_BATCH + 1];
    assert_eq!(kind(store.get_many(&too_many)), invalid);
    assert_eq!(kind(store.get(OsmId::Node(NodeId(0)))), invalid);
    assert_eq!(kind(Bbox::new(170., 0., -170., 1.)), invalid);
    // Caller JSON that does not parse is the caller's argument, too.
    let json: std::result::Result<Query, _> = serde_json::from_str("{malformed");
    assert_eq!(Error::from(json.unwrap_err()).kind(), invalid);
}

#[test]
fn files_that_are_not_current_areas_are_invalid_files() {
    let (directory, path) = area();
    // Not SQLite at all (SQLITE_NOTADB).
    let text = directory.path().join("text.sqlite");
    std::fs::write(&text, b"this is not a database, just some text.....").unwrap();
    assert_eq!(kind(Store::open(&text)), ErrorKind::InvalidFile);
    // An empty file is an empty SQLite database without our application_id.
    let empty = directory.path().join("empty.sqlite");
    std::fs::write(&empty, b"").unwrap();
    assert_eq!(kind(Store::open(&empty)), ErrorKind::InvalidFile);
    // A Cantino area of another FORMAT_VERSION.
    let old = directory.path().join("old.sqlite");
    std::fs::copy(&path, &old).unwrap();
    rusqlite::Connection::open(&old)
        .unwrap()
        .execute_batch("PRAGMA user_version = 1")
        .unwrap();
    let error = Store::open(&old).err().expect("old format must fail");
    assert_eq!(error.kind(), ErrorKind::InvalidFile);
    assert!(error.to_string().contains("re-import"), "{error}");
}

#[test]
fn corrupt_or_truncated_inputs_are_invalid_files() {
    let directory = tempfile::tempdir().unwrap();
    let destination = directory.path().join("area.sqlite");
    let import = |bytes: &[u8], name: &str| {
        let input = directory.path().join(name);
        std::fs::write(&input, bytes).unwrap();
        import_area(&input, &destination, ImportOptions::default())
    };
    // Garbage, a PBF cut in the middle, malformed XML, XML breaking the
    // snapshot contract (unsorted IDs).
    assert_eq!(
        kind(import(b"not a PBF", "garbage.pbf")),
        ErrorKind::InvalidFile
    );
    let pbf = std::fs::read(fixture("snapshot.osm.pbf")).unwrap();
    assert_eq!(
        kind(import(&pbf[..pbf.len() / 2], "truncated.pbf")),
        ErrorKind::InvalidFile
    );
    assert_eq!(
        kind(import(b"<osm version=\"0.6\"><node id=", "broken.osm")),
        ErrorKind::InvalidFile
    );
    assert_eq!(
        kind(import(
            b"<osm version=\"0.6\"><node id=\"2\" lat=\"0\" lon=\"0\"/><node id=\"1\" lat=\"1\" lon=\"1\"/></osm>",
            "unsorted.osm"
        )),
        ErrorKind::InvalidFile
    );
    assert!(!destination.exists());
    // A malformed PMTiles header.
    assert_eq!(kind(Header::parse(&[0u8; 127])), ErrorKind::InvalidFile);
}

#[test]
fn missing_files_and_unwritable_destinations_are_io() {
    let directory = tempfile::tempdir().unwrap();
    let missing = directory.path().join("missing.sqlite");
    // Opening a missing area: SQLITE_CANTOPEN (read-only, no create).
    assert_eq!(kind(Store::open(&missing)), ErrorKind::Io);
    // Importing a missing input, as PBF and as XML would both start by
    // opening it.
    assert_eq!(
        kind(import_area(
            directory.path().join("missing.osm.pbf"),
            directory.path().join("area.sqlite"),
            ImportOptions::default()
        )),
        ErrorKind::Io
    );
    // A destination in a directory that does not exist.
    assert_eq!(
        kind(import_area(
            fixture("snapshot.osm"),
            directory.path().join("no/such/dir/area.sqlite"),
            ImportOptions::default()
        )),
        ErrorKind::Io
    );
}

#[test]
fn kinds_round_trip_through_their_abi_codes() {
    for kind in [
        ErrorKind::InvalidArgument,
        ErrorKind::InvalidFile,
        ErrorKind::Io,
        ErrorKind::WrongThread,
        ErrorKind::Internal,
    ] {
        assert_eq!(ErrorKind::from_code(kind.code()), Some(kind));
    }
    // 0 is "no error"; unknown codes are not guessed.
    assert_eq!(ErrorKind::from_code(0), None);
    assert_eq!(ErrorKind::from_code(99), None);
    // The values are ABI (include/cantino.h CANTINO_ERROR_*).
    assert_eq!(ErrorKind::InvalidArgument.code(), 1);
    assert_eq!(ErrorKind::Internal.code(), 5);
    assert_eq!(
        Error::WrongThread("x".into()).kind(),
        ErrorKind::WrongThread
    );
    assert_eq!(Error::Internal("x".into()).kind(), ErrorKind::Internal);
}
