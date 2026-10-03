//! Spike variant B: compact blobs + side indexes in SQLite.
//!
//! Env knobs (for tuning measurements, defaults are the reported config):
//!   B_VALUES=text|intern   tag_index stores value text (default) or value ids + vals table
//!   B_NODE_PAYLOAD=col|table   tagged-node payload as nullable column (default) or side table
//!   B_VACUUM=0|1           VACUUM after import (default 1)
//!   B_GEO_SORT=0|1         insert R-tree rows in Morton order after loading (default 0: no gain, +1 MB)
//!   B_CACHE_MB=16          SQLite page cache during import
use osmpbf::{Element, ElementReader, RelMemberType};
use rusqlite::{params, Connection, OptionalExtension};
use serde_json::json;
use std::collections::HashMap;
use std::time::Instant;

// ---------- varint helpers ----------
fn put_uvar(buf: &mut Vec<u8>, mut v: u64) {
    while v >= 0x80 {
        buf.push((v as u8) | 0x80);
        v >>= 7;
    }
    buf.push(v as u8);
}
fn put_svar(buf: &mut Vec<u8>, v: i64) {
    put_uvar(buf, ((v << 1) ^ (v >> 63)) as u64);
}
struct Rd<'a> {
    b: &'a [u8],
    p: usize,
}
impl<'a> Rd<'a> {
    fn new(b: &'a [u8]) -> Self {
        Rd { b, p: 0 }
    }
    fn done(&self) -> bool {
        self.p >= self.b.len()
    }
    fn u(&mut self) -> u64 {
        let mut v = 0u64;
        let mut s = 0;
        loop {
            let x = self.b[self.p];
            self.p += 1;
            v |= ((x & 0x7f) as u64) << s;
            if x < 0x80 {
                return v;
            }
            s += 7;
        }
    }
    fn s(&mut self) -> i64 {
        let u = self.u();
        ((u >> 1) as i64) ^ -((u & 1) as i64)
    }
    fn bytes(&mut self, n: usize) -> &'a [u8] {
        let r = &self.b[self.p..self.p + n];
        self.p += n;
        r
    }
}

fn hwm_bytes() -> u64 {
    std::fs::read_to_string("/proc/self/status")
        .ok()
        .and_then(|s| {
            s.lines()
                .find(|l| l.starts_with("VmHWM:"))
                .and_then(|l| l.split_whitespace().nth(1).map(|x| x.parse::<u64>().unwrap_or(0) * 1024))
        })
        .unwrap_or(0)
}

struct Interner {
    map: HashMap<String, u32>,
}
impl Interner {
    fn new() -> Self {
        Interner { map: HashMap::new() }
    }
    fn get(&mut self, s: &str) -> u32 {
        if let Some(&id) = self.map.get(s) {
            return id;
        }
        let id = self.map.len() as u32;
        self.map.insert(s.to_owned(), id);
        id
    }
    fn into_vec(self) -> Vec<String> {
        let mut v = vec![String::new(); self.map.len()];
        for (s, id) in self.map {
            v[id as usize] = s;
        }
        v
    }
}

fn env(k: &str, d: &str) -> String {
    std::env::var(k).unwrap_or_else(|_| d.to_string())
}

struct ImportStats {
    nodes: u64,
    ways: u64,
    relations: u64,
    get_way: i64,
    get_way_refs: usize,
    ways_missing_coords: u64,
}

const K_NODE: i64 = 0;
const K_WAY: i64 = 1;
const K_REL: i64 = 2;

fn encode_tags<'a>(
    buf: &mut Vec<u8>,
    tags: impl Iterator<Item = (&'a str, &'a str)>,
    dict: &mut Interner,
    vals: &mut Interner,
    tag_rows: &mut Vec<(u32, u32, u8, i64)>,
    kind: u8,
    id: i64,
) -> usize {
    let mut tmp: Vec<(&str, &str)> = tags.collect();
    tmp.sort(); // BTreeMap order, like model::Tags
    put_uvar(buf, tmp.len() as u64);
    for (k, v) in &tmp {
        let kid = dict.get(k);
        put_uvar(buf, kid as u64);
        put_uvar(buf, v.len() as u64);
        buf.extend_from_slice(v.as_bytes());
        tag_rows.push((kid, vals.get(v), kind, id));
    }
    tmp.len()
}

fn put_meta(buf: &mut Vec<u8>, version: Option<i64>, ts_ms: i64, cs: i64, uid: i64) {
    if let Some(v) = version {
        put_uvar(buf, v.max(0) as u64);
    }
    put_uvar(buf, (ts_ms / 1000).max(0) as u64);
    put_uvar(buf, cs.max(0) as u64);
    put_uvar(buf, uid.max(0) as u64);
}


