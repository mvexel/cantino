//! PMTiles v3 binary layout: the fixed 127-byte header, varint-encoded
//! directories and their internal compression.
//!
//! Archive layout (spec v3, sections in this order in files we write):
//!
//! ```text
//! [header 127 B][root directory][JSON metadata][leaf directories][tile data]
//! ```
//!
//! The header and the root directory must lie within the first 16 384 bytes,
//! so one range request fetches both. Every section's offset and length is in
//! the header; readers never assume the order above.
//!
//! A directory is a list of entries sorted by tile id. An entry with
//! `run_length >= 1` addresses tile data: `run_length` consecutive tile ids
//! all share the bytes at `offset..offset + length` (relative to the tile
//! data section). An entry with `run_length == 0` points at a leaf directory
//! (offset relative to the leaf directory section) that covers ids from its
//! `tile_id` up to the next entry's `tile_id`. Serialized directories are
//! column-oriented varints, then compressed with the archive's internal
//! compression (see `encode_directory`).
//!
//! Hand-rolled instead of using the `pmtiles` crate: the format is small, and
//! the crate's reader side is built around an async runtime.

use std::io::{Read, Write};

use crate::{Error, Result};

pub const HEADER_LEN: usize = 127;
/// Bytes a reader fetches first; the spec guarantees header + root fit.
pub const FIRST_FETCH_LEN: u64 = 16_384;
/// Cap on a decompressed directory or metadata blob. A real directory with
/// tens of thousands of entries is well under 1 MiB; anything larger than this
/// is treated as damage (or a decompression bomb) rather than allocated.
const MAX_INFLATED_LEN: u64 = 64 << 20;

/// Compression codes shared by the header's internal- and tile-compression
/// fields. Only `None` and `Gzip` are supported for directories/metadata
/// (the only internal compressions known writers produce); tile compression
/// is copied through untouched, so any value is fine there.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Compression {
    Unknown,
    None,
    Gzip,
    Brotli,
    Zstd,
    Other(u8),
}

impl Compression {
    pub fn from_byte(b: u8) -> Self {
        match b {
            0 => Self::Unknown,
            1 => Self::None,
            2 => Self::Gzip,
            3 => Self::Brotli,
            4 => Self::Zstd,
            other => Self::Other(other),
        }
    }

    pub fn to_byte(self) -> u8 {
        match self {
            Self::Unknown => 0,
            Self::None => 1,
            Self::Gzip => 2,
            Self::Brotli => 3,
            Self::Zstd => 4,
            Self::Other(b) => b,
        }
    }

    /// Fails clearly for internal compressions this crate cannot (de)code.
    pub fn check_internal(self) -> Result<()> {
        match self {
            Self::None | Self::Gzip => Ok(()),
            other => Err(Error::Invalid(format!(
                "unsupported PMTiles internal compression {other:?} (only none and gzip are supported)"
            ))),
        }
    }
}

/// The fixed header. Field names follow the spec; offsets are absolute file
/// offsets, lengths in bytes. Bounds and center are degrees * 10^7.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Header {
    pub spec_version: u8,
    pub root_offset: u64,
    pub root_length: u64,
    pub metadata_offset: u64,
    pub metadata_length: u64,
    pub leaf_directory_offset: u64,
    pub leaf_directory_length: u64,
    pub tile_data_offset: u64,
    pub tile_data_length: u64,
    pub addressed_tiles_count: u64,
    pub tile_entries_count: u64,
    pub tile_contents_count: u64,
    /// Tile data is laid out in tile-id order (deduplicated contents may point
    /// backwards). Extracting requires it: it keeps wanted tiles near each
    /// other, so few range requests cover them.
    pub clustered: bool,
    pub internal_compression: Compression,
    pub tile_compression: Compression,
    pub tile_type: u8,
    pub min_zoom: u8,
    pub max_zoom: u8,
    pub min_lon_e7: i32,
    pub min_lat_e7: i32,
    pub max_lon_e7: i32,
    pub max_lat_e7: i32,
    pub center_zoom: u8,
    pub center_lon_e7: i32,
    pub center_lat_e7: i32,
}

fn u64_at(b: &[u8], at: usize) -> u64 {
    u64::from_le_bytes(b[at..at + 8].try_into().expect("8 bytes"))
}
fn i32_at(b: &[u8], at: usize) -> i32 {
    i32::from_le_bytes(b[at..at + 4].try_into().expect("4 bytes"))
}

