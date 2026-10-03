//! Desktop driver for the sans-IO basemap extract: plays the platform's role
//! (HTTP range requests) with `ureq`, a dev-dependency that never ships.
//!
//! cargo run --release --example basemap_extract -- URL OUT.pmtiles west south east north maxzoom
//!
//! Requests of one step run in parallel (4 at a time, like go-pmtiles'
//! default download threads); tile responses are written as they arrive, in
//! completion order, which exercises the assembler's any-order contract.

use std::sync::{Mutex, mpsc};
use std::time::{Duration, Instant};

use osm_framework::basemap::{BBox, ByteRange, ExtractPlan, Step};

const THREADS: usize = 4;

type Fail = Box<dyn std::error::Error + Send + Sync>;

fn get_range(agent: &ureq::Agent, url: &str, r: ByteRange) -> Result<Vec<u8>, Fail> {
    let resp = agent
        .get(url)
        .header(
            "Range",
            format!("bytes={}-{}", r.offset, r.offset + r.length - 1),
        )
        .call()?;
    if resp.status() != 206 {
        return Err(format!(
            "range {r:?}: HTTP {} (need 206 Partial Content)",
            resp.status()
        )
        .into());
    }
    // One byte over the request lets the engine notice an oversized body.
    Ok(resp
        .into_body()
        .into_with_config()
        .limit(r.length + 1)
        .read_to_vec()?)
}

/// Fetches `ranges` with `THREADS` workers and hands each body to `sink` on
/// this thread as it completes. The channel holds at most `THREADS` bodies, so
/// memory stays bounded by a few ranges.
fn fetch_all(
    agent: &ureq::Agent,
    url: &str,
    ranges: Vec<ByteRange>,
    mut sink: impl FnMut(ByteRange, Vec<u8>) -> Result<(), Fail>,
) -> Result<(), Fail> {
    let queue = Mutex::new(ranges);
    let (tx, rx) = mpsc::sync_channel::<(ByteRange, Result<Vec<u8>, Fail>)>(THREADS);
    std::thread::scope(|s| {
        for _ in 0..THREADS {
            let tx = tx.clone();
            let queue = &queue;
            s.spawn(move || {
                loop {
                    let Some(r) = queue.lock().unwrap().pop() else {
                        break;
                    };
                    if tx.send((r, get_range(agent, url, r))).is_err() {
                        break;
                    }
                }
            });
        }
        drop(tx);
        let mut result = Ok(());
        for (r, body) in rx {
            if result.is_ok() {
                result = body.and_then(|b| sink(r, b));
                if result.is_err() {
                    queue.lock().unwrap().clear();
                }
            }
        }
        result
    })
}

fn main() -> Result<(), Fail> {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let [url, out, w, s, e, n, maxzoom] = args.as_slice() else {
        eprintln!("usage: basemap_extract URL OUT.pmtiles west south east north maxzoom");
        std::process::exit(2);
    };
    let bbox = BBox::new(w.parse()?, s.parse()?, e.parse()?, n.parse()?);
    let agent: ureq::Agent = ureq::Agent::config_builder()
        .timeout_global(Some(Duration::from_secs(120)))
        .http_status_as_error(false)
        .build()
        .into();
    let started = Instant::now();
    let mut requests = 0usize;
    let mut bytes = 0u64;

    // Directory phase: one batch per step.
    let mut plan = ExtractPlan::new(bbox, None, Some(maxzoom.parse()?), 0.05)?;
    let mut batch = vec![plan.first_request()];
    let tiles = loop {
        let mut next = Vec::new();
        let mut ready = None;
        requests += batch.len();
        fetch_all(&agent, url, std::mem::take(&mut batch), |r, body| {
            bytes += body.len() as u64;
            match plan.feed(r.id, &body)? {
                Step::Fetch(more) => next.extend(more),
                Step::Wait => {}
                Step::TilesReady(t) => ready = Some(t),
            }
            Ok(())
        })?;
        if let Some(t) = ready {
            break t;
        }
        batch = next;
    };
    let dir_time = started.elapsed();
    eprintln!(
        "directories: {requests} requests, {bytes} B in {dir_time:?}; tiles: {} requests, {} B to transfer for {} B of tile data",
        tiles.requests.len(),
        tiles.transfer_bytes,
        tiles.tile_data_bytes
    );

    // Tile phase.
    let staging = format!("{out}.part");
    let mut asm = plan.into_assembler(&staging)?;
    requests += tiles.requests.len();
    fetch_all(&agent, url, asm.remaining(), |r, body| {
        bytes += body.len() as u64;
        asm.write_range(r.id, &body)?;
        Ok(())
    })?;
    asm.finish(out)?;
    let size = std::fs::metadata(out)?.len();
    println!(
        "{{\"requests\":{requests},\"bytes_transferred\":{bytes},\"addressed_tiles\":{},\"tile_entries\":{},\"file_bytes\":{size},\"seconds\":{:.2}}}",
        tiles.addressed_tiles,
        tiles.tile_entries,
        started.elapsed().as_secs_f64()
    );
    Ok(())
}
