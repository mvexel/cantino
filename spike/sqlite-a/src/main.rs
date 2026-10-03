//! Variant A: normalized, interned SQLite store for raw OSM data.
use osmpbf::{Element, ElementReader, RelMemberType};
use rusqlite::{params, Connection, OptionalExtension, Statement};
use serde_json::{json, Map, Value};
use std::collections::HashMap;
use std::time::Instant;

const NODE: i64 = 0;
const WAY: i64 = 1;
const REL: i64 = 2;

// Downtown box (degrees) and its 1e-7 integer form.
const BOX: (f64, f64, f64, f64) = (-111.897, 40.760, -111.885, 40.769); // w s e n

fn e7(v: f64) -> i64 {
    (v * 1e7).round() as i64
}

fn page_size() -> i64 {
    std::env::var("PAGE_SIZE").ok().and_then(|v| v.parse().ok()).unwrap_or(4096)
}
fn env_flag(name: &str, default: bool) -> bool {
    std::env::var(name).map(|v| v == "1").unwrap_or(default)
}

const SCHEMA: &str = "
CREATE TABLE strings(id INTEGER PRIMARY KEY, s TEXT NOT NULL);
CREATE TABLE nodes(id INTEGER PRIMARY KEY, lat INTEGER NOT NULL, lon INTEGER NOT NULL, version INTEGER NOT NULL);
CREATE TABLE node_meta(id INTEGER PRIMARY KEY, ts INTEGER, changeset INTEGER, uid INTEGER, user INTEGER);
CREATE TABLE ways(id INTEGER PRIMARY KEY, version INTEGER, ts INTEGER, changeset INTEGER, uid INTEGER, user INTEGER);
CREATE TABLE way_nodes(way_id INTEGER NOT NULL, seq INTEGER NOT NULL, node_id INTEGER NOT NULL, PRIMARY KEY(way_id, seq)) WITHOUT ROWID;
CREATE TABLE relations(id INTEGER PRIMARY KEY, version INTEGER, ts INTEGER, changeset INTEGER, uid INTEGER, user INTEGER);
CREATE TABLE members(rel_id INTEGER NOT NULL, seq INTEGER NOT NULL, kind INTEGER NOT NULL, ref INTEGER NOT NULL, role INTEGER NOT NULL, PRIMARY KEY(rel_id, seq)) WITHOUT ROWID;
CREATE TABLE tags(kind INTEGER NOT NULL, id INTEGER NOT NULL, key INTEGER NOT NULL, value INTEGER NOT NULL, PRIMARY KEY(kind, id, key)) WITHOUT ROWID;
-- rtree id = osm_id*2 + kind (0 node, 1 way); 1e-7 integer coords, exact.
CREATE VIRTUAL TABLE spatial USING rtree_i32(id, min_lon, max_lon, min_lat, max_lat);
";

const INDEXES: &str = "
CREATE UNIQUE INDEX strings_s ON strings(s);
CREATE INDEX way_nodes_node ON way_nodes(node_id, way_id);
CREATE INDEX members_ref ON members(kind, ref, rel_id);
CREATE INDEX tags_kv ON tags(key, value, kind, id);
";

struct Interner<'c> {
    map: HashMap<String, i64>,
    stmt: Statement<'c>,
}
impl Interner<'_> {
    fn get(&mut self, s: &str) -> i64 {
        if let Some(&id) = self.map.get(s) {
            return id;
        }
        let id = self.map.len() as i64 + 1;
        self.stmt.execute(params![id, s]).unwrap();
        self.map.insert(s.to_owned(), id);
        id
    }
}

#[derive(Default)]
struct ImportStats {
    dup_tags: u64,
    missing_way_nodes: u64,
    get_way: (i64, Vec<i64>), // way with most refs (lowest id on ties)
    repeat_way: Option<(i64, Vec<i64>)>, // first way with a repeated ref
    way_hash: u64,
    way_refs: u64,
}

fn seq_hash(refs: impl Iterator<Item = i64>) -> u64 {
    let mut h: u64 = 1469598103934665603;
    for r in refs {
        h = (h ^ r as u64).wrapping_mul(1099511628211);
    }
    h
}