impl Header {
    /// Parses the first 127 bytes of an archive. Rejects anything that is not
    /// PMTiles v3 (v1/v2 have a different layout entirely).
    pub fn parse(b: &[u8]) -> Result<Self> {
        if b.len() < HEADER_LEN {
            return Err(Error::Corrupt(format!(
                "PMTiles header needs {HEADER_LEN} bytes, got {}",
                b.len()
            )));
        }
        if &b[0..7] != b"PMTiles" {
            return Err(Error::Corrupt(
                "not a PMTiles archive (magic number missing)".into(),
            ));
        }
        if b[7] != 3 {
            return Err(Error::Corrupt(format!(
                "PMTiles spec version {} is not supported (need 3)",
                b[7]
            )));
        }
        Ok(Self {
            spec_version: b[7],
            root_offset: u64_at(b, 8),
            root_length: u64_at(b, 16),
            metadata_offset: u64_at(b, 24),
            metadata_length: u64_at(b, 32),
            leaf_directory_offset: u64_at(b, 40),
            leaf_directory_length: u64_at(b, 48),
            tile_data_offset: u64_at(b, 56),
            tile_data_length: u64_at(b, 64),
            addressed_tiles_count: u64_at(b, 72),
            tile_entries_count: u64_at(b, 80),
            tile_contents_count: u64_at(b, 88),
            clustered: b[96] == 1,
            internal_compression: Compression::from_byte(b[97]),
            tile_compression: Compression::from_byte(b[98]),
            tile_type: b[99],
            min_zoom: b[100],
            max_zoom: b[101],
            min_lon_e7: i32_at(b, 102),
            min_lat_e7: i32_at(b, 106),
            max_lon_e7: i32_at(b, 110),
            max_lat_e7: i32_at(b, 114),
            center_zoom: b[118],
            center_lon_e7: i32_at(b, 119),
            center_lat_e7: i32_at(b, 123),
        })
    }

    pub fn to_bytes(&self) -> [u8; HEADER_LEN] {
        let mut b = [0u8; HEADER_LEN];
        b[0..7].copy_from_slice(b"PMTiles");
        b[7] = 3;
        for (at, v) in [
            (8, self.root_offset),
            (16, self.root_length),
            (24, self.metadata_offset),
            (32, self.metadata_length),
            (40, self.leaf_directory_offset),
            (48, self.leaf_directory_length),
            (56, self.tile_data_offset),
            (64, self.tile_data_length),
            (72, self.addressed_tiles_count),
            (80, self.tile_entries_count),
            (88, self.tile_contents_count),
        ] {
            b[at..at + 8].copy_from_slice(&v.to_le_bytes());
        }
        b[96] = u8::from(self.clustered);
        b[97] = self.internal_compression.to_byte();
        b[98] = self.tile_compression.to_byte();
        b[99] = self.tile_type;
        b[100] = self.min_zoom;
        b[101] = self.max_zoom;
        for (at, v) in [
            (102, self.min_lon_e7),
            (106, self.min_lat_e7),
            (110, self.max_lon_e7),
            (114, self.max_lat_e7),
        ] {
            b[at..at + 4].copy_from_slice(&v.to_le_bytes());
        }
        b[118] = self.center_zoom;
        b[119..123].copy_from_slice(&self.center_lon_e7.to_le_bytes());
        b[123..127].copy_from_slice(&self.center_lat_e7.to_le_bytes());
        b
    }
}

/// One directory entry (see the module docs for the two kinds).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Entry {
    pub tile_id: u64,
    pub offset: u64,
    pub length: u32,
    pub run_length: u32,
}

/// Unsigned LEB128 varint, as used by the directory encoding.
pub fn write_varint(out: &mut Vec<u8>, mut v: u64) {
    while v >= 0x80 {
        out.push((v as u8) | 0x80);
        v >>= 7;
    }
    out.push(v as u8);
}

/// Reads one varint at `*pos`, advancing it. Errors on truncation or on a
/// value that does not fit in u64.
pub fn read_varint(b: &[u8], pos: &mut usize) -> Result<u64> {
    let mut v = 0u64;
    for shift in (0..64).step_by(7) {
        let byte = *b
            .get(*pos)
            .ok_or_else(|| Error::Corrupt("truncated PMTiles directory varint".into()))?;
        *pos += 1;
        if shift == 63 && byte > 1 {
            break;
        }
        v |= u64::from(byte & 0x7f) << shift;
        if byte & 0x80 == 0 {
            return Ok(v);
        }
    }
    Err(Error::Corrupt(
        "PMTiles directory varint overflows u64".into(),
    ))
}