struct Importer<'c> {
    col_payload: bool,
    ins_node: rusqlite::Statement<'c>,
    ins_np: Option<rusqlite::Statement<'c>>,
    ins_way: rusqlite::Statement<'c>,
    ins_rel: rusqlite::Statement<'c>,
    geo_rows: Vec<(i64, [i32; 4])>,
    buf: Vec<u8>,
    rbuf: Vec<u8>,
    dict: Interner,
    vals: Interner,
    users: HashMap<i64, String>,
    tag_rows: Vec<(u32, u32, u8, i64)>,
    node_way: Vec<(i64, i64)>,
    member_rel: Vec<(i64, i64)>,
    node_ids: Vec<i64>,
    node_xy: Vec<(i32, i32)>,
    way_bounds: HashMap<i64, [i32; 4]>,
    st: ImportStats,
    sorted: bool,
}

impl<'c> Importer<'c> {
    #[allow(clippy::too_many_arguments)]
    fn node<'a>(&mut self, id: i64, lat: i32, lon: i32, version: i64, tags: impl Iterator<Item = (&'a str, &'a str)>, ts: i64, cs: i64, uid: i64, user: &str) {
        self.buf.clear();
        let n = encode_tags(&mut self.buf, tags, &mut self.dict, &mut self.vals, &mut self.tag_rows, 0, id);
        if let Some(&last) = self.node_ids.last() {
            if id <= last {
                self.sorted = false;
            }
        }
        self.node_ids.push(id);
        self.node_xy.push((lon, lat));
        self.st.nodes += 1;
        if n > 0 {
            put_meta(&mut self.buf, None, ts, cs, uid);
            self.users.entry(uid).or_insert_with(|| user.to_owned());
            self.geo_rows.push((id * 4 + K_NODE, [lon, lon, lat, lat]));
            if self.col_payload {
                self.ins_node.execute(params![id, lat, lon, version, &self.buf]).unwrap();
            } else {
                self.ins_node.execute(params![id, lat, lon, version]).unwrap();
                self.ins_np.as_mut().unwrap().execute(params![id, &self.buf]).unwrap();
            }
        } else if self.col_payload {
            self.ins_node.execute(params![id, lat, lon, version, rusqlite::types::Null]).unwrap();
        } else {
            self.ins_node.execute(params![id, lat, lon, version]).unwrap();
        }
    }
    fn coord(&self, id: i64) -> Option<(i32, i32)> {
        self.node_ids.binary_search(&id).ok().map(|i| self.node_xy[i])
    }
    fn way(&mut self, w: &osmpbf::Way) {
        let id = w.id();
        self.buf.clear();
        self.rbuf.clear();
        encode_tags(&mut self.buf, w.tags(), &mut self.dict, &mut self.vals, &mut self.tag_rows, 1, id);
        let info = w.info();
        let uid = info.uid().unwrap_or(0) as i64;
        put_meta(&mut self.buf, Some(info.version().unwrap_or(0) as i64), info.milli_timestamp().unwrap_or(0), info.changeset().unwrap_or(0), uid);
        if let Some(Ok(u)) = info.user() {
            self.users.entry(uid).or_insert_with(|| u.to_owned());
        }
        let mut prev = 0i64;
        let mut b = [i32::MAX, i32::MIN, i32::MAX, i32::MIN];
        let mut nrefs = 0usize;
        let mut missing = false;
        for r in w.refs() {
            put_svar(&mut self.rbuf, r - prev);
            prev = r;
            nrefs += 1;
            self.node_way.push((r, id));
            match self.coord(r) {
                Some((x, y)) => {
                    b[0] = b[0].min(x);
                    b[1] = b[1].max(x);
                    b[2] = b[2].min(y);
                    b[3] = b[3].max(y);
                }
                None => missing = true,
            }
        }
        if missing {
            self.st.ways_missing_coords += 1;
        }
        if nrefs > self.st.get_way_refs || (nrefs == self.st.get_way_refs && id < self.st.get_way) {
            self.st.get_way_refs = nrefs;
            self.st.get_way = id;
        }
        self.ins_way.execute(params![id, &self.rbuf, &self.buf]).unwrap();
        if b[0] != i32::MAX {
            self.geo_rows.push((id * 4 + K_WAY, b));
            self.way_bounds.insert(id, b);
        }
        self.st.ways += 1;
    }
    fn rel(&mut self, r: &osmpbf::Relation) {
        let id = r.id();
        self.buf.clear();
        self.rbuf.clear();
        encode_tags(&mut self.buf, r.tags(), &mut self.dict, &mut self.vals, &mut self.tag_rows, 2, id);
        let info = r.info();
        let uid = info.uid().unwrap_or(0) as i64;
        put_meta(&mut self.buf, Some(info.version().unwrap_or(0) as i64), info.milli_timestamp().unwrap_or(0), info.changeset().unwrap_or(0), uid);
        if let Some(Ok(u)) = info.user() {
            self.users.entry(uid).or_insert_with(|| u.to_owned());
        }
        let mut prev = 0i64;
        let mut b = [i32::MAX, i32::MIN, i32::MAX, i32::MIN];
        for m in r.members() {
            let kind = match m.member_type {
                RelMemberType::Node => K_NODE,
                RelMemberType::Way => K_WAY,
                RelMemberType::Relation => K_REL,
            };
            let role = self.dict.get(m.role().unwrap_or(""));
            put_uvar(&mut self.rbuf, ((role as u64) << 2) | kind as u64);
            put_svar(&mut self.rbuf, m.member_id - prev);
            prev = m.member_id;
            self.member_rel.push((m.member_id * 4 + kind, id));
            let mb = match kind {
                K_NODE => self.coord(m.member_id).map(|(x, y)| [x, x, y, y]),
                K_WAY => self.way_bounds.get(&m.member_id).copied(),
                _ => None, // nested relations not expanded (spike)
            };
            if let Some(mb) = mb {
                b[0] = b[0].min(mb[0]);
                b[1] = b[1].max(mb[1]);
                b[2] = b[2].min(mb[2]);
                b[3] = b[3].max(mb[3]);
            }
        }
        self.ins_rel.execute(params![id, &self.rbuf, &self.buf]).unwrap();
        if b[0] != i32::MAX {
            self.geo_rows.push((id * 4 + K_REL, b));
        }
        self.st.relations += 1;
    }
}

