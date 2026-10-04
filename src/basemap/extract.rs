//! The planning half of an extract: a sans-IO state machine that says which
//! byte ranges of the source archive to fetch and, once all directories are
//! in, which ranges of tile data the output needs.
//!
//! ```text
//! new() ──first_request()──▶ [header + root, 16 KiB]
//!   feed(header bytes) ──▶ Fetch([leaf dirs…, metadata?, root?])   (0..n rounds)
//!   feed(each range)   ──▶ Wait | Fetch(deeper leaves) | TilesReady(plan)
//! into_assembler(staging) ──▶ Assembler (see assemble.rs)
//! ```
//!
//! The caller performs every request (any order, any concurrency) and feeds
//! each response back with its range id. The plan never touches the network
//! or the file system and holds only directories and metadata in memory, which
//! scale with the number of tiles kept (tens of bytes per tile), never with
//! the tile data.
//!
//! Failure behaviour: feeding an unknown id or a response of the wrong length
//! is rejected without changing state, so the caller can retry that request.
//! Any other error (malformed or unsupported source archive) is fatal for the
//! plan: drop it and start over. Nothing is written anywhere in this phase.

use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};

use super::assemble::Assembler;
use super::cover::{BBox, TileCover};
use super::format::{
    Compression, Entry, FIRST_FETCH_LEN, HEADER_LEN, Header, build_directories, decode_directory,
};
use super::ranges::{MergedRange, SrcDst, merge_ranges, reencode};
use super::tile_id::{MAX_ZOOM, zoom_start};
use crate::{Error, Result};

/// One HTTP range request: bytes `offset..offset + length` of the source
/// archive (`Range: bytes=offset-(offset+length-1)`). `id` is unique within
/// one plan and is how the response is fed back.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct ByteRange {
    pub id: u64,
    pub offset: u64,
    pub length: u64,
}

/// What the caller does next after `feed`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Step {
    /// Perform these requests (in addition to any still outstanding) and feed
    /// each response.
    Fetch(Vec<ByteRange>),
    /// Nothing new; keep feeding the outstanding responses.
    Wait,
    /// All directories are known. Create the assembler and fetch the tiles.
    TilesReady(TilePlan),
}

/// The tile download phase, for the caller to schedule and show progress.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct TilePlan {
    /// Merged tile data requests, largest first.
    pub requests: Vec<ByteRange>,
    /// Bytes the requests transfer, including overfetched gaps.
    pub transfer_bytes: u64,
    /// Size of the output tile section (wanted bytes, deduplicated).
    pub tile_data_bytes: u64,
    /// Size of the finished archive.
    pub archive_bytes: u64,
    /// Directory entries in the output (go-pmtiles "tile entries").
    pub tile_entries: u64,
    /// Tiles a reader can address (run lengths summed).
    pub addressed_tiles: u64,
    /// Distinct tile contents.
    pub tile_contents: u64,
    /// Tiles in the relevance set (bbox cover); more than `addressed_tiles`
    /// where the source has no tile (e.g. empty sea at high zoom).
    pub cover_tiles: u64,
    /// Requests issued before the tile phase (header/root, leaves, metadata).
    pub directory_requests: u64,
    /// Bytes transferred before the tile phase.
    pub directory_bytes: u64,
    pub min_zoom: u8,
    pub max_zoom: u8,
}

/// What a pending request is for.
#[derive(Debug, Clone)]
enum Purpose {
    /// The first 16 KiB: header and (normally) root directory and metadata.
    Header,
    /// Root directory outside the first 16 KiB (spec violation, tolerated).
    Root,
    Metadata,
    /// Leaf directories laid out as wanted/discard parts (merged request).
    Leaves(Vec<super::ranges::CopyDiscard>),
}

/// Everything needed to write the output, fixed before tiles are fetched.
#[derive(Debug)]
pub(crate) struct Finalized {
    pub header: Header,
    pub root: Vec<u8>,
    pub metadata: Vec<u8>,
    pub leaves: Vec<u8>,
    /// Tile requests by id, with their parts; offsets relative to the source
    /// tile section (`src`) and the output tile section (`dst`).
    pub tile_ranges: BTreeMap<u64, MergedRange>,
    pub plan: TilePlan,
}

/// Planning state for one extract. Owned; drive it with `first_request` and
/// `feed`, then turn it into an `Assembler`.
#[derive(Debug)]
pub struct ExtractPlan {
    bbox: BBox,
    req_min_zoom: Option<u8>,
    req_max_zoom: Option<u8>,
    overfetch: f32,
    /// Source header once known; output header is derived from it.
    source: Option<Header>,
    cover: Option<TileCover>,
    pending: BTreeMap<u64, (ByteRange, Purpose)>,
    next_id: u64,
    tiles: Vec<Entry>,
    metadata: Option<Vec<u8>>,
    directory_requests: u64,
    directory_bytes: u64,
    finalized: Option<Finalized>,
}