fn import(pbf: &str, db: &str) -> ImportStats {
    for suffix in ["", "-journal", "-wal", "-shm"] {
        let _ = std::fs::remove_file(format!("{db}{suffix}"));
    }
    let conn = Connection::open(db).unwrap();
    conn.execute_batch(&format!(
        "PRAGMA page_size={}; PRAGMA journal_mode=OFF; PRAGMA synchronous=OFF;
         PRAGMA cache_size=-65536; PRAGMA temp_store=MEMORY; PRAGMA locking_mode=EXCLUSIVE;",
        page_size()
    ))
    .unwrap();
    conn.execute_batch(SCHEMA).unwrap();
    conn.execute_batch("BEGIN").unwrap();
    let mut st = ImportStats::default();
    {
        let mut strings = Interner {
            map: HashMap::new(),
            stmt: conn.prepare("INSERT INTO strings(id, s) VALUES (?1, ?2)").unwrap(),
        };
        let mut ins_node = conn.prepare("INSERT INTO nodes VALUES (?1, ?2, ?3, ?4)").unwrap();
        let mut ins_node_meta = conn.prepare("INSERT INTO node_meta VALUES (?1, ?2, ?3, ?4, ?5)").unwrap();
        let mut ins_way = conn.prepare("INSERT INTO ways VALUES (?1, ?2, ?3, ?4, ?5, ?6)").unwrap();
        let mut ins_wn = conn.prepare("INSERT INTO way_nodes VALUES (?1, ?2, ?3)").unwrap();
        let mut ins_rel = conn.prepare("INSERT INTO relations VALUES (?1, ?2, ?3, ?4, ?5, ?6)").unwrap();
        let mut ins_mem = conn.prepare("INSERT INTO members VALUES (?1, ?2, ?3, ?4, ?5)").unwrap();
        let mut ins_tag = conn.prepare("INSERT OR IGNORE INTO tags VALUES (?1, ?2, ?3, ?4)").unwrap();
        let mut ins_sp = conn.prepare("INSERT INTO spatial VALUES (?1, ?2, ?3, ?4, ?5)").unwrap();

        // Node coords kept in memory to compute way bounds.
        let mut coords: Vec<(i64, i32, i32)> = Vec::with_capacity(1 << 21);
        let mut sorted_checked = false;

        let mut add_tags = |kind: i64, id: i64, tags: &mut dyn Iterator<Item = (&str, &str)>, strings: &mut Interner, st: &mut ImportStats| -> bool {
            let mut any = false;
            for (k, v) in tags {
                any = true;
                let (k, v) = (strings.get(k), strings.get(v));
                if ins_tag.execute(params![kind, id, k, v]).unwrap() == 0 {
                    st.dup_tags += 1;
                }
            }
            any
        };

        ElementReader::from_path(pbf)
            .unwrap()
            .for_each(|el| match el {
                Element::DenseNode(n) => {
                    let (id, lat, lon) = (n.id(), n.decimicro_lat(), n.decimicro_lon());
                    let info = n.info();
                    let ver = info.map(|i| i.version()).unwrap_or(0);
                    ins_node.execute(params![id, lat, lon, ver]).unwrap();
                    coords.push((id, lat, lon));
                    let tagged = add_tags(NODE, id, &mut n.tags(), &mut strings, &mut st);
                    if tagged {
                        if let Some(i) = info {
                            let u = strings.get(i.user().unwrap_or(""));
                            ins_node_meta
                                .execute(params![id, i.milli_timestamp() / 1000, i.changeset(), i.uid(), u])
                                .unwrap();
                        }
                        ins_sp.execute(params![id * 2 + NODE, lon, lon, lat, lat]).unwrap();
                    }
                }
                Element::Node(n) => {
                    let (id, lat, lon) = (n.id(), n.decimicro_lat(), n.decimicro_lon());
                    let i = n.info();
                    ins_node.execute(params![id, lat, lon, i.version().unwrap_or(0)]).unwrap();
                    coords.push((id, lat, lon));
                    if add_tags(NODE, id, &mut n.tags(), &mut strings, &mut st) {
                        let u = strings.get(i.user().and_then(|r| r.ok()).unwrap_or(""));
                        ins_node_meta
                            .execute(params![id, i.milli_timestamp().map(|t| t / 1000), i.changeset(), i.uid(), u])
                            .unwrap();
                        ins_sp.execute(params![id * 2 + NODE, lon, lon, lat, lat]).unwrap();
                    }
                }
                Element::Way(w) => {
                    if !sorted_checked {
                        if !coords.windows(2).all(|p| p[0].0 < p[1].0) {
                            coords.sort_unstable_by_key(|c| c.0);
                        }
                        sorted_checked = true;
                    }
                    let id = w.id();
                    let i = w.info();
                    let u = strings.get(i.user().and_then(|r| r.ok()).unwrap_or(""));
                    ins_way
                        .execute(params![id, i.version(), i.milli_timestamp().map(|t| t / 1000), i.changeset(), i.uid(), u])
                        .unwrap();
                    let refs: Vec<i64> = w.refs().collect();
                    let (mut x0, mut x1, mut y0, mut y1) = (i32::MAX, i32::MIN, i32::MAX, i32::MIN);
                    for (seq, r) in refs.iter().enumerate() {
                        ins_wn.execute(params![id, seq as i64, r]).unwrap();
                        match coords.binary_search_by_key(r, |c| c.0) {
                            Ok(ix) => {
                                let (_, lat, lon) = coords[ix];
                                x0 = x0.min(lon);
                                x1 = x1.max(lon);
                                y0 = y0.min(lat);
                                y1 = y1.max(lat);
                            }
                            Err(_) => st.missing_way_nodes += 1,
                        }
                    }
                    if x0 <= x1 {
                        ins_sp.execute(params![id * 2 + WAY, x0, x1, y0, y1]).unwrap();
                    }
                    add_tags(WAY, id, &mut w.tags(), &mut strings, &mut st);
                    st.way_hash = st.way_hash.wrapping_add(seq_hash(refs.iter().copied()) ^ id as u64);
                    st.way_refs += refs.len() as u64;
                    if refs.len() > st.get_way.1.len() {
                        st.get_way = (id, refs.clone());
                    }
                    if st.repeat_way.is_none() {
                        let mut s = refs.clone();
                        s.sort_unstable();
                        if s.windows(2).any(|p| p[0] == p[1]) {
                            st.repeat_way = Some((id, refs));
                        }
                    }
                }
                Element::Relation(r) => {
                    let id = r.id();
                    let i = r.info();
                    let u = strings.get(i.user().and_then(|r| r.ok()).unwrap_or(""));
                    ins_rel
                        .execute(params![id, i.version(), i.milli_timestamp().map(|t| t / 1000), i.changeset(), i.uid(), u])
                        .unwrap();
                    for (seq, m) in r.members().enumerate() {
                        let kind = match m.member_type {
                            RelMemberType::Node => NODE,
                            RelMemberType::Way => WAY,
                            RelMemberType::Relation => REL,
                        };
                        let role = strings.get(m.role().unwrap_or(""));
                        ins_mem.execute(params![id, seq as i64, kind, m.member_id, role]).unwrap();
                    }
                    add_tags(REL, id, &mut r.tags(), &mut strings, &mut st);
                }
            })
            .unwrap();
    }
    conn.execute_batch(INDEXES).unwrap();
    conn.execute_batch("COMMIT").unwrap();
    conn.execute_batch("ANALYZE").unwrap();
    if env_flag("VACUUM", true) {
        // VACUUM INTO writes the compacted copy straight to disk; plain VACUUM
        // with temp_store=MEMORY would hold a full db copy in RAM.
        let tmp = format!("{db}.vac");
        let _ = std::fs::remove_file(&tmp);
        conn.execute("VACUUM INTO ?1", [&tmp]).unwrap();
        drop(conn);
        std::fs::rename(&tmp, db).unwrap();
    } else {
        drop(conn);
    }
    st
}

