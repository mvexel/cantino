//! Basemap extract: the sans-IO engine driven against local archives.
//!
//! * Synthetic archives (built here, with leaf directories two levels deep,
//!   run-length entries and deduplicated tiles) check the extract against an
//!   independent tile filter.
//! * `tests/fixtures/basemap/slc-nw-z12-15.pmtiles` (48 KB, made with
//!   go-pmtiles 1.31.2 from a Protomaps build) checks real-world data.
//! * The golden test compares with the go-pmtiles CLI on `work/basemap/` and
//!   skips (with a message) when that untracked directory is absent.

use std::collections::{BTreeMap, HashMap, VecDeque};
use std::path::{Path, PathBuf};
use std::process::Command;

use cantino::basemap::format::{
    Compression, Entry, HEADER_LEN, Header, decode_directory, encode_directory,
};
use cantino::basemap::tile_id::{id_to_zxy, zxy_to_id};
use cantino::basemap::{BBox, ByteRange, ExtractPlan, Step, TilePlan};

// ---------------------------------------------------------------- helpers --

/// What the platform does: answer a range request from the "server".
fn serve(src: &[u8], r: ByteRange) -> &[u8] {
    let start = (r.offset as usize).min(src.len());
    let end = ((r.offset + r.length) as usize).min(src.len());
    &src[start..end]
}

/// Runs the whole extract against an in-memory source, feeding responses in
/// LIFO order when `shuffle` (so batches arrive out of order), and returns
/// the tile plan plus the number of requests made.
fn drive(
    src: &[u8],
    bbox: BBox,
    zooms: (Option<u8>, Option<u8>),
    overfetch: f32,
    out: &Path,
    shuffle: bool,
) -> (TilePlan, usize) {
    let mut plan = ExtractPlan::new(bbox, zooms.0, zooms.1, overfetch).unwrap();
    let mut queue = VecDeque::from([plan.first_request()]);
    let mut requests = 0;
    let tiles = loop {
        let r = if shuffle {
            queue.pop_back()
        } else {
            queue.pop_front()
        }
        .expect("a request is outstanding until TilesReady");
        requests += 1;
        match plan.feed(r.id, serve(src, r)).unwrap() {
            Step::Fetch(more) => queue.extend(more),
            Step::Wait => assert!(!queue.is_empty()),
            Step::TilesReady(t) => break t,
        }
    };
    assert!(queue.is_empty() && plan.outstanding().is_empty());
    let mut asm = plan.into_assembler(out.with_extension("part")).unwrap();
    let mut todo = asm.remaining();
    assert_eq!(todo, tiles.requests);
    if shuffle {
        todo.reverse();
    }
    for r in todo {
        // Streams through write_range_from.
        asm.write_range(r.id, serve(src, r)).unwrap();
    }
    let p = asm.progress();
    assert_eq!(
        (p.ranges_done, p.bytes_done),
        (p.ranges_total, tiles.transfer_bytes)
    );
    asm.finish(out).unwrap();
    assert!(!out.with_extension("part").exists());
    let total = requests + tiles.requests.len();
    (tiles, total)
}

struct Archive {
    header: Header,
    /// Tile id -> bytes, run lengths expanded.
    tiles: BTreeMap<u64, Vec<u8>>,
    /// All tile entries in id order (root and leaves flattened).
    entries: Vec<Entry>,
    metadata: Vec<u8>,
}

