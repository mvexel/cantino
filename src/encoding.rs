//! Byte encodings for the SQLite object blobs.
//!
//! The store keeps one row per object and packs the variable-length parts into
//! compact blobs instead of normalised child tables (that layout measured larger
//! and slower; see `spike/`). All integers are LEB128 varints; signed values are
//! zigzag-encoded first so small negative deltas stay one byte.
//!
//! Layouts (all fields in order, no padding, no length prefix for the blob):
//!
//! ```text
//! payload  = tag_count:uvar { key_id:uvar value_len:uvar value:utf8 }*  metadata
//! metadata = [version:uvar] timestamp_s:uvar changeset:uvar uid:uvar
//!            (nodes omit version: it lives in the `nodes.version` column)
//! way refs = { (node_id - previous_node_id):svar }*            previous starts at 0
//! members  = { (role_id << 2 | kind):uvar (id - previous_id):svar }*
//! ```
//!
//! `key_id` and `role_id` index the `dict` table; `uid` keys the `users` table.
//! Tags are written in key order (the order of `model::Tags`), so decoding
//! needs no sort. Values are stored inline: they are mostly unique (names,
//! addresses) and interning them would cost a lookup per tag for little gain.
//!
//! Decoding is defensive: a truncated or out-of-range blob yields
//! `Error::Corrupt` rather than a panic, because the database file is outside
//! this process's control once it is on disk.
use crate::{Error, Result};

pub(crate) fn put_uvar(buffer: &mut Vec<u8>, mut value: u64) {
    while value >= 0x80 {
        buffer.push((value as u8) | 0x80);
        value >>= 7;
    }
    buffer.push(value as u8);
}

pub(crate) fn put_svar(buffer: &mut Vec<u8>, value: i64) {
    put_uvar(buffer, ((value << 1) ^ (value >> 63)) as u64);
}

/// Cursor over one stored blob.
pub(crate) struct Reader<'a> {
    bytes: &'a [u8],
    position: usize,
}

impl<'a> Reader<'a> {
    pub(crate) fn new(bytes: &'a [u8]) -> Self {
        Self { bytes, position: 0 }
    }

    pub(crate) fn is_done(&self) -> bool {
        self.position >= self.bytes.len()
    }

    pub(crate) fn uvar(&mut self) -> Result<u64> {
        let mut value = 0u64;
        let mut shift = 0;
        loop {
            let byte = *self
                .bytes
                .get(self.position)
                .ok_or_else(|| corrupt("truncated varint"))?;
            self.position += 1;
            if shift >= 64 {
                return Err(corrupt("varint overflow"));
            }
            value |= u64::from(byte & 0x7f) << shift;
            if byte < 0x80 {
                return Ok(value);
            }
            shift += 7;
        }
    }

    pub(crate) fn svar(&mut self) -> Result<i64> {
        let value = self.uvar()?;
        Ok(((value >> 1) as i64) ^ -((value & 1) as i64))
    }

    /// A length-prefixed UTF-8 string borrowed from the blob.
    pub(crate) fn text(&mut self) -> Result<&'a str> {
        let length = usize::try_from(self.uvar()?).map_err(|_| corrupt("string length"))?;
        let end = self
            .position
            .checked_add(length)
            .filter(|end| *end <= self.bytes.len())
            .ok_or_else(|| corrupt("truncated string"))?;
        let text = std::str::from_utf8(&self.bytes[self.position..end])
            .map_err(|_| corrupt("invalid UTF-8 in stored string"))?;
        self.position = end;
        Ok(text)
    }
}

pub(crate) fn corrupt(what: &str) -> Error {
    Error::Corrupt(what.into())
}