fn hwm_bytes() -> u64 {
    std::fs::read_to_string("/proc/self/status")
        .ok()
        .and_then(|s| {
            s.lines()
                .find(|l| l.starts_with("VmHWM:"))
                .and_then(|l| l.split_whitespace().nth(1).and_then(|v| v.parse::<u64>().ok()))
        })
        .map(|kb| kb * 1024)
        .unwrap_or(0)
}

fn ms(t: Instant) -> f64 {
    t.elapsed().as_secs_f64() * 1000.0
}

fn pct(v: &mut [f64], p: f64) -> f64 {
    v.sort_by(|a, b| a.partial_cmp(b).unwrap());
    v[((v.len() - 1) as f64 * p) as usize]
}

/// What a UI needs to show an object: id, all tags, node coordinate.
struct Obj {
    kind: i64,
    id: i64,
    tags: Vec<(String, String)>,
    coord: Option<(i64, i64)>,
}

fn resolve(conn: &Connection, s: &str) -> Option<i64> {
    conn.prepare_cached("SELECT id FROM strings WHERE s = ?1")
        .unwrap()
        .query_row([s], |r| r.get(0))
        .optional()
        .unwrap()
}

fn fetch(conn: &Connection, kind: i64, id: i64) -> Obj {
    let mut ts = conn
        .prepare_cached(
            "SELECT k.s, v.s FROM tags t JOIN strings k ON k.id = t.key JOIN strings v ON v.id = t.value
             WHERE t.kind = ?1 AND t.id = ?2",
        )
        .unwrap();
    let tags = ts
        .query_map(params![kind, id], |r| Ok((r.get(0)?, r.get(1)?)))
        .unwrap()
        .map(|r| r.unwrap())
        .collect();
    let coord = if kind == NODE {
        conn.prepare_cached("SELECT lat, lon FROM nodes WHERE id = ?1")
            .unwrap()
            .query_row([id], |r| Ok((r.get(0)?, r.get(1)?)))
            .optional()
            .unwrap()
    } else {
        None
    };
    Obj { kind, id, tags, coord }
}

