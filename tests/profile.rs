//! Import profiles: tag-filtered import with osmium-style reference closure.
use cantino::*;
use std::path::{Path, PathBuf};

/// `tests/fixtures/profile.osm` (and its PBF twin, made with
/// `osmium cat profile.osm -o profile.osm.pbf`):
///
/// - node 1 `amenity=cafe` (matches), node 5 `amenity=bench` (wrong value),
///   node 10 `shop=bakery` (the shop rule is for ways only)
/// - way 10 `shop=supermarket` (matches) → keeps nodes 2, 3, 4
/// - way 11 `building=yes`, only in relation 23 (not kept) → dropped with node 6
/// - way 12 `note=…`, outer of relation 20 → kept whole, keeps nodes 7, 9
/// - relation 20 `amenity=cafe` → keeps way 12, child relation 21; node 999 absent
/// - relations 21 ↔ 22 form a cycle; they keep nodes 8 and 9
/// - relation 23 `type=route` → dropped
fn fixture(name: &str) -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("tests/fixtures")
        .join(name)
}

fn profile() -> ImportProfile {
    ImportProfile {
        keep: vec![
            KeepRule {
                kinds: "rwn".into(),
                key: "amenity".into(),
                values: Some(vec!["cafe".into()]),
            },
            KeepRule {
                kinds: "w".into(),
                key: "shop".into(),
                values: None,
            },
        ],
    }
}

fn import(
    input: &Path,
    profile: Option<ImportProfile>,
) -> (tempfile::TempDir, ImportReport, Store) {
    let directory = tempfile::tempdir().unwrap();
    let area = directory.path().join("area.sqlite");
    let options = ImportOptions {
        profile,
        ..ImportOptions::default()
    };
    let report = import_area(input, &area, options).unwrap();
    let store = Store::open(&area).unwrap();
    (directory, report, store)
}

fn present(store: &Store, id: OsmId) -> bool {
    store.get(id).unwrap().is_some()
}

#[test]
fn closure_keeps_matches_and_their_references_from_xml_and_pbf() {
    for input in ["profile.osm", "profile.osm.pbf"] {
        let (_directory, report, store) = import(&fixture(input), Some(profile()));
        let kept =
            |ids: &[i64], make: fn(i64) -> OsmId| ids.iter().all(|id| present(&store, make(*id)));
        let gone =
            |ids: &[i64], make: fn(i64) -> OsmId| ids.iter().all(|id| !present(&store, make(*id)));
        let node = |id| OsmId::Node(NodeId(id));
        let way = |id| OsmId::Way(WayId(id));
        let relation = |id| OsmId::Relation(RelationId(id));
        assert!(kept(&[1, 2, 3, 4, 7, 8, 9], node), "{input}: kept nodes");
        assert!(gone(&[5, 6, 10], node), "{input}: dropped nodes");
        assert!(kept(&[10, 12], way), "{input}: kept ways");
        assert!(gone(&[11], way), "{input}: dropped ways");
        assert!(kept(&[20, 21, 22], relation), "{input}: kept relations");
        assert!(gone(&[23], relation), "{input}: dropped relations");
        assert_eq!(
            report.counts,
            Counts {
                nodes: 7,
                ways: 2,
                relations: 3
            },
            "{input}"
        );
        assert_eq!(store.counts().unwrap(), report.counts);

        // Kept by reference = kept whole, tags included.
        let Some(Object::Way(way12)) = store.get(way(12)).unwrap() else {
            panic!("{input}: way 12 missing");
        };
        assert_eq!(
            way12.tags.get("note").map(String::as_str),
            Some("kept by reference")
        );
        // Tag queries see only what was kept.
        let cafes = store
            .query(&Query {
                tags: vec![TagFilter::Equals("amenity".into(), "cafe".into())],
                ..Query::default()
            })
            .unwrap();
        let ids: Vec<OsmId> = cafes.iter().map(Object::id).collect();
        assert_eq!(ids, vec![node(1), relation(20)], "{input}");
    }
}

#[test]
fn profile_is_recorded_normalized() {
    let (_directory, report, store) = import(&fixture("profile.osm"), Some(profile()));
    let recorded = report.profile.clone().expect("report carries the profile");
    assert_eq!(
        recorded.keep[0].kinds, "nwr",
        "kinds are put in n, w, r order"
    );
    assert_eq!(recorded, profile().normalized().unwrap());
    assert_eq!(store.profile().unwrap(), Some(recorded));
    // JSON form used by the C ABI and Kotlin: absent values are omitted.
    let json = serde_json::to_value(&report).unwrap();
    assert_eq!(
        json["profile"],
        serde_json::json!({"keep":[
            {"kinds":"nwr","key":"amenity","values":["cafe"]},
            {"kinds":"w","key":"shop"}
        ]})
    );
}

#[test]
fn no_profile_imports_everything_and_records_none() {
    let (_directory, report, store) = import(&fixture("profile.osm"), None);
    assert_eq!(
        report.counts,
        Counts {
            nodes: 10,
            ways: 3,
            relations: 4
        }
    );
    assert_eq!(report.profile, None);
    assert_eq!(store.profile().unwrap(), None);
    assert!(
        serde_json::to_value(&report)
            .unwrap()
            .get("profile")
            .is_none()
    );
}

#[test]
fn invalid_profiles_are_rejected_before_anything_is_written() {
    let rule = |kinds: &str, key: &str, values: Option<Vec<String>>| KeepRule {
        kinds: kinds.into(),
        key: key.into(),
        values,
    };
    for bad in [
        ImportProfile { keep: vec![] },
        ImportProfile {
            keep: vec![rule("nwr", "", None)],
        },
        ImportProfile {
            keep: vec![rule("", "amenity", None)],
        },
        ImportProfile {
            keep: vec![rule("nx", "amenity", None)],
        },
        ImportProfile {
            keep: vec![rule("n", "amenity", Some(vec![]))],
        },
    ] {
        let directory = tempfile::tempdir().unwrap();
        let area = directory.path().join("area.sqlite");
        let options = ImportOptions {
            profile: Some(bad.clone()),
            ..ImportOptions::default()
        };
        let error = import_area(fixture("profile.osm"), &area, options).unwrap_err();
        assert_eq!(error.kind(), ErrorKind::InvalidArgument, "{bad:?}: {error}");
        assert_eq!(
            std::fs::read_dir(directory.path()).unwrap().count(),
            0,
            "{bad:?}"
        );
    }
}

#[test]
fn options_json_accepts_a_profile() {
    let options: ImportOptions =
        serde_json::from_str(r#"{"profile":{"keep":[{"kinds":"n","key":"amenity"}]}}"#).unwrap();
    assert_eq!(options.cache_mb, ImportOptions::default().cache_mb);
    assert_eq!(options.profile.unwrap().keep[0].key, "amenity");
}