impl ExtractPlan {
    /// `min_zoom`/`max_zoom`: `None` takes the source archive's; requested
    /// zooms are clamped to the source's range (as go-pmtiles does).
    /// `overfetch`: extra bytes allowed per wanted byte to save requests;
    /// go-pmtiles defaults to 0.05.
    pub fn new(
        bbox: BBox,
        min_zoom: Option<u8>,
        max_zoom: Option<u8>,
        overfetch: f32,
    ) -> Result<Self> {
        bbox.validate()?;
        if !(overfetch.is_finite() && overfetch >= 0.0) {
            return Err(Error::Invalid(
                "overfetch must be a non-negative number".into(),
            ));
        }
        if let (Some(a), Some(b)) = (min_zoom, max_zoom)
            && a > b
        {
            return Err(Error::Invalid(
                "min_zoom cannot be greater than max_zoom".into(),
            ));
        }
        let first = ByteRange {
            id: 0,
            offset: 0,
            length: FIRST_FETCH_LEN,
        };
        let mut pending = BTreeMap::new();
        pending.insert(0, (first, Purpose::Header));
        Ok(Self {
            bbox,
            req_min_zoom: min_zoom,
            req_max_zoom: max_zoom,
            overfetch,
            source: None,
            cover: None,
            pending,
            next_id: 1,
            tiles: Vec::new(),
            metadata: None,
            directory_requests: 1,
            directory_bytes: 0,
            finalized: None,
        })
    }

    /// The first request: the first 16 KiB, holding header and root directory
    /// (and, in Protomaps builds, the metadata). A server may return fewer
    /// bytes if the archive is smaller; feed whatever arrived.
    pub fn first_request(&self) -> ByteRange {
        ByteRange {
            id: 0,
            offset: 0,
            length: FIRST_FETCH_LEN,
        }
    }

    /// Requests issued and not yet fed.
    pub fn outstanding(&self) -> Vec<ByteRange> {
        self.pending.values().map(|(r, _)| *r).collect()
    }

    /// Feeds the response for request `id`. `bytes` must be exactly the
    /// requested length (the first request may be shorter, see
    /// `first_request`). A wrong id or length is rejected without changing
    /// state.
    pub fn feed(&mut self, id: u64, bytes: &[u8]) -> Result<Step> {
        if self.finalized.is_some() {
            return Err(Error::Invalid(
                "extract plan already has its tile plan".into(),
            ));
        }
        let (range, purpose) = self
            .pending
            .get(&id)
            .cloned()
            .ok_or_else(|| Error::Invalid(format!("no outstanding request with id {id}")))?;
        let len = bytes.len() as u64;
        let ok = match purpose {
            Purpose::Header => len >= HEADER_LEN as u64 && len <= range.length,
            _ => len == range.length,
        };
        if !ok {
            return Err(Error::Invalid(format!(
                "response for request {id} has {len} bytes, expected {}",
                range.length
            )));
        }
        let mut fetch = Vec::new();
        match purpose {
            Purpose::Header => self.on_header(bytes, &mut fetch)?,
            Purpose::Root => {
                let source = self.source.clone().expect("header precedes root");
                let dir = decode_directory(bytes, source.internal_compression)?;
                self.on_directory(&dir, &mut fetch)?;
            }
            Purpose::Metadata => self.metadata = Some(bytes.to_vec()),
            Purpose::Leaves(parts) => {
                let compression = self
                    .source
                    .as_ref()
                    .expect("header known")
                    .internal_compression;
                let mut at = 0usize;
                for part in parts {
                    let end = at + part.wanted as usize;
                    let dir = decode_directory(&bytes[at..end], compression)?;
                    self.on_directory(&dir, &mut fetch)?;
                    at = end + part.discard as usize;
                }
            }
        }
        self.pending.remove(&id);
        self.directory_bytes += len;
        self.directory_requests += fetch.len() as u64;
        if !fetch.is_empty() {
            return Ok(Step::Fetch(fetch));
        }
        if !self.pending.is_empty() {
            return Ok(Step::Wait);
        }
        self.finalize()?;
        Ok(Step::TilesReady(
            self.finalized
                .as_ref()
                .expect("just finalized")
                .plan
                .clone(),
        ))
    }

    fn issue(&mut self, offset: u64, length: u64, purpose: Purpose, out: &mut Vec<ByteRange>) {
        let range = ByteRange {
            id: self.next_id,
            offset,
            length,
        };
        self.next_id += 1;
        self.pending.insert(range.id, (range, purpose));
        out.push(range);
    }