fn import(pbf: &str, out: &str) -> ImportStats {
    let _ = std::fs::remove_file(out);
    let values_mode = env("B_VALUES", "text");
    let col_payload = env("B_NODE_PAYLOAD", "col") == "col";
    let conn = Connection::open(out).unwrap();
    let cache_kb: i64 = env("B_CACHE_MB", "16").parse::<i64>().unwrap() * 1024;
    conn.execute_batch(&format!(
        "PRAGMA page_size=4096; PRAGMA journal_mode=OFF; PRAGMA synchronous=OFF;
         PRAGMA locking_mode=EXCLUSIVE; PRAGMA cache_size=-{cache_kb};"
    ))
    .unwrap();
    if col_payload {
        conn.execute_batch("CREATE TABLE nodes(id INTEGER PRIMARY KEY, lat INTEGER, lon INTEGER, version INTEGER, payload BLOB);").unwrap();
    } else {
        conn.execute_batch(
            "CREATE TABLE nodes(id INTEGER PRIMARY KEY, lat INTEGER, lon INTEGER, version INTEGER);
             CREATE TABLE node_payload(id INTEGER PRIMARY KEY, payload BLOB);",
        )
        .unwrap();
    }
    conn.execute_batch(
        "CREATE TABLE ways(id INTEGER PRIMARY KEY, refs BLOB, payload BLOB);
         CREATE TABLE relations(id INTEGER PRIMARY KEY, members BLOB, payload BLOB);
         CREATE TABLE dict(id INTEGER PRIMARY KEY, s TEXT);
         CREATE TABLE users(uid INTEGER PRIMARY KEY, name TEXT);
         CREATE VIRTUAL TABLE geo USING rtree_i32(id, minx, maxx, miny, maxy);
         BEGIN;",
    )
    .unwrap();

    let st = {
        let mut im = Importer {
            col_payload,
            ins_node: conn
                .prepare(if col_payload { "INSERT INTO nodes VALUES(?1,?2,?3,?4,?5)" } else { "INSERT INTO nodes VALUES(?1,?2,?3,?4)" })
                .unwrap(),
            ins_np: if col_payload { None } else { Some(conn.prepare("INSERT INTO node_payload VALUES(?1,?2)").unwrap()) },
            ins_way: conn.prepare("INSERT INTO ways VALUES(?1,?2,?3)").unwrap(),
            ins_rel: conn.prepare("INSERT INTO relations VALUES(?1,?2,?3)").unwrap(),
            geo_rows: Vec::new(),
            buf: Vec::with_capacity(4096),
            rbuf: Vec::with_capacity(4096),
            dict: Interner::new(),
            vals: Interner::new(),
            users: HashMap::new(),
            tag_rows: Vec::new(),
            node_way: Vec::new(),
            member_rel: Vec::new(),
            node_ids: Vec::new(),
            node_xy: Vec::new(),
            way_bounds: HashMap::new(),
            st: ImportStats { nodes: 0, ways: 0, relations: 0, get_way: 0, get_way_refs: 0, ways_missing_coords: 0 },
            sorted: true,
        };
        ElementReader::from_path(pbf)
            .unwrap()
            .for_each(|e| match e {
                Element::DenseNode(n) => {
                    let (v, ts, cs, uid, user) = match n.info() {
                        Some(i) => (i.version() as i64, i.milli_timestamp(), i.changeset(), i.uid() as i64, i.user().unwrap_or("")),
                        None => (0, 0, 0, 0, ""),
                    };
                    im.node(n.id(), n.decimicro_lat(), n.decimicro_lon(), v, n.tags(), ts, cs, uid, user);
                }
                Element::Node(n) => {
                    let i = n.info();
                    let user = match i.user() {
                        Some(Ok(u)) => u,
                        _ => "",
                    };
                    im.node(n.id(), n.decimicro_lat(), n.decimicro_lon(), i.version().unwrap_or(0) as i64, n.tags(), i.milli_timestamp().unwrap_or(0), i.changeset().unwrap_or(0), i.uid().unwrap_or(0) as i64, user);
                }
                Element::Way(w) => im.way(&w),
                Element::Relation(r) => im.rel(&r),
            })
            .unwrap();
        assert!(im.sorted, "node ids not sorted; binary search invalid");
        eprintln!("[import] ways missing node coords: {}", im.st.ways_missing_coords);
        eprintln!("[import] get way {} with {} refs", im.st.get_way, im.st.get_way_refs);
        eprintln!("[import] objects loaded, hwm={} MB; writing side tables", hwm_bytes() >> 20);
        // free coordinate map before side tables
        im.node_ids = Vec::new();
        im.node_xy = Vec::new();
        im.way_bounds = HashMap::new();
        let Importer { ins_node, ins_np, ins_way, ins_rel, mut geo_rows, dict, vals, users, mut tag_rows, mut node_way, mut member_rel, st, .. } = im;
        drop((ins_node, ins_np, ins_way, ins_rel));
        if env("B_GEO_SORT", "0") == "1" {
            // coarse spatial order (Morton on 2^-16-ish cells of the e7 ints) for R-tree locality
            fn morton(x: u32, y: u32) -> u64 {
                let mut z = 0u64;
                for i in 0..32 {
                    z |= (((x >> i) & 1) as u64) << (2 * i) | (((y >> i) & 1) as u64) << (2 * i + 1);
                }
                z
            }
            geo_rows.sort_by_cached_key(|(_, b)| {
                let cx = ((b[0] as i64 + b[1] as i64) / 2 + (1i64 << 31)) as u64 as u32;
                let cy = ((b[2] as i64 + b[3] as i64) / 2 + (1i64 << 31)) as u64 as u32;
                morton(cx, cy)
            });
        }
        {
            let mut s = conn.prepare("INSERT INTO geo VALUES(?1,?2,?3,?4,?5)").unwrap();
            for (id, b) in &geo_rows {
                s.execute(params![id, b[0], b[1], b[2], b[3]]).unwrap();
            }
        }
        drop(geo_rows);

        let dict = dict.into_vec();
        {
            let mut s = conn.prepare("INSERT INTO dict VALUES(?1,?2)").unwrap();
            for (i, k) in dict.iter().enumerate() {
                s.execute(params![i as i64, k]).unwrap();
            }
            let mut s = conn.prepare("INSERT INTO users VALUES(?1,?2)").unwrap();
            for (uid, n) in &users {
                s.execute(params![uid, n]).unwrap();
            }
        }
        // side indexes: sort in memory, append in PK order into WITHOUT ROWID tables
        node_way.sort_unstable();
        node_way.dedup();
        conn.execute_batch("CREATE TABLE node_way(node_id INTEGER, way_id INTEGER, PRIMARY KEY(node_id, way_id)) WITHOUT ROWID;").unwrap();
        {
            let mut s = conn.prepare("INSERT INTO node_way VALUES(?1,?2)").unwrap();
            for (n, w) in &node_way {
                s.execute(params![n, w]).unwrap();
            }
        }
        drop(node_way);
        member_rel.sort_unstable();
        member_rel.dedup();
        conn.execute_batch("CREATE TABLE member_rel(member INTEGER, rel_id INTEGER, PRIMARY KEY(member, rel_id)) WITHOUT ROWID;").unwrap();
        {
            let mut s = conn.prepare("INSERT INTO member_rel VALUES(?1,?2)").unwrap();
            for (m, r) in &member_rel {
                s.execute(params![m, r]).unwrap();
            }
        }
        drop(member_rel);
        let vals = vals.into_vec();
        if values_mode == "intern" {
            conn.execute_batch("CREATE TABLE vals(id INTEGER PRIMARY KEY, s TEXT);").unwrap();
            {
                let mut s = conn.prepare("INSERT INTO vals VALUES(?1,?2)").unwrap();
                for (i, v) in vals.iter().enumerate() {
                    s.execute(params![i as i64, v]).unwrap();
                }
            }
            conn.execute_batch("CREATE INDEX vals_s ON vals(s);").unwrap();
            tag_rows.sort_unstable();
            conn.execute_batch("CREATE TABLE tag_index(k INTEGER, v INTEGER, kind INTEGER, id INTEGER, PRIMARY KEY(k,v,kind,id)) WITHOUT ROWID;").unwrap();
            let mut s = conn.prepare("INSERT INTO tag_index VALUES(?1,?2,?3,?4)").unwrap();
            for (k, v, kind, id) in &tag_rows {
                s.execute(params![k, v, kind, id]).unwrap();
            }
        } else {
            tag_rows.sort_unstable_by(|a, b| (a.0, vals[a.1 as usize].as_str(), a.2, a.3).cmp(&(b.0, vals[b.1 as usize].as_str(), b.2, b.3)));
            conn.execute_batch("CREATE TABLE tag_index(k INTEGER, v TEXT, kind INTEGER, id INTEGER, PRIMARY KEY(k,v,kind,id)) WITHOUT ROWID;").unwrap();
            let mut s = conn.prepare("INSERT INTO tag_index VALUES(?1,?2,?3,?4)").unwrap();
            for (k, v, kind, id) in &tag_rows {
                s.execute(params![k, vals[*v as usize], kind, id]).unwrap();
            }
        }
        eprintln!("[import] tag rows {} distinct values {} keys+roles {}", tag_rows.len(), vals.len(), dict.len());
        st
    };
    conn.execute_batch("COMMIT;").unwrap();
    eprintln!("[import] committed, hwm={} MB", hwm_bytes() >> 20);
    if env("B_VACUUM", "1") == "1" {
        conn.execute_batch("PRAGMA cache_size=-16384; VACUUM;").unwrap();
    }
    conn.execute_batch("ANALYZE;").unwrap();
    st
}