/// A strict reader: walks every directory and checks the invariants a
/// PMTiles reader relies on.
fn read_archive(b: &[u8]) -> Archive {
    let header = Header::parse(b).unwrap();
    assert_eq!(
        header.tile_data_offset + header.tile_data_length,
        b.len() as u64,
        "file ends with tile data"
    );
    fn walk(b: &[u8], h: &Header, off: u64, len: u64, out: &mut Vec<Entry>) {
        let dir = decode_directory(
            &b[off as usize..(off + len) as usize],
            h.internal_compression,
        )
        .unwrap();
        for e in dir {
            if e.run_length == 0 {
                assert!(e.offset + u64::from(e.length) <= h.leaf_directory_length);
                walk(
                    b,
                    h,
                    h.leaf_directory_offset + e.offset,
                    u64::from(e.length),
                    out,
                );
            } else {
                out.push(e);
            }
        }
    }
    let mut entries = Vec::new();
    walk(
        b,
        &header,
        header.root_offset,
        header.root_length,
        &mut entries,
    );
    assert!(
        entries
            .windows(2)
            .all(|w| w[0].tile_id + u64::from(w[0].run_length) <= w[1].tile_id)
    );
    let mut tiles = BTreeMap::new();
    for e in &entries {
        assert!(e.offset + u64::from(e.length) <= header.tile_data_length);
        let at = (header.tile_data_offset + e.offset) as usize;
        let data = b[at..at + e.length as usize].to_vec();
        for id in e.tile_id..e.tile_id + u64::from(e.run_length) {
            tiles.insert(id, data.clone());
        }
    }
    assert_eq!(header.tile_entries_count, entries.len() as u64);
    assert_eq!(header.addressed_tiles_count, tiles.len() as u64);
    let m = header.metadata_offset as usize;
    let metadata = b[m..m + header.metadata_length as usize].to_vec();
    Archive {
        header,
        tiles,
        entries,
        metadata,
    }
}

/// The extract's tile filter, written independently of the crate's
/// Hilbert/quadtree code: floor of web-mercator tile fractions at max zoom,
/// shifted down to each lower zoom.
fn in_cover(bbox: BBox, min_z: u8, max_z: u8, id: u64) -> bool {
    let (z, x, y) = id_to_zxy(id);
    if z < min_z || z > max_z {
        return false;
    }
    let n = f64::from(1u32 << max_z);
    let col = |lon: f64| (((lon / 360.0 + 0.5) * n).floor().clamp(0.0, n - 1.0)) as u32;
    let row = |lat: f64| {
        let r = lat.to_radians();
        let f = (1.0 - (r.tan() + 1.0 / r.cos()).ln() / std::f64::consts::PI) / 2.0;
        ((f * n).floor().clamp(0.0, n - 1.0)) as u32
    };
    let s = max_z - z;
    let (x0, x1) = (col(bbox.west) >> s, col(bbox.east) >> s);
    let (y0, y1) = (row(bbox.north) >> s, row(bbox.south) >> s);
    (y0..=y1).contains(&y) && (x0..=x1).contains(&x)
}

fn tmp() -> tempfile::TempDir {
    tempfile::tempdir().unwrap()
}

// ------------------------------------------------------ synthetic archives --

/// Builds a clustered archive from `(tile id, bytes)` in id order: identical
/// bytes are stored once, consecutive identical tiles become runs. `levels`
/// = 0 (root only), 1 (root -> leaves) or 2 (root -> mid -> leaves).
fn write_archive(tiles: &[(u64, Vec<u8>)], c: Compression, levels: u8, clustered: bool) -> Vec<u8> {
    let mut data = Vec::new();
    let mut at: HashMap<&[u8], u64> = HashMap::new();
    let mut entries: Vec<Entry> = Vec::new();
    for (id, bytes) in tiles {
        if let Some(last) = entries.last_mut()
            && last.tile_id + u64::from(last.run_length) == *id
            && at.get(bytes.as_slice()) == Some(&last.offset)
        {
            last.run_length += 1;
            continue;
        }
        let offset = *at.entry(bytes).or_insert_with(|| {
            data.extend_from_slice(bytes);
            (data.len() - bytes.len()) as u64
        });
        entries.push(Entry {
            tile_id: *id,
            offset,
            length: bytes.len() as u32,
            run_length: 1,
        });
    }
    let mut leaf_section = Vec::new();
    let pointers = |dirs: &[Entry], size: usize, leaf_section: &mut Vec<u8>| -> Vec<Entry> {
        dirs.chunks(size)
            .map(|chunk| {
                let bytes = encode_directory(chunk, c).unwrap();
                let e = Entry {
                    tile_id: chunk[0].tile_id,
                    offset: leaf_section.len() as u64,
                    length: bytes.len() as u32,
                    run_length: 0,
                };
                leaf_section.extend_from_slice(&bytes);
                e
            })
            .collect()
    };
    let root_entries = match levels {
        0 => entries.clone(),
        1 => pointers(&entries, 500, &mut leaf_section),
        _ => {
            let leaves = pointers(&entries, 300, &mut leaf_section);
            pointers(&leaves, 7, &mut leaf_section)
        }
    };
    let root = encode_directory(&root_entries, c).unwrap();
    let metadata = {
        let json = br#"{"name":"synthetic","vector_layers":[]}"#;
        match c {
            Compression::Gzip => {
                use std::io::Write;
                let mut e =
                    flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::default());
                e.write_all(json).unwrap();
                e.finish().unwrap()
            }
            _ => json.to_vec(),
        }
    };
    let max_zoom = tiles.iter().map(|t| id_to_zxy(t.0).0).max().unwrap();
    let h = Header {
        spec_version: 3,
        root_offset: HEADER_LEN as u64,
        root_length: root.len() as u64,
        metadata_offset: (HEADER_LEN + root.len()) as u64,
        metadata_length: metadata.len() as u64,
        leaf_directory_offset: (HEADER_LEN + root.len() + metadata.len()) as u64,
        leaf_directory_length: leaf_section.len() as u64,
        tile_data_offset: (HEADER_LEN + root.len() + metadata.len() + leaf_section.len()) as u64,
        tile_data_length: data.len() as u64,
        addressed_tiles_count: tiles.len() as u64,
        tile_entries_count: entries.len() as u64,
        tile_contents_count: at.len() as u64,
        clustered,
        internal_compression: c,
        tile_compression: Compression::None,
        tile_type: 1,
        min_zoom: 0,
        max_zoom,
        min_lon_e7: -1_800_000_000,
        min_lat_e7: -850_000_000,
        max_lon_e7: 1_800_000_000,
        max_lat_e7: 850_000_000,
        center_zoom: 3,
        center_lon_e7: 0,
        center_lat_e7: 0,
    };
    let mut out = h.to_bytes().to_vec();
    out.extend_from_slice(&root);
    out.extend_from_slice(&metadata);
    out.extend_from_slice(&leaf_section);
    out.extend_from_slice(&data);
    out
}