    fn on_header(&mut self, bytes: &[u8], fetch: &mut Vec<ByteRange>) -> Result<()> {
        let source = Header::parse(bytes)?;
        if !source.clustered {
            // A property of the source file, not of the request.
            return Err(Error::Format(
                "source archive must be clustered for extracts".into(),
            ));
        }
        source.internal_compression.check_internal()?;
        if source.max_zoom > MAX_ZOOM || source.min_zoom > source.max_zoom {
            return Err(Error::Corrupt(
                "source header has an invalid zoom range".into(),
            ));
        }
        let min_zoom = self
            .req_min_zoom
            .map_or(source.min_zoom, |z| z.max(source.min_zoom));
        let max_zoom = self
            .req_max_zoom
            .map_or(source.max_zoom, |z| z.min(source.max_zoom));
        if min_zoom > max_zoom {
            return Err(Error::Invalid(format!(
                "requested zooms do not overlap the archive's {}..={}",
                source.min_zoom, source.max_zoom
            )));
        }
        self.cover = Some(TileCover::new(&self.bbox, min_zoom, max_zoom)?);
        self.source = Some(source.clone());

        let have = bytes.len() as u64;
        let within =
            |offset: u64, length: u64| offset.checked_add(length).is_some_and(|e| e <= have);
        if within(source.metadata_offset, source.metadata_length) {
            let at = source.metadata_offset as usize;
            self.metadata = Some(bytes[at..at + source.metadata_length as usize].to_vec());
        } else {
            self.issue(
                source.metadata_offset,
                source.metadata_length,
                Purpose::Metadata,
                fetch,
            );
        }
        if within(source.root_offset, source.root_length) {
            let at = source.root_offset as usize;
            let root = &bytes[at..at + source.root_length as usize];
            let dir = decode_directory(root, source.internal_compression)?;
            self.on_directory(&dir, fetch)?;
        } else {
            self.issue(source.root_offset, source.root_length, Purpose::Root, fetch);
        }
        Ok(())
    }

    /// go-pmtiles `RelevantEntries`: keeps the tile entries in the cover
    /// (splitting run-length entries down to the covered sub-runs, which keep
    /// the run's offset so `reencode` stores the data once) and requests the
    /// leaf directories whose id range touches the cover. Leaves found inside
    /// leaves are requested the same way (go-pmtiles panics on those).
    fn on_directory(&mut self, dir: &[Entry], fetch: &mut Vec<ByteRange>) -> Result<()> {
        let cover = self.cover.as_ref().expect("cover built with header");
        let source = self.source.as_ref().expect("header known");
        // A leaf's ids end where the next entry begins; the last one is
        // bounded by the end of the cover's zooms.
        let last_tile = zoom_start(cover.max_zoom() + 1);
        let mut leaves: Vec<SrcDst> = Vec::new();
        for (i, e) in dir.iter().enumerate() {
            match e.run_length {
                0 => {
                    let end = dir.get(i + 1).map_or(last_tile, |n| n.tile_id);
                    if e.tile_id < end && cover.intersects_range(e.tile_id, end) {
                        let src = source
                            .leaf_directory_offset
                            .checked_add(e.offset)
                            .ok_or_else(|| {
                                Error::Corrupt("leaf directory offset overflows".into())
                            })?;
                        leaves.push(SrcDst {
                            src,
                            dst: 0,
                            length: u64::from(e.length),
                        });
                    }
                }
                1 => {
                    if cover.contains(e.tile_id) {
                        self.tiles.push(*e);
                    }
                }
                n => {
                    let mut run: Option<Entry> = None;
                    for id in e.tile_id..e.tile_id.saturating_add(u64::from(n)) {
                        if cover.contains(id) {
                            match run.as_mut() {
                                Some(r) => r.run_length += 1,
                                None => {
                                    run = Some(Entry {
                                        tile_id: id,
                                        run_length: 1,
                                        ..*e
                                    })
                                }
                            }
                        } else if let Some(r) = run.take() {
                            self.tiles.push(r);
                        }
                    }
                    self.tiles.extend(run);
                }
            }
        }
        // Leaves of one directory are merged like tile data: neighbours are
        // fetched in one request when the gap fits the overfetch budget.
        let (merged, _) = merge_ranges(&leaves, self.overfetch);
        for m in merged {
            self.issue(m.src, m.length, Purpose::Leaves(m.parts), fetch);
        }
        Ok(())
    }