// ---------- read side ----------
#[allow(dead_code)]
#[derive(Debug)]
struct Meta {
    version: u64,
    timestamp: u64,
    changeset: u64,
    uid: u64,
    user: String,
}
#[allow(dead_code)]
#[derive(Debug)]
enum Obj {
    Node { id: i64, lat: i32, lon: i32, tags: Vec<(String, String)>, meta: Option<Meta> },
    Way { id: i64, refs: Vec<i64>, tags: Vec<(String, String)>, meta: Meta },
    Rel { id: i64, members: Vec<(i64, i64, String)>, tags: Vec<(String, String)>, meta: Meta },
}

struct Store {
    conn: Connection,
    dict: Vec<String>,
    key_ids: HashMap<String, i64>,
    users: HashMap<u64, String>,
    intern: bool,
    node_side: bool,
}
impl Store {
    fn open(path: &str) -> Store {
        let conn = Connection::open(path).unwrap();
        let mut dict = Vec::new();
        {
            let mut s = conn.prepare("SELECT id, s FROM dict ORDER BY id").unwrap();
            let mut rows = s.query([]).unwrap();
            while let Some(r) = rows.next().unwrap() {
                let id: i64 = r.get(0).unwrap();
                assert_eq!(id as usize, dict.len());
                dict.push(r.get::<_, String>(1).unwrap());
            }
        }
        let key_ids = dict.iter().enumerate().map(|(i, s)| (s.clone(), i as i64)).collect();
        let mut users = HashMap::new();
        {
            let mut s = conn.prepare("SELECT uid, name FROM users").unwrap();
            let mut rows = s.query([]).unwrap();
            while let Some(r) = rows.next().unwrap() {
                users.insert(r.get::<_, i64>(0).unwrap() as u64, r.get::<_, String>(1).unwrap());
            }
        }
        let has = |t: &str| conn.query_row("SELECT count(*) FROM sqlite_master WHERE name=?1", [t], |r| r.get::<_, i64>(0)).unwrap() > 0;
        let intern = has("vals");
        let node_side = has("node_payload");
        Store { conn, dict, key_ids, users, intern, node_side }
    }
    fn tags_meta(&self, p: &[u8], with_version: Option<u64>) -> (Vec<(String, String)>, Meta) {
        let mut r = Rd::new(p);
        let n = r.u();
        let mut tags = Vec::with_capacity(n as usize);
        for _ in 0..n {
            let k = self.dict[r.u() as usize].clone();
            let l = r.u() as usize;
            let v = String::from_utf8(r.bytes(l).to_vec()).unwrap();
            tags.push((k, v));
        }
        let version = match with_version {
            Some(v) => v,
            None => r.u(),
        };
        let timestamp = r.u();
        let changeset = r.u();
        let uid = r.u();
        let user = self.users.get(&uid).cloned().unwrap_or_default();
        (tags, Meta { version, timestamp, changeset, uid, user })
    }
    fn get_node(&self, id: i64) -> Option<Obj> {
        let sql = if self.node_side {
            "SELECT n.lat, n.lon, n.version, p.payload FROM nodes n LEFT JOIN node_payload p ON p.id=n.id WHERE n.id=?1"
        } else {
            "SELECT lat, lon, version, payload FROM nodes WHERE id=?1"
        };
        let mut s = self.conn.prepare_cached(sql).unwrap();
        s.query_row([id], |r| Ok((r.get::<_, i32>(0)?, r.get::<_, i32>(1)?, r.get::<_, i64>(2)?, r.get::<_, Option<Vec<u8>>>(3)?)))
            .optional()
            .unwrap()
            .map(|(lat, lon, v, p)| match p {
                Some(p) => {
                    let (tags, meta) = self.tags_meta(&p, Some(v as u64));
                    Obj::Node { id, lat, lon, tags, meta: Some(meta) }
                }
                None => Obj::Node { id, lat, lon, tags: vec![], meta: None },
            })
    }
    fn get_way(&self, id: i64) -> Option<Obj> {
        let mut s = self.conn.prepare_cached("SELECT refs, payload FROM ways WHERE id=?1").unwrap();
        s.query_row([id], |r| {
            let refs: Vec<u8> = r.get(0)?;
            let p: Vec<u8> = r.get(1)?;
            Ok((refs, p))
        })
        .optional()
        .unwrap()
        .map(|(rb, p)| {
            let mut rd = Rd::new(&rb);
            let mut refs = Vec::new();
            let mut prev = 0;
            while !rd.done() {
                prev += rd.s();
                refs.push(prev);
            }
            let (tags, meta) = self.tags_meta(&p, None);
            Obj::Way { id, refs, tags, meta }
        })
    }
    fn get_rel(&self, id: i64) -> Option<Obj> {
        let mut s = self.conn.prepare_cached("SELECT members, payload FROM relations WHERE id=?1").unwrap();
        s.query_row([id], |r| Ok((r.get::<_, Vec<u8>>(0)?, r.get::<_, Vec<u8>>(1)?)))
            .optional()
            .unwrap()
            .map(|(mb, p)| {
                let mut rd = Rd::new(&mb);
                let mut members = Vec::new();
                let mut prev = 0;
                while !rd.done() {
                    let rk = rd.u();
                    prev += rd.s();
                    members.push(((rk & 3) as i64, prev, self.dict[(rk >> 2) as usize].clone()));
                }
                let (tags, meta) = self.tags_meta(&p, None);
                Obj::Rel { id, members, tags, meta }
            })
    }
    fn get(&self, kind: i64, id: i64) -> Option<Obj> {
        match kind {
            K_NODE => self.get_node(id),
            K_WAY => self.get_way(id),
            _ => self.get_rel(id),
        }
    }
    fn tag_ids(&self, k: &str, v: &str, bbox: Option<[i32; 4]>) -> Vec<(i64, i64)> {
        let Some(&kid) = self.key_ids.get(k) else { return vec![] };
        let vexpr = if self.intern { "(SELECT id FROM vals WHERE s=?2)" } else { "?2" };
        let mut out = Vec::new();
        match bbox {
            None => {
                let sql = format!("SELECT kind, id FROM tag_index WHERE k=?1 AND v={vexpr} LIMIT 1000");
                let mut s = self.conn.prepare_cached(&sql).unwrap();
                let mut rows = s.query(params![kid, v]).unwrap();
                while let Some(r) = rows.next().unwrap() {
                    out.push((r.get(0).unwrap(), r.get(1).unwrap()));
                }
            }
            Some([w, so, e, n]) => {
                let sql = format!(
                    "SELECT t.kind, t.id FROM tag_index t JOIN geo g ON g.id = t.id*4 + t.kind
                     WHERE t.k=?1 AND t.v={vexpr} AND g.minx<=?3 AND g.maxx>=?4 AND g.miny<=?5 AND g.maxy>=?6 LIMIT 1000"
                );
                let mut s = self.conn.prepare_cached(&sql).unwrap();
                let mut rows = s.query(params![kid, v, e, w, n, so]).unwrap();
                while let Some(r) = rows.next().unwrap() {
                    out.push((r.get(0).unwrap(), r.get(1).unwrap()));
                }
            }
        }
        out
    }
    fn query_full(&self, k: &str, v: &str, bbox: Option<[i32; 4]>) -> Vec<Obj> {
        self.tag_ids(k, v, bbox).into_iter().map(|(kind, id)| self.get(kind, id).expect("indexed object missing")).collect()
    }
}

