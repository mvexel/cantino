use osm_framework::*;
use std::path::Path;

fn fixture() -> &'static Path {
    Path::new(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/tests/fixtures/snapshot.osm"
    ))
}
fn import() -> (tempfile::TempDir, Store) {
    import_with(fixture(), true)
}
/// Imports `input` into a fresh temporary directory. All handles close before
/// their temporary directories.
fn import_with(input: &Path, preserve_untagged_metadata: bool) -> (tempfile::TempDir, Store) {
    let directory = tempfile::tempdir().unwrap();
    let destination = directory.path().join("area.sqlite");
    import_area(
        input,
        &destination,
        ImportOptions {
            preserve_untagged_metadata,
            ..ImportOptions::default()
        },
    )
    .unwrap();
    let store = Store::open(&destination).unwrap();
    (directory, store)
}
/// Every object of every kind, in (kind, id) order.
fn everything(store: &Store) -> Vec<Object> {
    [ObjectKind::Node, ObjectKind::Way, ObjectKind::Relation]
        .into_iter()
        .flat_map(|kind| store.scan(kind, 0, 10000).unwrap())
        .collect()
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
    let reopened = Store::open(directory.path().join("area.sqlite")).unwrap();
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
fn bbox_results_are_bounds_candidates_with_bounded_memory() {
    let (_directory, store) = import();
    let bbox = Bbox::new(-111.002, 39.999, -110.998, 40.002).unwrap();
    let candidates = store.spatial_candidates(bbox, 100).unwrap();
    assert!(candidates.contains(&OsmId::Way(WayId(1))));
    // Way 2 runs from 39.9 to 40.1 straight through the box with both nodes
    // outside it. Bounds intersection finds it (OSMExpress's node cells did not).
    assert!(candidates.contains(&OsmId::Way(WayId(2))));
    // The relation's bounds come from way 1.
    assert!(candidates.contains(&OsmId::Relation(RelationId(1))));
    // Tagged nodes are exact points; untagged vertices are not indexed.
    assert!(candidates.contains(&OsmId::Node(NodeId(2))));
    assert!(!candidates.contains(&OsmId::Node(NodeId(1))));
    assert!(candidates.windows(2).all(|pair| pair[0] < pair[1]));
    assert!(store.spatial_candidates(bbox, 1).is_err());
    let crossing = store
        .query(&Query {
            bbox: Some(bbox),
            tags: vec![TagFilter::Equals("highway".into(), "path".into())],
            ..Query::default()
        })
        .unwrap();
    assert_eq!(
        crossing.iter().map(Object::id).collect::<Vec<_>>(),
        vec![OsmId::Way(WayId(2))]
    );
    // Point-in-box is exact at e7 resolution, edges included: node 2 sits at
    // latitude 40.001, so a box starting there contains it and a box starting
    // 1e-7 degrees further north does not.
    let node = OsmId::Node(NodeId(2));
    let edge = Bbox::new(-111.002, 40.001, -111.0, 40.002).unwrap();
    assert!(store.spatial_candidates(edge, 100).unwrap().contains(&node));
    let beside = Bbox::new(-111.002, 40.0010001, -111.0, 40.002).unwrap();
    assert!(
        !store
            .spatial_candidates(beside, 100)
            .unwrap()
            .contains(&node)
    );
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
    let destination = directory.path().join("area.sqlite");
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
            .starts_with(".osmfw-stage-")
    }));
}
#[test]
fn several_stores_share_an_area_and_owned_objects_outlive_store() {
    // The OSMExpress/LMDB rule "one Store per path per process" is gone: the
    // published file is read-only and SQLite allows any number of readers.
    let (directory, store) = import();
    let second = Store::open(directory.path().join("area.sqlite")).unwrap();
    assert_eq!(second.counts().unwrap(), store.counts().unwrap());
    drop(second);
    let object = store.get(OsmId::Node(NodeId(2))).unwrap().unwrap();
    drop(store);
    assert_eq!(object.tag("name"), Some("Café Test"));
    assert!(Store::open(directory.path().join("area.sqlite")).is_ok());
}
#[test]
fn empty_snapshot_and_cycles_do_not_hang() {
    let directory = tempfile::tempdir().unwrap();
    let input = directory.path().join("cyclic.osm");
    let area = directory.path().join("area.sqlite");
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
    let area = directory.path().join("area.sqlite");
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
    let area = directory.path().join("area.sqlite");
    for xml in [
        "<osm version=\"0.6\"><node id=\"1\" lat=\"0\" lon=\"0\"><tag k=\"name\" v=\"one\"/><tag k=\"name\" v=\"two\"/></node></osm>",
        "<osm version=\"0.6\"><node id=\"1\" visible=\"false\" lat=\"0\" lon=\"0\"/></osm>",
        // Fails only at the last object, after rows were already written.
        "<osm version=\"0.6\"><node id=\"1\" lat=\"0\" lon=\"0\"/><way id=\"1\"><nd ref=\"1\"/></way><relation id=\"1\" visible=\"false\"/></osm>",
        // Kinds out of order and change files are not snapshots.
        "<osm version=\"0.6\"><way id=\"1\"/><node id=\"1\" lat=\"0\" lon=\"0\"/></osm>",
        "<osmChange version=\"0.6\"><create><node id=\"1\" lat=\"0\" lon=\"0\"/></create></osmChange>",
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
    let area = directory.path().join("area.sqlite");
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

/// Objects of all three kinds share a tag, so pages must cross namespaces.
const MIXED: &str = r#"<osm version="0.6">
 <node id="5" lat="1" lon="1"><tag k="amenity" v="cafe"/></node>
 <node id="6" lat="1" lon="1"><tag k="amenity" v="bench"/></node>
 <node id="9" lat="2" lon="2"><tag k="amenity" v="cafe"/><tag k="name" v="B"/></node>
 <way id="3"><nd ref="5"/><nd ref="9"/><tag k="amenity" v="cafe"/></way>
 <way id="4"><nd ref="5"/><nd ref="6"/><tag k="amenity" v="parking"/></way>
 <relation id="7"><member type="way" ref="3" role=""/><tag k="amenity" v="cafe"/><tag k="name" v="A"/></relation>
</osm>"#;

#[test]
fn tag_queries_paginate_across_kinds() {
    let directory = tempfile::tempdir().unwrap();
    let input = directory.path().join("mixed.osm");
    std::fs::write(&input, MIXED).unwrap();
    let (_area, store) = import_with(&input, false);
    let pages = |tags: Vec<TagFilter>, bbox: Option<Bbox>| {
        let mut cursor = None;
        let mut ids = vec![];
        loop {
            let page = store
                .query(&Query {
                    tags: tags.clone(),
                    bbox,
                    after: cursor,
                    limit: 1,
                    ..Query::default()
                })
                .unwrap();
            let Some(last) = page.last() else { break };
            assert_eq!(page.len(), 1);
            cursor = Some(last.id());
            ids.push(last.id());
        }
        ids
    };
    let cafes = vec![
        OsmId::Node(NodeId(5)),
        OsmId::Node(NodeId(9)),
        OsmId::Way(WayId(3)),
        OsmId::Relation(RelationId(7)),
    ];
    let cafe = TagFilter::Equals("amenity".into(), "cafe".into());
    assert_eq!(pages(vec![cafe.clone()], None), cafes);
    // Exists reads every value of the key and must still come out in order.
    let amenity = TagFilter::Exists("amenity".into());
    assert_eq!(pages(vec![amenity], None).len(), 6);
    // ANDed filters, whichever drives.
    let named = TagFilter::Exists("name".into());
    assert_eq!(
        pages(vec![cafe.clone(), named.clone()], None),
        vec![OsmId::Node(NodeId(9)), OsmId::Relation(RelationId(7))]
    );
    assert_eq!(
        pages(vec![named, cafe.clone()], None),
        vec![OsmId::Node(NodeId(9)), OsmId::Relation(RelationId(7))]
    );
    // Tag + bbox: node 5 is outside; way 3 and relation 7 reach into the box.
    let bbox = Bbox::new(1.5, 1.5, 2.5, 2.5).unwrap();
    assert_eq!(pages(vec![cafe.clone()], Some(bbox)), cafes[1..].to_vec());
    assert_eq!(pages(vec![], Some(bbox)), cafes[1..].to_vec());
    // A key or value absent from the area matches nothing, without error.
    assert!(pages(vec![TagFilter::Exists("shop".into())], None).is_empty());
    assert!(
        pages(
            vec![TagFilter::Equals("amenity".into(), "pub".into())],
            None
        )
        .is_empty()
    );
    // A missing value and an empty one are distinct.
    assert!(pages(vec![TagFilter::Equals("name".into(), "".into())], None).is_empty());
}

#[test]
fn xml_and_pbf_imports_produce_identical_objects() {
    // snapshot.osm.pbf is `osmium cat snapshot.osm -o snapshot.osm.pbf`
    // (osmium 1.19, dense nodes with metadata).
    let pbf = Path::new(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/tests/fixtures/snapshot.osm.pbf"
    ));
    for preserve in [false, true] {
        let (_x, xml) = import_with(fixture(), preserve);
        let (_p, binary) = import_with(pbf, preserve);
        assert_eq!(xml.counts().unwrap(), binary.counts().unwrap());
        let objects = everything(&xml);
        assert_eq!(objects.len(), 7);
        assert_eq!(objects, everything(&binary));
    }
}

#[test]
fn untagged_nodes_keep_only_their_version_by_default() {
    let (_directory, store) = import_with(fixture(), false);
    let Some(Object::Node(vertex)) = store.get(OsmId::Node(NodeId(1))).unwrap() else {
        panic!()
    };
    assert_eq!(vertex.metadata, None);
    assert_eq!(vertex.location_version, 3);
    assert!(vertex.tags.is_empty());
    // Tagged nodes, ways and relations always keep their metadata.
    let cafe = store.get(OsmId::Node(NodeId(2))).unwrap().unwrap();
    assert_eq!(cafe.metadata().unwrap().changeset, 124);
    let way = store.get(OsmId::Way(WayId(1))).unwrap().unwrap();
    assert_eq!(way.metadata().unwrap().version, 2);
    // Options from the OSMExpress era still parse; unknown fields are ignored.
    let legacy: ImportOptions =
        serde_json::from_str(r#"{"map_size":1073741824,"sort_pairs":3}"#).unwrap();
    assert!(!legacy.preserve_untagged_metadata);
}

#[cfg(unix)]
#[test]
fn import_into_unwritable_directory_keeps_the_old_area() {
    use std::os::unix::fs::PermissionsExt;
    let (directory, store) = import();
    drop(store);
    let area = directory.path().join("area.sqlite");
    let before = std::fs::read(&area).unwrap();
    let permissions = |mode| std::fs::Permissions::from_mode(mode);
    std::fs::set_permissions(directory.path(), permissions(0o555)).unwrap();
    let result = import_area(fixture(), &area, ImportOptions::default());
    std::fs::set_permissions(directory.path(), permissions(0o755)).unwrap();
    assert!(result.is_err());
    assert_eq!(std::fs::read(&area).unwrap(), before);
    assert_eq!(Store::open(&area).unwrap().counts().unwrap().nodes, 4);
}
