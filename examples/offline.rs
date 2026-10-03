use cantino::*;

// Desktop harness around the same headless API intended for mobile bindings.
// Run `cargo run --release --example offline -- import input.osm.pbf area.sqlite`,
// then `cargo run --release --example offline -- cafes area.sqlite` with
// connectivity disabled. `offline get area.sqlite node 1` prints one raw object.
fn main() -> std::result::Result<(), Box<dyn std::error::Error>> {
    let args: Vec<String> = std::env::args().collect();
    match args.get(1).map(String::as_str) {
        Some("import") if args.len() == 4 => {
            let start = std::time::Instant::now();
            let report = import_area(&args[2], &args[3], ImportOptions::default())?;
            println!("{report:?} in {:.2} s", start.elapsed().as_secs_f64());
        }
        Some("cafes") if args.len() == 3 => {
            let store = Store::open(&args[2])?;
            println!("{:?}", store.counts()?);
            // Keyset pagination: pass the last result as the next cursor.
            let mut query = Query {
                tags: vec![TagFilter::Equals("amenity".into(), "cafe".into())],
                ..Query::default()
            };
            // Time the queries alone; printing comes afterwards. A second
            // pass shows warm-cache latency.
            let mut cafes = vec![];
            for pass in ["cold", "warm"] {
                cafes.clear();
                query.after = None;
                let start = std::time::Instant::now();
                loop {
                    let page = store.query(&query)?;
                    let Some(last) = page.last() else { break };
                    query.after = Some(last.id());
                    cafes.extend(page);
                }
                eprintln!(
                    "{} cafés in {:.2} ms ({pass})",
                    cafes.len(),
                    start.elapsed().as_secs_f64() * 1e3
                );
            }
            for cafe in &cafes {
                println!("{}", serde_json::to_string(cafe)?);
            }
        }
        Some("get") if args.len() == 5 => {
            let id: i64 = args[4].parse()?;
            let id = match args[3].as_str() {
                "node" => OsmId::Node(NodeId(id)),
                "way" => OsmId::Way(WayId(id)),
                "relation" => OsmId::Relation(RelationId(id)),
                _ => return Err("kind must be node, way or relation".into()),
            };
            let store = Store::open(&args[2])?;
            match store.get(id)? {
                Some(object) => println!("{}", serde_json::to_string(&object)?),
                None => println!("not found"),
            }
        }
        _ => {
            return Err(
                "usage: offline import INPUT AREA | offline cafes AREA | offline get AREA KIND ID"
                    .into(),
            );
        }
    }
    Ok(())
}