fn ms(t: Instant) -> f64 {
    t.elapsed().as_secs_f64() * 1000.0
}
fn pct(v: &mut [f64], p: f64) -> f64 {
    v.sort_by(|a, b| a.partial_cmp(b).unwrap());
    v[((v.len() - 1) as f64 * p) as usize]
}

/// Re-read the PBF and compare every way's refs (order+repeats), tags and version; sample nodes/relations.
fn verify(pbf: &str, store: &Store) {
    let mut ways_ok = 0u64;
    let mut ways_bad = 0u64;
    let mut ways_with_repeats = 0u64;
    let mut rels_ok = 0u64;
    let mut rels_bad = 0u64;
    let mut nodes_checked = 0u64;
    let mut nodes_bad = 0u64;
    let mut i = 0u64;
    ElementReader::from_path(pbf)
        .unwrap()
        .for_each(|e| match e {
            Element::Way(w) => {
                let want: Vec<i64> = w.refs().collect();
                let mut wt: Vec<(String, String)> = w.tags().map(|(a, b)| (a.to_owned(), b.to_owned())).collect();
                wt.sort();
                match store.get_way(w.id()) {
                    Some(Obj::Way { refs, tags, meta, .. }) if refs == want && tags == wt && meta.version == w.info().version().unwrap_or(0) as u64 => {
                        ways_ok += 1;
                        let mut s = want.clone();
                        s.sort_unstable();
                        s.dedup();
                        if s.len() != want.len() {
                            ways_with_repeats += 1;
                        }
                    }
                    _ => ways_bad += 1,
                }
            }
            Element::Relation(r) => {
                let want: Vec<(i64, i64, String)> = r
                    .members()
                    .map(|m| {
                        let k = match m.member_type {
                            RelMemberType::Node => 0,
                            RelMemberType::Way => 1,
                            RelMemberType::Relation => 2,
                        };
                        (k, m.member_id, m.role().unwrap_or("").to_owned())
                    })
                    .collect();
                match store.get_rel(r.id()) {
                    Some(Obj::Rel { members, .. }) if members == want => rels_ok += 1,
                    _ => rels_bad += 1,
                }
            }
            Element::DenseNode(n) => {
                i += 1;
                if i % 97 == 0 || n.tags().next().is_some() {
                    nodes_checked += 1;
                    let mut wt: Vec<(String, String)> = n.tags().map(|(a, b)| (a.to_owned(), b.to_owned())).collect();
                    wt.sort();
                    match store.get_node(n.id()) {
                        Some(Obj::Node { lat, lon, tags, .. }) if lat == n.decimicro_lat() && lon == n.decimicro_lon() && tags == wt => {}
                        _ => nodes_bad += 1,
                    }
                }
            }
            _ => {}
        })
        .unwrap();
    eprintln!(
        "[verify] ways ok={ways_ok} bad={ways_bad} (with repeated refs: {ways_with_repeats}); relations ok={rels_ok} bad={rels_bad}; nodes checked={nodes_checked} bad={nodes_bad}"
    );
}

