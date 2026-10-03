use osm_framework::*;
use std::path::Path;

fn fixture() -> &'static Path {
    Path::new(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/tests/fixtures/snapshot.osm"
    ))
}
fn import() -> (tempfile::TempDir, Store) {
    let directory = tempfile::tempdir().unwrap();
    let destination = directory.path().join("area.osmx");
    // Three pairs per run exercises the external merge sorter, not just its
    // in-memory fast path. All handles close before their temporary directories.
    import_area(
        fixture(),
        &destination,
        ImportOptions {
            sort_pairs: 3,
            ..ImportOptions::default()
        },
    )
    .unwrap();
    let store = Store::open(&destination).unwrap();
    (directory, store)
}
#[test]
fn raw_objects_survive_reopen_with_typed_identity_and_metadata() {
    let (directory, store) = import();
    assert_eq!(
        store.counts().unwrap(),
        Counts {
            nodes: 4,
            ways: 2,
            relations: 1
        }
    );
    let Some(Object::Way(way)) = store.get(OsmId::Way(WayId(1))).unwrap() else {
        panic!()
    };
    assert_eq!(way.nodes, vec![NodeId(1), NodeId(2), NodeId(1)]);
    let node = store.get(OsmId::Node(NodeId(1))).unwrap().unwrap();
    let metadata = node.metadata().unwrap();
    assert_eq!(
        (metadata.version, metadata.changeset, metadata.uid),
        (3, 123, 7)
    );
    assert_eq!(metadata.user, "Mapper");
    assert_ne!(metadata.timestamp, 0);
    drop(store);
    let reopened = Store::open(directory.path().join("area.osmx")).unwrap();
    assert_eq!(reopened.get(node.id()).unwrap(), Some(node));
    assert_eq!(reopened.get(OsmId::Node(NodeId(99))).unwrap(), None);
}
#[test]
fn unicode_tags_and_and_filters_work_offline() {
    let (_directory, store) = import();
    let query = Query {
        tags: vec![
            TagFilter::Equals("amenity".into(), "cafe".into()),
            TagFilter::Exists("name".into()),
        ],
        ..Query::default()
    };
    let hits = store.query(&query).unwrap();
    assert_eq!(hits.len(), 1);
    assert_eq!(hits[0].tag("name"), Some("Café Test"));
    assert_eq!(hits[0].tag("outdoor_seating"), None);
}
#[test]
fn ordered_geometry_and_missing_references_remain_explicit() {
    let (_directory, store) = import();
    let coordinates = store.way_coordinates(WayId(1)).unwrap().unwrap();
    assert_eq!(coordinates.len(), 3);
    assert_eq!(coordinates[0], coordinates[2]);
    let missing = store
        .missing_references(OsmId::Relation(RelationId(1)))
        .unwrap()
        .unwrap();
    assert_eq!(
        missing,
        vec![MissingReference {
            position: 1,
            target: OsmId::Node(NodeId(99))
        }]
    );
    let graph = store
        .dependencies(OsmId::Relation(RelationId(1)), 10)
        .unwrap();
    assert_eq!(graph.objects.len(), 4);
    assert_eq!(graph.missing, vec![OsmId::Node(NodeId(99))]);
    assert!(
        store
            .dependencies(OsmId::Relation(RelationId(1)), 1)
            .is_err()
    );
}
#[test]
fn bbox_results_are_candidates_with_bounded_memory() {
    let (_directory, store) = import();
    let bbox = Bbox::new(-111.002, 39.999, -110.998, 40.002).unwrap();
    let candidates = store.spatial_candidates(bbox, 100).unwrap();
    assert!(candidates.contains(&OsmId::Way(WayId(1))));
    // This documents upstream's node-based spatial semantics explicitly.
    assert!(!candidates.contains(&OsmId::Way(WayId(2))));
    assert!(store.spatial_candidates(bbox, 1).is_err());
    let cafes = store
        .query(&Query {
            bbox: Some(bbox),
            tags: vec![TagFilter::Equals("amenity".into(), "cafe".into())],
            ..Query::default()
        })
        .unwrap();
    assert_eq!(cafes.len(), 1);
}
#[test]
fn keyset_pages_keep_kind_and_id_order() {
    let (_directory, store) = import();
    let mut cursor = None;
    let mut ids = vec![];
    loop {
        let objects = store
            .query(&Query {
                after: cursor,
                limit: 2,
                ..Query::default()
            })
            .unwrap();
        if objects.is_empty() {
            break;
        }
        cursor = objects.last().map(Object::id);
        ids.extend(objects.iter().map(Object::id));
    }
    assert_eq!(ids.len(), 7);
    assert!(ids.windows(2).all(|pair| pair[0] < pair[1]));
}
#[test]
fn failed_import_keeps_previous_area_and_cleans_staging() {
    let (directory, store) = import();
    let destination = directory.path().join("area.osmx");
    let before = std::fs::read(&destination).unwrap();
    let corrupt = directory.path().join("corrupt.osm.pbf");
    std::fs::write(&corrupt, b"not a PBF").unwrap();
    assert!(import_area(&corrupt, &destination, ImportOptions::default()).is_err());
    assert_eq!(std::fs::read(&destination).unwrap(), before);
    assert_eq!(store.counts().unwrap().nodes, 4);
    assert!(!std::fs::read_dir(directory.path()).unwrap().any(|entry| {
        entry
            .unwrap()
            .file_name()
            .to_string_lossy()
            .starts_with(".osmx-stage-")
    }));
}
#[test]
fn a_second_environment_is_rejected_and_owned_objects_outlive_store() {
    let (directory, store) = import();
    assert!(Store::open(directory.path().join("area.osmx")).is_err());
    let object = store.get(OsmId::Node(NodeId(2))).unwrap().unwrap();
    drop(store);
    assert_eq!(object.tag("name"), Some("Café Test"));
    assert!(Store::open(directory.path().join("area.osmx")).is_ok());
}
#[test]
fn empty_snapshot_and_cycles_do_not_hang() {
    let directory = tempfile::tempdir().unwrap();
    let input = directory.path().join("cyclic.osm");
    let area = directory.path().join("area.osmx");
    std::fs::write(&input,"<osm version=\"0.6\"><relation id=\"1\"><member type=\"relation\" ref=\"1\" role=\"self\"/></relation></osm>").unwrap();
    import_area(&input, &area, ImportOptions::default()).unwrap();
    let store = Store::open(&area).unwrap();
    assert_eq!(
        store
            .dependencies(OsmId::Relation(RelationId(1)), 2)
            .unwrap()
            .objects
            .len(),
        1
    );
    assert!(
        store
            .spatial_candidates(Bbox::new(-1., -1., 1., 1.).unwrap(), 10)
            .unwrap()
            .is_empty()
    );
}
#[test]
fn duplicate_or_unsorted_snapshot_ids_fail_without_publishing() {
    let directory = tempfile::tempdir().unwrap();
    let input = directory.path().join("bad.osm");
    let area = directory.path().join("area.osmx");
    std::fs::write(&input,"<osm version=\"0.6\"><node id=\"2\" lat=\"0\" lon=\"0\"/><node id=\"1\" lat=\"1\" lon=\"1\"/></osm>").unwrap();
    assert!(import_area(&input, &area, ImportOptions::default()).is_err());
    assert!(!area.exists());
}
#[test]
fn validation_rejects_bad_coordinates_ids_and_limits() {
    assert!(Coordinate::from_degrees(f64::NAN, 0.).is_err());
    assert!(Bbox::new(170., 0., -170., 1.).is_err());
    let (_directory, store) = import();
    assert!(store.get(OsmId::Node(NodeId(-1))).is_err());
    assert!(
        store
            .query(&Query {
                limit: 0,
                ..Query::default()
            })
            .is_err()
    );
    assert!(store.scan(ObjectKind::Node, 0, 10001).is_err());
}
#[test]
fn slice_request_preserves_bbox_order_and_validates_job_ids() {
    let body = slice::create_job_body("test", Bbox::new(-111., 40., -110., 41.).unwrap()).unwrap();
    assert_eq!(
        body["RegionData"],
        serde_json::json!([40., -111., 41., -110.])
    );
    assert!(slice::JobId::parse("../../etc/passwd").is_err());
    let job = slice::JobId::parse("2637da98-20a1-428f-b6db-18ac2861b763").unwrap();
    assert!(job.download_url().ends_with(".osm.pbf"));
    let progress: slice::Progress =
        serde_json::from_str("{\"Complete\":true,\"Timestamp\":\"x\",\"SizeBytes\":12}").unwrap();
    assert!(progress.complete);
}