/// Every tile of zooms 0..=max_zoom, except some missing ones at the top
/// zoom; a band of "ocean" tiles share one content (dedup + runs).
fn world_tiles(max_zoom: u8) -> Vec<(u64, Vec<u8>)> {
    let mut v = Vec::new();
    for z in 0..=max_zoom {
        for x in 0..(1u32 << z) {
            for y in 0..(1u32 << z) {
                if z == max_zoom && x % 7 == 3 {
                    continue;
                }
                let bytes = if z >= 4 && (y * 4) >> z == 2 {
                    b"ocean".to_vec()
                } else {
                    format!("tile {z}/{x}/{y}").into_bytes()
                };
                v.push((zxy_to_id(z, x, y), bytes));
            }
        }
    }
    v.sort();
    v
}

fn check_against_filter(src: &[u8], bbox: BBox, min_z: u8, max_z: u8, out: &Path) {
    let source = read_archive(src);
    let got = read_archive(&std::fs::read(out).unwrap());
    let expected: BTreeMap<u64, Vec<u8>> = source
        .tiles
        .into_iter()
        .filter(|(id, _)| in_cover(bbox, min_z, max_z, *id))
        .collect();
    assert!(!expected.is_empty());
    assert_eq!(got.tiles.len(), expected.len());
    assert!(
        got.tiles == expected,
        "extracted tiles differ from the filtered source"
    );
    let h = &got.header;
    // Our output keeps header + root within the first fetch.
    assert_eq!(h.root_offset, HEADER_LEN as u64);
    assert!(h.root_offset + h.root_length <= 16_384);
    assert_eq!((h.min_zoom, h.max_zoom), (min_z, max_z));
    assert_eq!(h.min_lon_e7, (bbox.west * 1e7) as i32);
    assert_eq!(h.max_lat_e7, (bbox.north * 1e7) as i32);
    assert!(h.clustered);
    assert_eq!(got.metadata, source.metadata);
    // Deduplicated: one stored copy per distinct content.
    let distinct: std::collections::BTreeSet<&Vec<u8>> = got.tiles.values().collect();
    assert_eq!(h.tile_contents_count, distinct.len() as u64);
}

