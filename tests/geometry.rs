//! Geometry helpers and 0.2 read additions: way coordinates, representative
//! points, batch get and the `NotExists` tag filter.
use cantino::*;

/// A small synthetic area with exact e7 coordinates, so expected points can
/// be written down as integers:
///
/// - way 1: closed square (nodes 1-4) with a 0.002° side at the equator
/// - way 2: open line along latitude 1 with uneven segments (0.001°, 0.002°)
/// - way 3: open line whose middle node (99) is outside the area
/// - way 4: closed ring with one vertex (98) outside the area
/// - way 5: every node outside the area
/// - relations 1-7: plain, nested, a two-relation cycle, a self-cycle, an
///   empty one and one whose members are all missing
/// - relations 10-19: a chain ten relations deep ending in node 1
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
 <relation id="2"><member type="relation" ref="1" role=""/><member type="node" ref="8" role=""/></relation>
 <relation id="3"><member type="relation" ref="4" role=""/><member type="node" ref="1" role=""/></relation>
 <relation id="4"><member type="relation" ref="3" role=""/><member type="node" ref="9" role=""/></relation>
 <relation id="5"><member type="relation" ref="5" role="self"/></relation>
 <relation id="6"><tag k="type" v="empty"/></relation>
 <relation id="7"><member type="way" ref="999" role=""/><member type="node" ref="94" role=""/></relation>
 <relation id="10"><member type="relation" ref="11" role=""/></relation>
 <relation id="11"><member type="relation" ref="12" role=""/></relation>
 <relation id="12"><member type="relation" ref="13" role=""/></relation>
 <relation id="13"><member type="relation" ref="14" role=""/></relation>
 <relation id="14"><member type="relation" ref="15" role=""/></relation>
 <relation id="15"><member type="relation" ref="16" role=""/></relation>
 <relation id="16"><member type="relation" ref="17" role=""/></relation>
 <relation id="17"><member type="relation" ref="18" role=""/></relation>
 <relation id="18"><member type="relation" ref="19" role=""/></relation>
 <relation id="19"><member type="node" ref="1" role=""/></relation>
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

fn rep(store: &Store, id: OsmId) -> Option<Coordinate> {
    store.representative_point(id).unwrap()
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
fn representative_point_of_nodes_and_ways() {
    let (_directory, store) = area();
    // Node: its coordinate.
    assert_eq!(rep(&store, OsmId::Node(NodeId(2))), point(0, 20_000));
    // Closed way: mean of the four distinct corners (closing node once).
    assert_eq!(rep(&store, OsmId::Way(WayId(1))), point(10_000, 10_000));
    // Open way: half of 0.003° of length lies 0.0005° into the second
    // segment, i.e. at longitude 0.0015 (not the vertex mean 0.00133).
    assert_eq!(rep(&store, OsmId::Way(WayId(2))), point(10_000_000, 15_000));
    // Open way partly outside: the missing middle node is skipped and the
    // polyline joins its neighbours.
    assert_eq!(rep(&store, OsmId::Way(WayId(3))), point(20_000_000, 10_000));
    // Closed way partly outside: mean of the in-area distinct vertices 1, 2.
    assert_eq!(rep(&store, OsmId::Way(WayId(4))), point(0, 10_000));
    // Nothing in the area: no point.
    assert_eq!(rep(&store, OsmId::Way(WayId(5))), None);
    assert_eq!(rep(&store, OsmId::Node(NodeId(99))), None);
    assert_eq!(rep(&store, OsmId::Way(WayId(42))), None);
    assert!(store.representative_point(OsmId::Node(NodeId(0))).is_err());
}

#[test]
fn representative_point_of_relations() {
    let (_directory, store) = area();
    // Way 1 (counted once though listed twice) and node 5; node 95 is
    // missing and ignored: mean of (0.001, 0.001) and (1, 0).
    assert_eq!(
        rep(&store, OsmId::Relation(RelationId(1))),
        point(5_005_000, 5_000)
    );
    // Nested: relation 1's point and node 8, equally weighted.
    assert_eq!(
        rep(&store, OsmId::Relation(RelationId(2))),
        point(12_502_500, 2_500)
    );
    // Cycle 3 -> 4 -> 3: inside relation 4, relation 3 is already being
    // resolved and contributes nothing, so 4 resolves to node 9 and 3 to the
    // mean of node 9 and node 1.
    assert_eq!(
        rep(&store, OsmId::Relation(RelationId(3))),
        point(10_000_000, 10_000)
    );
    assert_eq!(
        rep(&store, OsmId::Relation(RelationId(4))),
        point(10_000_000, 10_000)
    );
    // Self-cycle, empty relation, all members missing: no point.
    assert_eq!(rep(&store, OsmId::Relation(RelationId(5))), None);
    assert_eq!(rep(&store, OsmId::Relation(RelationId(6))), None);
    assert_eq!(rep(&store, OsmId::Relation(RelationId(7))), None);
    assert_eq!(rep(&store, OsmId::Relation(RelationId(404))), None);
    // Depth limit: from relation 11 the chain reaches node 1 at the deepest
    // followed level; from relation 10 the last relation (19) lies one level
    // too deep, so nothing is found.
    assert_eq!(MAX_RELATION_DEPTH, 8);
    assert_eq!(rep(&store, OsmId::Relation(RelationId(11))), point(0, 0));
    assert_eq!(rep(&store, OsmId::Relation(RelationId(10))), None);
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