fn tag_query(conn: &Connection, key: &str, value: &str) -> Vec<Obj> {
    let (Some(k), Some(v)) = (resolve(conn, key), resolve(conn, value)) else {
        return vec![];
    };
    let ids: Vec<(i64, i64)> = conn
        .prepare_cached("SELECT kind, id FROM tags WHERE key = ?1 AND value = ?2 LIMIT 1000")
        .unwrap()
        .query_map(params![k, v], |r| Ok((r.get(0)?, r.get(1)?)))
        .unwrap()
        .map(|r| r.unwrap())
        .collect();
    ids.into_iter().map(|(kind, id)| fetch(conn, kind, id)).collect()
}

/// Spatial-first: R-tree candidates, then tag check by primary key.
fn bbox_query(conn: &Connection, key: &str, value: &str) -> Vec<Obj> {
    let (Some(k), Some(v)) = (resolve(conn, key), resolve(conn, value)) else {
        return vec![];
    };
    let (w, s, e, n) = BOX;
    let cands: Vec<i64> = conn
        .prepare_cached(
            "SELECT id FROM spatial WHERE min_lon <= ?3 AND max_lon >= ?1 AND min_lat <= ?4 AND max_lat >= ?2",
        )
        .unwrap()
        .query_map(params![e7(w), e7(s), e7(e), e7(n)], |r| r.get(0))
        .unwrap()
        .map(|r| r.unwrap())
        .collect();
    let mut chk = conn
        .prepare_cached("SELECT 1 FROM tags WHERE kind = ?1 AND id = ?2 AND key = ?3 AND value = ?4")
        .unwrap();
    let mut out = vec![];
    for c in cands {
        let (kind, id) = (c & 1, c >> 1);
        if chk.exists(params![kind, id, k, v]).unwrap() {
            out.push(fetch(conn, kind, id));
        }
    }
    out
}

/// Tag-first alternative: tag index, then R-tree rowid lookup + box test.
fn bbox_query_tagfirst(conn: &Connection, key: &str, value: &str) -> usize {
    let (Some(k), Some(v)) = (resolve(conn, key), resolve(conn, value)) else {
        return 0;
    };
    let (w, s, e, n) = BOX;
    let ids: Vec<(i64, i64)> = conn
        .prepare_cached("SELECT kind, id FROM tags WHERE key = ?1 AND value = ?2 AND kind < 2")
        .unwrap()
        .query_map(params![k, v], |r| Ok((r.get(0)?, r.get(1)?)))
        .unwrap()
        .map(|r| r.unwrap())
        .collect();
    let mut chk = conn
        .prepare_cached(
            "SELECT 1 FROM spatial WHERE id = ?1 AND min_lon <= ?4 AND max_lon >= ?2 AND min_lat <= ?5 AND max_lat >= ?3",
        )
        .unwrap();
    let mut out = vec![];
    for (kind, id) in ids {
        if chk.exists(params![id * 2 + kind, e7(w), e7(s), e7(e), e7(n)]).unwrap() {
            out.push(fetch(conn, kind, id));
        }
    }
    out.len()
}