fn main() {
    let a: Vec<String> = std::env::args().collect();
    if a.len() < 4 {
        eprintln!("usage: bench import|run IN.pbf OUT.sqlite");
        std::process::exit(2);
    }
    let (cmd, pbf, out) = (a[1].as_str(), a[2].as_str(), a[3].as_str());
    let t = Instant::now();
    let st = import(pbf, out);
    let import_ms = ms(t);
    let hwm_import = hwm_bytes();
    eprintln!("[import] {import_ms:.0} ms");
    if cmd == "import" {
        println!("{}", json!({"variant":"B","import_ms":import_ms,"hwm_after_import_bytes":hwm_import,"db_bytes":std::fs::metadata(out).unwrap().len()}));
        return;
    }

    let t = Instant::now();
    let store = Store::open(out);
    let open_ms = ms(t);
    let count = |t: &str| store.conn.query_row(&format!("SELECT count(*) FROM {t}"), [], |r| r.get::<_, i64>(0)).unwrap();
    let (nodes, ways, relations) = (count("nodes"), count("ways"), count("relations"));
    // cold tag query: first query on fresh connection (dictionary load timed separately as open_ms)
    let t = Instant::now();
    let cafes = store.query_full("amenity", "cafe", None);
    let cold = ms(t);
    eprintln!("[query] open+dict load {open_ms:.2} ms; cafes {} ({} nodes / {} ways / {} rels)", cafes.len(),
        cafes.iter().filter(|o| matches!(o, Obj::Node{..})).count(),
        cafes.iter().filter(|o| matches!(o, Obj::Way{..})).count(),
        cafes.iter().filter(|o| matches!(o, Obj::Rel{..})).count());
    let mut tq = Vec::new();
    for _ in 0..20 {
        let t = Instant::now();
        let r = store.query_full("amenity", "cafe", None);
        tq.push(ms(t));
        assert_eq!(r.len(), cafes.len());
    }
    let bb = [-1118970000i32, 407600000, -1118850000, 407690000];
    let downtown = store.query_full("amenity", "cafe", Some(bb));
    for o in &downtown {
        match o {
            Obj::Node { id, lat, lon, tags, .. } => eprintln!("[downtown] node {id} {lat} {lon} {:?}", tags.iter().find(|t| t.0 == "name").map(|t| &t.1)),
            Obj::Way { id, tags, .. } => eprintln!("[downtown] way {id} {:?}", tags.iter().find(|t| t.0 == "name").map(|t| &t.1)),
            Obj::Rel { id, .. } => eprintln!("[downtown] rel {id}"),
        }
    }
    let mut bq = Vec::new();
    for _ in 0..50 {
        let t = Instant::now();
        let r = store.query_full("amenity", "cafe", Some(bb));
        bq.push(ms(t));
        assert_eq!(r.len(), downtown.len());
    }
    eprintln!("[get] way {} ({} refs)", st.get_way, st.get_way_refs);
    let mut gq = Vec::new();
    for _ in 0..200 {
        let t = Instant::now();
        let o = store.get_way(st.get_way).unwrap();
        gq.push(ms(t));
        if let Obj::Way { refs, .. } = o {
            assert_eq!(refs.len(), st.get_way_refs);
        }
    }
    let mut table_mb = serde_json::Map::new();
    if let Ok(mut s) = store.conn.prepare("SELECT name, SUM(pgsize) FROM dbstat GROUP BY name ORDER BY 2 DESC") {
        let mut rows = s.query([]).unwrap();
        while let Some(r) = rows.next().unwrap() {
            let n: String = r.get(0).unwrap();
            let b: i64 = r.get(1).unwrap();
            table_mb.insert(n, json!((b as f64 / 1048576.0 * 100.0).round() / 100.0));
        }
    }
    verify(pbf, &store);
    let line = json!({
        "variant": "B",
        "pbf_bytes": std::fs::metadata(pbf).unwrap().len(),
        "db_bytes": std::fs::metadata(out).unwrap().len(),
        "nodes": nodes, "ways": ways, "relations": relations,
        "import_ms": import_ms,
        "hwm_after_import_bytes": hwm_import,
        "cafes": cafes.len(),
        "downtown_cafes": downtown.len(),
        "tag_query_cold_ms": cold,
        "tag_query_p50_ms": pct(&mut tq, 0.5),
        "tag_query_p95_ms": pct(&mut tq, 0.95),
        "bbox_query_p50_ms": pct(&mut bq, 0.5),
        "bbox_query_p95_ms": pct(&mut bq, 0.95),
        "get_p50_ms": pct(&mut gq, 0.5),
        "get_p95_ms": pct(&mut gq, 0.95),
        "table_mb": table_mb,
        "hwm_final_bytes": hwm_bytes(),
    });
    println!("{line}");
}