#[test]
fn duplicate_tags_and_deleted_objects_do_not_activate() {
    let directory = tempfile::tempdir().unwrap();
    let input = directory.path().join("bad.osm");
    let area = directory.path().join("area.osmx");
    for xml in [
        "<osm version=\"0.6\"><node id=\"1\" lat=\"0\" lon=\"0\"><tag k=\"name\" v=\"one\"/><tag k=\"name\" v=\"two\"/></node></osm>",
        "<osm version=\"0.6\"><node id=\"1\" visible=\"false\" lat=\"0\" lon=\"0\"/></osm>",
    ] {
        std::fs::write(&input, xml).unwrap();
        assert!(import_area(&input, &area, ImportOptions::default()).is_err());
        assert!(!area.exists());
    }
}

#[test]
fn successful_replacement_keeps_old_handle_until_reopen() {
    let (directory, store) = import();
    let input = directory.path().join("new.osm");
    let area = directory.path().join("area.osmx");
    std::fs::write(
        &input,
        "<osm version=\"0.6\"><node id=\"8\" lat=\"0\" lon=\"0\"/></osm>",
    )
    .unwrap();
    import_area(&input, &area, ImportOptions::default()).unwrap();
    assert_eq!(store.counts().unwrap().nodes, 4);
    assert!(store.get(OsmId::Node(NodeId(8))).unwrap().is_none());
    drop(store);
    let reopened = Store::open(&area).unwrap();
    assert_eq!(reopened.counts().unwrap().nodes, 1);
    assert!(reopened.get(OsmId::Node(NodeId(8))).unwrap().is_some());
}