struct WayFull {
    version: i64,
    ts: i64,
    changeset: i64,
    uid: i64,
    user: String,
    tags: Vec<(String, String)>,
    nodes: Vec<i64>,
}

fn get_way(conn: &Connection, id: i64) -> Option<WayFull> {
    let mut w = conn
        .prepare_cached(
            "SELECT w.version, w.ts, w.changeset, w.uid, u.s FROM ways w LEFT JOIN strings u ON u.id = w.user WHERE w.id = ?1",
        )
        .unwrap()
        .query_row([id], |r| {
            Ok(WayFull {
                version: r.get(0)?,
                ts: r.get(1)?,
                changeset: r.get(2)?,
                uid: r.get(3)?,
                user: r.get::<_, Option<String>>(4)?.unwrap_or_default(),
                tags: vec![],
                nodes: vec![],
            })
        })
        .optional()
        .unwrap()?;
    w.tags = fetch(conn, WAY, id).tags;
    w.nodes = conn
        .prepare_cached("SELECT node_id FROM way_nodes WHERE way_id = ?1 ORDER BY seq")
        .unwrap()
        .query_map([id], |r| r.get(0))
        .unwrap()
        .map(|r| r.unwrap())
        .collect();
    Some(w)
}

fn count(conn: &Connection, sql: &str) -> i64 {
    conn.query_row(sql, [], |r| r.get(0)).unwrap()
}

