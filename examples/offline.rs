use osm_framework::*;

// Desktop harness around the same headless API intended for mobile bindings.
// Run `cargo run --example offline -- import input.osm.pbf area.osmx`, then
// `cargo run --example offline -- cafes area.osmx` with connectivity disabled.
fn main() -> std::result::Result<(), Box<dyn std::error::Error>> {
    let args: Vec<String> = std::env::args().collect();
    match args.get(1).map(String::as_str) {
        Some("import") if args.len() == 4 => {
            println!(
                "{:?}",
                import_area(&args[2], &args[3], ImportOptions::default())?
            );
        }
        Some("cafes") if args.len() == 3 => {
            let store = Store::open(&args[2])?;
            println!("{:?}", store.counts()?);
            let query = Query {
                tags: vec![TagFilter::Equals("amenity".into(), "cafe".into())],
                ..Query::default()
            };
            for cafe in store.query(&query)? {
                println!("{}", serde_json::to_string(&cafe)?);
            }
        }
        _ => return Err("usage: offline import INPUT AREA | offline cafes AREA".into()),
    }
    Ok(())
}