#[test]
fn synthetic_archives_all_directory_shapes() {
    let tiles = world_tiles(8);
    let bbox = BBox::new(-30.0, -20.0, 25.0, 35.0);
    for (levels, c) in [
        (0, Compression::Gzip),
        (1, Compression::Gzip),
        (2, Compression::None),
    ] {
        let src = write_archive(&tiles, c, levels, true);
        for (zooms, overfetch, shuffle) in [
            ((None, None), 0.05, false),
            ((Some(3), Some(7)), 0.0, true),
            ((None, None), 2.0, true),
        ] {
            let dir = tmp();
            let out = dir.path().join("out.pmtiles");
            let (plan, _) = drive(&src, bbox, zooms, overfetch, &out, shuffle);
            let (lo, hi) = (zooms.0.unwrap_or(0), zooms.1.unwrap_or(8));
            assert_eq!((plan.min_zoom, plan.max_zoom), (lo, hi));
            check_against_filter(&src, bbox, lo, hi, &out);
            if overfetch == 0.0 {
                assert_eq!(plan.transfer_bytes, plan.tile_data_bytes);
            }
        }
    }
}

#[test]
fn bigger_extract_gets_leaf_directories_and_overfetch_saves_requests() {
    // ~50k tiles in the cover: the output root cannot hold them all.
    let tiles = world_tiles(9);
    let src = write_archive(&tiles, Compression::Gzip, 1, true);
    let bbox = BBox::new(-179.0, -84.0, 179.0, 84.0);
    let dir = tmp();
    let a = dir.path().join("a.pmtiles");
    let (strict, strict_requests) = drive(&src, bbox, (None, None), 0.0, &a, false);
    check_against_filter(&src, bbox, 0, 9, &a);
    let got = read_archive(&std::fs::read(&a).unwrap());
    assert!(
        got.header.leaf_directory_length > 0,
        "expected leaf directories"
    );
    let b = dir.path().join("b.pmtiles");
    let (loose, loose_requests) = drive(&src, bbox, (None, None), 0.5, &b, true);
    assert!(loose_requests < strict_requests);
    assert!(loose.transfer_bytes >= strict.transfer_bytes);
    assert_eq!(read_archive(&std::fs::read(&b).unwrap()).tiles, got.tiles);
}

#[test]
fn antimeridian_bbox() {
    let tiles = world_tiles(6);
    let src = write_archive(&tiles, Compression::Gzip, 1, true);
    let bbox = BBox::new(170.0, -10.0, -170.0, 10.0);
    let dir = tmp();
    let out = dir.path().join("out.pmtiles");
    drive(&src, bbox, (None, None), 0.05, &out, false);
    let got = read_archive(&std::fs::read(&out).unwrap());
    let cols: std::collections::BTreeSet<u32> = got
        .tiles
        .keys()
        .map(|&id| id_to_zxy(id))
        .filter(|t| t.0 == 6)
        .map(|t| t.1)
        .collect();
    assert_eq!(cols.iter().copied().collect::<Vec<_>>(), [0, 1, 62, 63]);
    assert_eq!(
        (got.header.min_lon_e7, got.header.max_lon_e7),
        (-1_800_000_000, 1_800_000_000)
    );
    assert_eq!(got.header.center_lon_e7, 1_800_000_000);
}

// ------------------------------------------------------- failure behaviour --