/// Undoes internal compression (directories, metadata).
pub fn decompress(data: &[u8], compression: Compression) -> Result<Vec<u8>> {
    compression.check_internal()?;
    if compression == Compression::None {
        return Ok(data.to_vec());
    }
    let mut out = Vec::new();
    flate2::read::GzDecoder::new(data)
        .take(MAX_INFLATED_LEN + 1)
        .read_to_end(&mut out)
        .map_err(|e| Error::Corrupt(format!("PMTiles gzip section: {e}")))?;
    if out.len() as u64 > MAX_INFLATED_LEN {
        return Err(Error::Corrupt(
            "PMTiles directory inflates beyond 64 MiB".into(),
        ));
    }
    Ok(out)
}

fn compress(data: &[u8], compression: Compression) -> Result<Vec<u8>> {
    compression.check_internal()?;
    if compression == Compression::None {
        return Ok(data.to_vec());
    }
    // Best compression, like go-pmtiles: directories are tiny and fetched by
    // every reader, so the CPU is well spent.
    let mut enc = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::best());
    enc.write_all(data)?;
    Ok(enc.finish()?)
}

/// Serializes and compresses a directory. Layout (all varints): entry count;
/// tile ids delta-encoded; run lengths; lengths; offsets, where 0 means
/// "directly after the previous entry's data" and anything else is
/// `offset + 1`. The column layout and the 0-shortcut make clustered
/// directories compress very well.
pub fn encode_directory(entries: &[Entry], compression: Compression) -> Result<Vec<u8>> {
    let mut b = Vec::with_capacity(entries.len() * 6 + 8);
    write_varint(&mut b, entries.len() as u64);
    let mut last_id = 0u64;
    for e in entries {
        write_varint(&mut b, e.tile_id - last_id);
        last_id = e.tile_id;
    }
    for e in entries {
        write_varint(&mut b, u64::from(e.run_length));
    }
    for e in entries {
        write_varint(&mut b, u64::from(e.length));
    }
    for (i, e) in entries.iter().enumerate() {
        if i > 0 && e.offset == entries[i - 1].offset + u64::from(entries[i - 1].length) {
            write_varint(&mut b, 0);
        } else {
            write_varint(&mut b, e.offset + 1);
        }
    }
    compress(&b, compression)
}

/// Decompresses and parses a directory. Never panics on hostile input: every
/// malformed case is `Error::Corrupt`.
pub fn decode_directory(data: &[u8], compression: Compression) -> Result<Vec<Entry>> {
    let b = decompress(data, compression)?;
    let mut pos = 0;
    let count = read_varint(&b, &mut pos)?;
    // Each entry needs at least four varint bytes; a larger count is damage,
    // and checking first avoids a huge allocation.
    if count > (b.len() as u64) / 4 + 1 {
        return Err(Error::Corrupt(format!(
            "PMTiles directory claims {count} entries"
        )));
    }
    let count = count as usize;
    let mut entries = Vec::with_capacity(count);
    let mut last_id = 0u64;
    for _ in 0..count {
        last_id = last_id
            .checked_add(read_varint(&b, &mut pos)?)
            .ok_or_else(|| Error::Corrupt("PMTiles tile id overflow".into()))?;
        entries.push(Entry {
            tile_id: last_id,
            offset: 0,
            length: 0,
            run_length: 0,
        });
    }
    let to_u32 = |v: u64| {
        u32::try_from(v).map_err(|_| Error::Corrupt("PMTiles directory value exceeds u32".into()))
    };
    for e in entries.iter_mut() {
        e.run_length = to_u32(read_varint(&b, &mut pos)?)?;
    }
    for e in entries.iter_mut() {
        e.length = to_u32(read_varint(&b, &mut pos)?)?;
    }
    for i in 0..count {
        let v = read_varint(&b, &mut pos)?;
        entries[i].offset = if v == 0 {
            if i == 0 {
                return Err(Error::Corrupt(
                    "first PMTiles directory offset is relative".into(),
                ));
            }
            entries[i - 1].offset + u64::from(entries[i - 1].length)
        } else {
            v - 1
        };
    }
    Ok(entries)
}