    /// All directories are in: sort and re-encode the kept entries, merge the
    /// tile copies into requests, build the output directories and header.
    fn finalize(&mut self) -> Result<()> {
        let source = self.source.clone().expect("header known");
        let cover = self.cover.as_ref().expect("cover known");
        let metadata = self
            .metadata
            .take()
            .ok_or_else(|| Error::Corrupt("metadata was never received".into()))?;
        // Directories are sorted, but leaves may be fed in any order.
        self.tiles.sort_by_key(|e| e.tile_id);
        let re = reencode(&self.tiles);
        let (merged, transfer) = merge_ranges(&re.copies, self.overfetch);
        // Directories use the source's internal compression so they agree
        // with the copied metadata (go-pmtiles always writes gzip here, which
        // is wrong for an uncompressed source; harmless for real archives).
        let compression: Compression = source.internal_compression;
        let (root, leaves) = build_directories(
            &re.entries,
            FIRST_FETCH_LEN as usize - HEADER_LEN,
            compression,
        )?;

        let mut h = source.clone();
        let b = self.bbox;
        // Bounds are the requested bbox; across the antimeridian the bbox is
        // two polygons whose bound spans the world (go-pmtiles' behaviour).
        let (west, east) = if b.west > b.east {
            (-180.0, 180.0)
        } else {
            (b.west, b.east)
        };
        let mut center_lon = (b.west + b.east) / 2.0;
        if b.west > b.east {
            center_lon = (b.west + b.east + 360.0) / 2.0;
            if center_lon > 180.0 {
                center_lon -= 360.0;
            }
        }
        // `as i32` truncates toward zero, matching Go's int32(float64).
        h.min_lon_e7 = (west * 1e7) as i32;
        h.min_lat_e7 = (b.south * 1e7) as i32;
        h.max_lon_e7 = (east * 1e7) as i32;
        h.max_lat_e7 = (b.north * 1e7) as i32;
        h.center_lon_e7 = (center_lon * 1e7) as i32;
        h.center_lat_e7 = ((b.south + b.north) / 2.0 * 1e7) as i32;
        h.min_zoom = cover.min_zoom();
        h.max_zoom = cover.max_zoom();
        h.center_zoom = h.center_zoom.clamp(h.min_zoom, h.max_zoom);
        h.clustered = true;
        h.root_offset = HEADER_LEN as u64;
        h.root_length = root.len() as u64;
        h.metadata_offset = h.root_offset + h.root_length;
        h.metadata_length = metadata.len() as u64;
        h.leaf_directory_offset = h.metadata_offset + h.metadata_length;
        h.leaf_directory_length = leaves.len() as u64;
        h.tile_data_offset = h.leaf_directory_offset + h.leaf_directory_length;
        h.tile_data_length = re.tile_data_length;
        h.addressed_tiles_count = re.addressed_tiles;
        h.tile_entries_count = re.entries.len() as u64;
        h.tile_contents_count = re.tile_contents;

        let mut tile_ranges = BTreeMap::new();
        let mut requests = Vec::with_capacity(merged.len());
        for m in merged {
            let range = ByteRange {
                id: self.next_id,
                offset: source
                    .tile_data_offset
                    .checked_add(m.src)
                    .ok_or_else(|| Error::Corrupt("tile offset overflows".into()))?,
                length: m.length,
            };
            self.next_id += 1;
            requests.push(range);
            tile_ranges.insert(range.id, m);
        }
        let plan = TilePlan {
            requests,
            transfer_bytes: transfer,
            tile_data_bytes: re.tile_data_length,
            archive_bytes: h.tile_data_offset + h.tile_data_length,
            tile_entries: h.tile_entries_count,
            addressed_tiles: h.addressed_tiles_count,
            tile_contents: h.tile_contents_count,
            cover_tiles: cover.count(),
            directory_requests: self.directory_requests,
            directory_bytes: self.directory_bytes,
            min_zoom: h.min_zoom,
            max_zoom: h.max_zoom,
        };
        self.tiles = Vec::new();
        self.finalized = Some(Finalized {
            header: h,
            root,
            metadata,
            leaves,
            tile_ranges,
            plan,
        });
        Ok(())
    }

    /// The tile plan once `feed` returned `TilesReady`.
    pub fn tile_plan(&self) -> Option<&TilePlan> {
        self.finalized.as_ref().map(|f| &f.plan)
    }

    /// Starts the download phase: creates `staging_path` (truncating any
    /// leftover) laid out as the final archive minus a valid header. See
    /// `Assembler` for the atomicity guarantees. `staging_path` must be on
    /// the same file system as the eventual output (rename).
    pub fn into_assembler(self, staging_path: impl AsRef<std::path::Path>) -> Result<Assembler> {
        let finalized = self.finalized.ok_or_else(|| {
            Error::Invalid("extract plan is not ready: feed until TilesReady".into())
        })?;
        Assembler::create(finalized, staging_path.as_ref())
    }
}