#[test]
fn bad_responses_are_rejected_and_retryable() {
    let src = write_archive(&world_tiles(6), Compression::Gzip, 1, true);
    let bbox = BBox::new(-10.0, -10.0, 10.0, 10.0);
    let mut plan = ExtractPlan::new(bbox, None, None, 0.05).unwrap();
    let first = plan.first_request();
    assert!(plan.feed(99, serve(&src, first)).is_err(), "unknown id");
    assert!(plan.feed(first.id, &src[..50]).is_err(), "short header");
    let Step::Fetch(leaves) = plan.feed(first.id, serve(&src, first)).unwrap() else {
        panic!("expected leaf requests")
    };
    assert!(
        plan.feed(first.id, serve(&src, first)).is_err(),
        "fed twice"
    );
    let leaf = leaves[0];
    let bytes = serve(&src, leaf);
    assert!(plan.feed(leaf.id, &bytes[1..]).is_err(), "truncated leaf");
    let mut step = plan.feed(leaf.id, bytes).unwrap();
    for r in &leaves[1..] {
        step = plan.feed(r.id, serve(&src, *r)).unwrap();
    }
    let Step::TilesReady(tiles) = step else {
        panic!("expected tiles")
    };
    assert!(plan.feed(leaf.id, bytes).is_err(), "plan is finalized");

    let dir = tmp();
    let staging = dir.path().join("x.part");
    let out = dir.path().join("x.pmtiles");
    std::fs::write(&out, b"previous area").unwrap();
    let mut asm = plan.into_assembler(&staging).unwrap();
    let r = tiles.requests[0];
    let data = serve(&src, r);
    assert!(asm.write_range(r.id + 1000, data).is_err(), "unknown id");
    assert!(
        asm.write_range_from(r.id, &data[..data.len() - 1]).is_err(),
        "short body"
    );
    let mut too_long = data.to_vec();
    too_long.push(0);
    assert!(
        asm.write_range_from(r.id, too_long.as_slice()).is_err(),
        "Range ignored"
    );
    // Nothing finished yet: finish refuses, the old output is untouched, and
    // the staging file has no valid header.
    assert!(asm.finish(&out).is_err());
    assert_eq!(std::fs::read(&out).unwrap(), b"previous area");
    assert!(Header::parse(&std::fs::read(&staging).unwrap()).is_err());
    for r in asm.remaining() {
        asm.write_range(r.id, serve(&src, r)).unwrap();
    }
    asm.finish(&out).unwrap();
    assert!(asm.finish(&out).is_err());
    assert!(!staging.exists());
    read_archive(&std::fs::read(&out).unwrap());
}

#[test]
fn dropped_assembler_removes_staging() {
    let src = write_archive(&world_tiles(5), Compression::Gzip, 0, true);
    let mut plan = ExtractPlan::new(BBox::new(0.0, 0.0, 1.0, 1.0), None, None, 0.05).unwrap();
    let Step::TilesReady(_) = plan.feed(0, serve(&src, plan.first_request())).unwrap() else {
        panic!("root-only archive needs one request")
    };
    let dir = tmp();
    let staging = dir.path().join("y.part");
    let asm = plan.into_assembler(&staging).unwrap();
    assert!(staging.exists());
    drop(asm);
    assert!(!staging.exists());
}

#[test]
fn unsupported_sources_fail_clearly() {
    let tiles = world_tiles(4);
    let bbox = BBox::new(0.0, 0.0, 1.0, 1.0);
    let unclustered = write_archive(&tiles, Compression::Gzip, 0, false);
    let mut plan = ExtractPlan::new(bbox, None, None, 0.05).unwrap();
    let err = plan
        .feed(0, serve(&unclustered, plan.first_request()))
        .unwrap_err();
    assert!(err.to_string().contains("clustered"), "{err}");

    let mut brotli = write_archive(&tiles, Compression::Gzip, 0, true);
    brotli[97] = 3;
    let mut plan = ExtractPlan::new(bbox, None, None, 0.05).unwrap();
    let err = plan
        .feed(0, serve(&brotli, plan.first_request()))
        .unwrap_err();
    assert!(err.to_string().contains("internal compression"), "{err}");

    let mut plan = ExtractPlan::new(bbox, None, None, 0.05).unwrap();
    assert!(
        plan.feed(0, b"not a pmtiles archive at all, but long enough? no")
            .is_err()
    );
    assert!(ExtractPlan::new(bbox, Some(5), Some(3), 0.0).is_err());
    assert!(ExtractPlan::new(bbox, None, None, -1.0).is_err());
    // Requested zooms outside the archive's range.
    let src = write_archive(&tiles, Compression::Gzip, 0, true);
    let mut plan = ExtractPlan::new(bbox, Some(10), None, 0.0).unwrap();
    assert!(plan.feed(0, serve(&src, plan.first_request())).is_err());
}