/// Root and leaf directory bytes for a sorted list of tile entries, so that
/// the root fits in `target_root_len` (16 384 - 127 when the root must share
/// the first fetch with the header). Port of go-pmtiles `BuildDirectories`:
///
/// 1. fewer than 16 384 entries and the whole directory fits: root only;
/// 2. otherwise a root of leaf pointers only, with leaves of `leaf_size`
///    entries (at least 4096, about entries/3500), growing leaf_size by 20%
///    until the root fits.
///
/// Leaves are concatenated in tile-id order; root entries point at them with
/// offsets relative to the leaf section. Returns `(root, leaves)`.
pub fn build_directories(
    entries: &[Entry],
    target_root_len: usize,
    compression: Compression,
) -> Result<(Vec<u8>, Vec<u8>)> {
    if entries.len() < 16_384 {
        let root = encode_directory(entries, compression)?;
        if root.len() <= target_root_len {
            return Ok((root, Vec::new()));
        }
    }
    let mut leaf_size = (entries.len() as f32 / 3500.0).max(4096.0);
    loop {
        let mut roots = Vec::new();
        let mut leaves = Vec::new();
        for chunk in entries.chunks(leaf_size as usize) {
            let bytes = encode_directory(chunk, compression)?;
            roots.push(Entry {
                tile_id: chunk[0].tile_id,
                offset: leaves.len() as u64,
                length: bytes.len() as u32,
                run_length: 0,
            });
            leaves.extend_from_slice(&bytes);
        }
        let root = encode_directory(&roots, compression)?;
        if root.len() <= target_root_len {
            return Ok((root, leaves));
        }
        leaf_size *= 1.2;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn varint_round_trip() {
        for v in [
            0u64,
            1,
            127,
            128,
            300,
            16_383,
            16_384,
            u32::MAX as u64,
            u64::MAX,
        ] {
            let mut b = Vec::new();
            write_varint(&mut b, v);
            let mut pos = 0;
            assert_eq!(read_varint(&b, &mut pos).unwrap(), v);
            assert_eq!(pos, b.len());
        }
        let mut b = Vec::new();
        write_varint(&mut b, 300);
        assert_eq!(b, [0xac, 0x02]);
        // Truncated and overlong inputs are errors, not panics.
        assert!(read_varint(&[0x80], &mut 0).is_err());
        assert!(read_varint(&[0xff; 11], &mut 0).is_err());
    }

    fn sample_entries(n: u64) -> Vec<Entry> {
        let mut offset = 0;
        (0..n)
            .map(|i| {
                let length = 10 + (i % 7) as u32;
                // Every 5th entry repeats the previous data (dedup), every
                // 11th is a run.
                let e = Entry {
                    tile_id: i * 3,
                    offset: if i % 5 == 4 { offset - 12 } else { offset },
                    length: if i % 5 == 4 { 12 } else { length },
                    run_length: if i % 11 == 0 { 2 } else { 1 },
                };
                if i % 5 != 4 {
                    offset += u64::from(length);
                }
                e
            })
            .collect()
    }

    #[test]
    fn directory_round_trip_both_compressions() {
        let entries = sample_entries(1000);
        for c in [Compression::None, Compression::Gzip] {
            let bytes = encode_directory(&entries, c).unwrap();
            assert_eq!(decode_directory(&bytes, c).unwrap(), entries);
        }
        assert!(encode_directory(&entries, Compression::Brotli).is_err());
        assert!(decode_directory(&[1, 2, 3], Compression::Zstd).is_err());
        assert!(decode_directory(&[200, 1], Compression::None).is_err());
    }

    #[test]
    fn header_round_trip() {
        let mut b = [0u8; HEADER_LEN];
        b[0..7].copy_from_slice(b"PMTiles");
        b[7] = 3;
        for (i, x) in b.iter_mut().enumerate().skip(8) {
            *x = i as u8;
        }
        b[96] = 1;
        let h = Header::parse(&b).unwrap();
        assert_eq!(h.to_bytes(), b);
        b[7] = 2;
        assert!(Header::parse(&b).is_err());
    }

    #[test]
    fn large_directories_split_into_leaves() {
        let entries = sample_entries(40_000);
        let (root, leaves) = build_directories(
            &entries,
            FIRST_FETCH_LEN as usize - HEADER_LEN,
            Compression::Gzip,
        )
        .unwrap();
        assert!(root.len() <= FIRST_FETCH_LEN as usize - HEADER_LEN);
        let root = decode_directory(&root, Compression::Gzip).unwrap();
        assert!(root.iter().all(|e| e.run_length == 0));
        let mut all = Vec::new();
        for e in root {
            let leaf = &leaves[e.offset as usize..e.offset as usize + e.length as usize];
            all.extend(decode_directory(leaf, Compression::Gzip).unwrap());
        }
        assert_eq!(all, entries);
    }
}