fn run(pbf: &str, db: &str) {
    let pbf_bytes = std::fs::metadata(pbf).unwrap().len();
    let t = Instant::now();
    let st = import(pbf, db);
    let import_ms = ms(t);
    let hwm_after_import = hwm_bytes();
    let db_bytes = std::fs::metadata(db).unwrap().len();

    let conn = Connection::open(db).unwrap();
    // Cold-ish tag query: fresh connection (OS page cache is still warm).
    let t = Instant::now();
    let cafes = tag_query(&conn, "amenity", "cafe");
    let tag_cold = ms(t);
    let mut tq = vec![];
    for _ in 0..20 {
        let t = Instant::now();
        let r = tag_query(&conn, "amenity", "cafe");
        tq.push(ms(t));
        assert_eq!(r.len(), cafes.len());
    }
    let downtown = bbox_query(&conn, "amenity", "cafe");
    let mut bq = vec![];
    for _ in 0..50 {
        let t = Instant::now();
        let r = bbox_query(&conn, "amenity", "cafe");
        bq.push(ms(t));
        assert_eq!(r.len(), downtown.len());
    }
    let mut bq2 = vec![];
    for _ in 0..50 {
        let t = Instant::now();
        let n = bbox_query_tagfirst(&conn, "amenity", "cafe");
        bq2.push(ms(t));
        assert_eq!(n, downtown.len());
    }
    let (gid, grefs) = &st.get_way;
    let mut gq = vec![];
    for _ in 0..200 {
        let t = Instant::now();
        let w = get_way(&conn, *gid).unwrap();
        gq.push(ms(t));
        assert_eq!(&w.nodes, grefs);
    }

    let mut tables = Map::new();
    if let Ok(mut s) = conn.prepare("SELECT name, SUM(pgsize) FROM dbstat GROUP BY name ORDER BY 2 DESC") {
        for row in s
            .query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, i64>(1)?)))
            .unwrap()
        {
            let (name, b) = row.unwrap();
            tables.insert(name, json!((b as f64 / 1e6 * 10.0).round() / 10.0));
        }
    }
    let nodes = count(&conn, "SELECT count(*) FROM nodes");
    let ways = count(&conn, "SELECT count(*) FROM ways");
    let relations = count(&conn, "SELECT count(*) FROM relations");
    let hwm_final = hwm_bytes();

    // Correctness checks (stderr only; not part of the bench JSON).
    let mut diag = Map::new();
    diag.insert("get_way_id".into(), json!(gid));
    diag.insert("get_way_refs".into(), json!(grefs.len()));
    let w = get_way(&conn, *gid).unwrap();
    diag.insert(
        "get_way_sample".into(),
        json!({"version": w.version, "ts": w.ts, "changeset": w.changeset, "uid": w.uid, "user": w.user, "tags": w.tags.len()}),
    );
    if let Some((rid, rrefs)) = &st.repeat_way {
        let back = get_way(&conn, *rid).unwrap().nodes;
        diag.insert("repeat_way".into(), json!({"id": rid, "refs": rrefs.len(), "roundtrip_ok": &back == rrefs}));
    }
    {
        let mut s = conn.prepare("SELECT way_id, node_id FROM way_nodes ORDER BY way_id, seq").unwrap();
        let mut rows = s.query([]).unwrap();
        let (mut h, mut cur, mut buf, mut total) = (0u64, i64::MIN, Vec::<i64>::new(), 0u64);
        while let Some(r) = rows.next().unwrap() {
            let (wid, nid): (i64, i64) = (r.get(0).unwrap(), r.get(1).unwrap());
            if wid != cur {
                if cur != i64::MIN {
                    h = h.wrapping_add(seq_hash(buf.iter().copied()) ^ cur as u64);
                }
                cur = wid;
                buf.clear();
            }
            buf.push(nid);
            total += 1;
        }
        if cur != i64::MIN {
            h = h.wrapping_add(seq_hash(buf.iter().copied()) ^ cur as u64);
        }
        diag.insert("all_ways_roundtrip_ok".into(), json!(h == st.way_hash && total == st.way_refs));
        diag.insert("way_refs".into(), json!(total));
    }
    diag.insert("dup_tags_ignored".into(), json!(st.dup_tags));
    diag.insert("missing_way_nodes".into(), json!(st.missing_way_nodes));
    diag.insert("bbox_tagfirst_p50_ms".into(), json!(pct(&mut bq2.clone(), 0.5)));
    diag.insert("bbox_tagfirst_p95_ms".into(), json!(pct(&mut bq2, 0.95)));
    let kinds = |v: &[Obj]| {
        let mut c = [0; 3];
        for o in v {
            c[o.kind as usize] += 1;
        }
        c
    };
    diag.insert("cafes_by_kind".into(), json!(kinds(&cafes)));
    diag.insert("downtown_by_kind".into(), json!(kinds(&downtown)));
    let sample: Vec<Value> = downtown
        .iter()
        .map(|o| json!({"k": o.kind, "id": o.id, "name": o.tags.iter().find(|t| t.0 == "name").map(|t| &t.1), "coord": o.coord}))
        .collect();
    diag.insert("downtown".into(), json!(sample));
    eprintln!("{}", Value::Object(diag));

    let out = json!({
        "variant": "A",
        "pbf_bytes": pbf_bytes,
        "db_bytes": db_bytes,
        "nodes": nodes,
        "ways": ways,
        "relations": relations,
        "import_ms": import_ms,
        "hwm_after_import_bytes": hwm_after_import,
        "cafes": cafes.len(),
        "downtown_cafes": downtown.len(),
        "tag_query_cold_ms": tag_cold,
        "tag_query_p50_ms": pct(&mut tq.clone(), 0.5),
        "tag_query_p95_ms": pct(&mut tq, 0.95),
        "bbox_query_p50_ms": pct(&mut bq.clone(), 0.5),
        "bbox_query_p95_ms": pct(&mut bq, 0.95),
        "get_p50_ms": pct(&mut gq.clone(), 0.5),
        "get_p95_ms": pct(&mut gq, 0.95),
        "table_mb": Value::Object(tables),
        "hwm_final_bytes": hwm_final,
    });
    println!("{out}");
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    match (args.get(1).map(String::as_str), args.get(2), args.get(3)) {
        (Some("import"), Some(pbf), Some(db)) => {
            let t = Instant::now();
            let st = import(pbf, db);
            eprintln!(
                "imported in {:.0} ms, db {} bytes, dup_tags {}, missing_way_nodes {}",
                ms(t),
                std::fs::metadata(db).unwrap().len(),
                st.dup_tags,
                st.missing_way_nodes
            );
        }
        (Some("run"), Some(pbf), Some(db)) => run(pbf, db),
        _ => {
            eprintln!("usage: bench import|run IN.pbf OUT.sqlite");
            std::process::exit(2);
        }
    }
}