#[test]
fn steps_serialize_as_json() {
    let r = ByteRange {
        id: 3,
        offset: 16384,
        length: 512,
    };
    let json = serde_json::to_string(&Step::Fetch(vec![r])).unwrap();
    assert_eq!(json, r#"{"fetch":[{"id":3,"offset":16384,"length":512}]}"#);
    assert_eq!(serde_json::to_string(&Step::Wait).unwrap(), r#""wait""#);
}

// -------------------------------------------------- real Protomaps data --

fn root() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
}

#[test]
fn committed_protomaps_fixture() {
    let src = std::fs::read(root().join("tests/fixtures/basemap/slc-nw-z12-15.pmtiles")).unwrap();
    let source = read_archive(&src);
    assert_eq!(source.tiles.len(), 22);
    let dir = tmp();
    // Whole fixture: identical tiles, directories and metadata.
    let all = BBox::new(-112.09, 40.83, -112.06, 40.845);
    let out = dir.path().join("all.pmtiles");
    drive(&src, all, (None, None), 0.05, &out, false);
    let got = read_archive(&std::fs::read(&out).unwrap());
    assert_eq!(got.tiles, source.tiles);
    assert_eq!(got.entries, source.entries);
    // Same header except the root's length (gzip encoders differ) and the
    // offsets that follow from it.
    let layout = |h: &Header| Header {
        root_length: 0,
        metadata_offset: 0,
        leaf_directory_offset: 0,
        tile_data_offset: 0,
        ..h.clone()
    };
    assert_eq!(layout(&got.header), layout(&source.header));
    // A sub-bbox at fewer zooms.
    let sub = BBox::new(-112.085, 40.835, -112.07, 40.842);
    let out = dir.path().join("sub.pmtiles");
    drive(&src, sub, (Some(13), Some(15)), 0.05, &out, true);
    check_against_filter(&src, sub, 13, 15, &out);
}

/// go-pmtiles 1.31.2 vs this engine on the 12 MB Salt Lake City build.
#[test]
fn golden_against_go_pmtiles() {
    let source = root().join("work/basemap/slc.pmtiles");
    let cli = root().join("work/basemap/pmtiles");
    if !source.exists() || !cli.exists() {
        eprintln!(
            "skipping golden_against_go_pmtiles: {} or {} missing (work/ is untracked)",
            source.display(),
            cli.display()
        );
        return;
    }
    let src = std::fs::read(&source).unwrap();
    let dir = tmp();
    let theirs = dir.path().join("go.pmtiles");
    let ours = dir.path().join("rust.pmtiles");
    let status = Command::new(&cli)
        .arg("extract")
        .arg(&source)
        .arg(&theirs)
        .arg("--bbox=-111.91,40.75,-111.87,40.78")
        .arg("--maxzoom=15")
        .output()
        .unwrap();
    assert!(
        status.status.success(),
        "{}",
        String::from_utf8_lossy(&status.stderr)
    );
    let bbox = BBox::new(-111.91, 40.75, -111.87, 40.78);
    let (plan, requests) = drive(&src, bbox, (None, Some(15)), 0.05, &ours, true);
    let go = read_archive(&std::fs::read(&theirs).unwrap());
    let rs = read_archive(&std::fs::read(&ours).unwrap());
    eprintln!(
        "golden: {} tiles, {} requests, {} bytes transferred, go file {} B, ours {} B",
        rs.tiles.len(),
        requests,
        plan.transfer_bytes + plan.directory_bytes,
        std::fs::metadata(&theirs).unwrap().len(),
        std::fs::metadata(&ours).unwrap().len()
    );
    assert_eq!(rs.tiles.len(), go.tiles.len());
    assert!(
        rs.tiles == go.tiles,
        "tile sets or bytes differ from go-pmtiles"
    );
    assert_eq!(rs.entries, go.entries, "directory entries differ");
    assert_eq!(rs.metadata, go.metadata);
    let (h, g) = (&rs.header, &go.header);
    assert_eq!(
        (
            h.min_zoom,
            h.max_zoom,
            h.center_zoom,
            h.min_lon_e7,
            h.min_lat_e7,
            h.max_lon_e7
        ),
        (
            g.min_zoom,
            g.max_zoom,
            g.center_zoom,
            g.min_lon_e7,
            g.min_lat_e7,
            g.max_lon_e7
        )
    );
    assert_eq!(
        (
            h.max_lat_e7,
            h.center_lon_e7,
            h.center_lat_e7,
            h.tile_contents_count
        ),
        (
            g.max_lat_e7,
            g.center_lon_e7,
            g.center_lat_e7,
            g.tile_contents_count
        )
    );
    for cmd in ["verify", "show"] {
        let out = Command::new(&cli).arg(cmd).arg(&ours).output().unwrap();
        assert!(
            out.status.success(),
            "pmtiles {cmd} rejected our archive: {}",
            String::from_utf8_lossy(&out.stderr)
        );
    }
}
