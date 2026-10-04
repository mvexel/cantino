//! Way coordinates, batch get and the `NotExists` tag filter.
use cantino::*;

/// A small synthetic area with exact e7 coordinates, so expected points can
/// be written down as integers:
///
/// - way 1: closed square (nodes 1-4) with a 0.002° side at the equator
/// - way 2: open line along latitude 1 with uneven segments (0.001°, 0.002°)
/// - way 3: open line whose middle node (99) is outside the area
/// - way 4: closed ring with one vertex (98) outside the area
/// - way 5: every node outside the area
/// - relation 1: repeated and missing members for batch lookup coverage
const AREA: &str = r#"<osm version="0.6">
 <node id="1" lat="0" lon="0"/>
 <node id="2" lat="0" lon="0.002"/>
 <node id="3" lat="0.002" lon="0.002"/>
 <node id="4" lat="0.002" lon="0"/>
 <node id="5" lat="1" lon="0"/>
 <node id="6" lat="1" lon="0.001"/>
 <node id="7" lat="1" lon="0.003"/>
 <node id="8" lat="2" lon="0"/>
 <node id="9" lat="2" lon="0.002"><tag k="amenity" v="bench"/></node>
 <way id="1"><nd ref="1"/><nd ref="2"/><nd ref="3"/><nd ref="4"/><nd ref="1"/><tag k="building" v="yes"/><tag k="name" v="Square"/></way>
 <way id="2"><nd ref="5"/><nd ref="6"/><nd ref="7"/><tag k="highway" v="path"/></way>
 <way id="3"><nd ref="8"/><nd ref="99"/><nd ref="9"/><tag k="highway" v="path"/><tag k="name" v="Gap"/></way>
 <way id="4"><nd ref="1"/><nd ref="2"/><nd ref="98"/><nd ref="1"/><tag k="building" v="yes"/></way>
 <way id="5"><nd ref="97"/><nd ref="96"/><tag k="highway" v="path"/></way>
 <relation id="1"><member type="way" ref="1" role="outer"/><member type="node" ref="5" role="label"/><member type="way" ref="1" role="again"/><member type="node" ref="95" role="gone"/></relation>
</osm>"#;

fn area() -> (tempfile::TempDir, Store) {
    let directory = tempfile::tempdir().unwrap();
    let input = directory.path().join("geometry.osm");
    let destination = directory.path().join("area.sqlite");
    std::fs::write(&input, AREA).unwrap();
    import_area(&input, &destination, ImportOptions::default()).unwrap();
    let store = Store::open(&destination).unwrap();
    (directory, store)
}

fn point(lat_e7: i32, lon_e7: i32) -> Option<Coordinate> {
    Some(Coordinate { lat_e7, lon_e7 })
}

#[test]
fn way_coordinates_keep_order_repeats_and_gaps() {
    let (_directory, store) = area();
    let square = store.way_coordinates(WayId(1)).unwrap().unwrap();
    assert_eq!(square.len(), 5);
    assert_eq!(square[0], point(0, 0));
    assert_eq!(square[1], point(0, 20_000));
    assert_eq!(square[0], square[4]); // closing repeat kept
    let gap = store.way_coordinates(WayId(3)).unwrap().unwrap();
    assert_eq!(
        gap,
        vec![point(20_000_000, 0), None, point(20_000_000, 20_000)]
    );
    assert_eq!(
        store.way_coordinates(WayId(5)).unwrap().unwrap(),
        vec![None, None]
    );
    assert_eq!(store.way_coordinates(WayId(42)).unwrap(), None);
}

#[test]
fn batch_get_keeps_input_order_and_reports_missing() {
    let (_directory, store) = area();
    let ids = [
        OsmId::Way(WayId(2)),
        OsmId::Node(NodeId(99)),
        OsmId::Node(NodeId(1)),
        OsmId::Way(WayId(2)),
        OsmId::Relation(RelationId(1)),
    ];
    let objects = store.get_many(&ids).unwrap();
    assert_eq!(objects.len(), ids.len());
    assert_eq!(objects[1], None);
    for (id, object) in ids.iter().zip(&objects) {
        if let Some(object) = object {
            assert_eq!(object.id(), *id);
            assert_eq!(Some(object), store.get(*id).unwrap().as_ref());
        }
    }
    assert_eq!(objects[0], objects[3]);
    assert!(store.get_many(&[]).unwrap().is_empty());
    let too_many = vec![OsmId::Node(NodeId(1)); MAX_BATCH + 1];
    let error = store.get_many(&too_many).unwrap_err().to_string();
    assert!(error.contains("at most 10000"), "{error}");
    assert_eq!(
        store
            .get_many(&too_many[..MAX_BATCH])
            .unwrap()
            .iter()
            .flatten()
            .count(),
        MAX_BATCH
    );
    // One invalid ID fails the whole batch.
    assert!(
        store
            .get_many(&[OsmId::Node(NodeId(1)), OsmId::Way(WayId(-3))])
            .is_err()
    );
}

#[test]
fn not_exists_filters_candidates_but_never_drives() {
    let (_directory, store) = area();
    let ids = |tags: Vec<TagFilter>, bbox: Option<Bbox>| {
        store
            .query(&Query {
                tags,
                bbox,
                ..Query::default()
            })
            .map(|objects| objects.iter().map(Object::id).collect::<Vec<_>>())
    };
    let path = TagFilter::Equals("highway".into(), "path".into());
    let unnamed = TagFilter::NotExists("name".into());
    // Paths without a name: way 3 is named.
    assert_eq!(
        ids(vec![path.clone(), unnamed.clone()], None).unwrap(),
        vec![OsmId::Way(WayId(2)), OsmId::Way(WayId(5))]
    );
    // Filter order does not matter.
    assert_eq!(
        ids(vec![unnamed.clone(), path.clone()], None).unwrap(),
        vec![OsmId::Way(WayId(2)), OsmId::Way(WayId(5))]
    );
    // Exists driver too.
    assert_eq!(
        ids(
            vec![TagFilter::Exists("building".into()), unnamed.clone()],
            None
        )
        .unwrap(),
        vec![OsmId::Way(WayId(4))]
    );
    // A key that no object carries is absent everywhere: no restriction.
    assert_eq!(
        ids(
            vec![path.clone(), TagFilter::NotExists("no-such-key".into())],
            None
        )
        .unwrap()
        .len(),
        3
    );
    // NotExists alone has no driver and is rejected, not scanned.
    let error = ids(vec![unnamed.clone()], None).unwrap_err().to_string();
    assert!(error.contains("NotExists"), "{error}");
    assert!(
        ids(
            vec![unnamed.clone(), TagFilter::NotExists("highway".into())],
            None
        )
        .is_err()
    );
    // With a bbox the bbox drives: the bench (node 9) and the ways and
    // relations reaching latitude 2, minus the named way 3.
    let north = Bbox::new(-0.001, 1.9, 0.003, 2.1).unwrap();
    let found = ids(vec![unnamed], Some(north)).unwrap();
    assert!(found.contains(&OsmId::Node(NodeId(9))));
    assert!(!found.contains(&OsmId::Way(WayId(3))));
    // Wire form used by the C ABI and the Kotlin adapter.
    assert_eq!(
        serde_json::to_string(&TagFilter::NotExists("name".into())).unwrap(),
        r#"{"NotExists":"name"}"#
    );
}
