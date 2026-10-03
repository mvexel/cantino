//! The writing half of an extract: tile data arrives as range responses, in
//! any order, and is written straight into a staging file that already has
//! the final layout. `finish` writes the header last and renames.
//!
//! Atomicity and failure behaviour:
//!
//! * The staging file starts with 127 zero bytes where the header goes. Zero
//!   bytes are not a PMTiles magic number, so no reader (including
//!   `pmtiles verify`) accepts a staging file, however far it got. The header
//!   is written only after every range is in.
//! * `finish` flushes the file (fsync), then renames it over the output path
//!   and fsyncs the directory. The output path therefore holds either the
//!   previous file (if any) or the complete new archive, never a partial one.
//! * An `Assembler` dropped without a successful `finish` deletes its
//!   staging file. Errors from `write_range*` leave the range missing (it can
//!   be re-fetched and written again; writes are idempotent), and `finish`
//!   refuses while ranges are missing.
//!
//! Memory: only the range being written passes through memory, and
//! `write_range_from` streams it, so tile data never accumulates in RAM.

use std::collections::{BTreeMap, BTreeSet};
use std::fs::{File, OpenOptions};
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use super::extract::{ByteRange, Finalized, TilePlan};
use super::format::HEADER_LEN;
use super::ranges::MergedRange;
use crate::{Error, Result};

/// Download progress of the tile phase.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct Progress {
    pub ranges_done: u64,
    pub ranges_total: u64,
    pub bytes_done: u64,
    pub bytes_total: u64,
}

/// Writes an extract's tile data as range responses arrive.
#[derive(Debug)]
pub struct Assembler {
    staging: PathBuf,
    file: Option<File>,
    header: [u8; HEADER_LEN],
    tile_data_offset: u64,
    ranges: BTreeMap<u64, (ByteRange, MergedRange)>,
    done: BTreeSet<u64>,
    plan: TilePlan,
    finished: bool,
}

impl Assembler {
    pub(crate) fn create(f: Finalized, staging: &Path) -> Result<Self> {
        let mut file = OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .truncate(true)
            .open(staging)?;
        let mut this = Self {
            staging: staging.to_path_buf(),
            file: None,
            header: f.header.to_bytes(),
            tile_data_offset: f.header.tile_data_offset,
            ranges: BTreeMap::new(),
            done: BTreeSet::new(),
            plan: f.plan.clone(),
            finished: false,
        };
        // From here on, Drop removes the staging file on any early return.
        let total = f.plan.archive_bytes;
        let result = (|| -> Result<()> {
            file.set_len(total)?;
            file.write_all(&[0u8; HEADER_LEN])?;
            file.write_all(&f.root)?;
            file.write_all(&f.metadata)?;
            file.write_all(&f.leaves)?;
            Ok(())
        })();
        this.file = Some(file);
        result?;
        let by_id: BTreeMap<u64, ByteRange> = f.plan.requests.iter().map(|r| (r.id, *r)).collect();
        for (id, merged) in f.tile_ranges {
            this.ranges.insert(id, (by_id[&id], merged));
        }
        Ok(this)
    }

    /// The tile plan this assembler executes.
    pub fn plan(&self) -> &TilePlan {
        &self.plan
    }

    /// Tile requests not yet written (all of them initially).
    pub fn remaining(&self) -> Vec<ByteRange> {
        self.ranges
            .iter()
            .filter(|(id, _)| !self.done.contains(id))
            .map(|(_, (r, _))| *r)
            .collect()
    }

    pub fn progress(&self) -> Progress {
        let bytes_done = self.done.iter().map(|id| self.ranges[id].0.length).sum();
        Progress {
            ranges_done: self.done.len() as u64,
            ranges_total: self.ranges.len() as u64,
            bytes_done,
            bytes_total: self.plan.transfer_bytes,
        }
    }

    /// Writes the response for tile request `id` (exactly `length` bytes).
    pub fn write_range(&mut self, id: u64, bytes: &[u8]) -> Result<()> {
        let (range, _) = self.lookup(id)?;
        if bytes.len() as u64 != range.length {
            return Err(Error::Invalid(format!(
                "response for request {id} has {} bytes, expected {}",
                bytes.len(),
                range.length
            )));
        }
        self.write_range_from(id, bytes)
    }

    /// Streams the response for tile request `id` from `reader`, which must
    /// yield exactly the requested bytes: fewer is an error (truncated
    /// download) and so is more (typically a server that ignored the Range
    /// header and sent the whole archive with status 200).
    pub fn write_range_from(&mut self, id: u64, mut reader: impl Read) -> Result<()> {
        if self.finished {
            return Err(Error::Invalid("extract already finished".into()));
        }
        let (range, merged) = self.lookup(id)?;
        let (range, merged) = (*range, merged.clone());
        // A rewrite of a finished range invalidates it until it succeeds.
        self.done.remove(&id);
        let file = self.file.as_mut().expect("file open until finish");
        file.seek(SeekFrom::Start(self.tile_data_offset + merged.dst))?;
        let mut buf = vec![0u8; 64 * 1024];
        for part in &merged.parts {
            copy_exact(&mut reader, Some(&mut *file), part.wanted, &mut buf, id)?;
            copy_exact(&mut reader, None::<&mut File>, part.discard, &mut buf, id)?;
        }
        if reader.read(&mut buf[..1])? != 0 {
            return Err(Error::Invalid(format!(
                "response for request {id} is longer than the requested {} bytes \
                 (did the server ignore the Range header?)",
                range.length
            )));
        }
        self.done.insert(id);
        Ok(())
    }

    fn lookup(&self, id: u64) -> Result<&(ByteRange, MergedRange)> {
        self.ranges
            .get(&id)
            .ok_or_else(|| Error::Invalid(format!("no tile request with id {id}")))
    }

    /// Completes the archive at `output` (replacing any existing file): header
    /// last, fsync, atomic rename. Fails without side effects on the output
    /// while ranges are missing; the assembler stays usable in that case.
    pub fn finish(&mut self, output: impl AsRef<Path>) -> Result<()> {
        if self.finished {
            return Err(Error::Invalid("extract already finished".into()));
        }
        let missing = self.ranges.len() - self.done.len();
        if missing > 0 {
            return Err(Error::Invalid(format!(
                "{missing} tile ranges not written yet"
            )));
        }
        let output = output.as_ref();
        let mut file = self.file.take().expect("file open until finish");
        let result = (|| -> Result<()> {
            file.seek(SeekFrom::Start(0))?;
            file.write_all(&self.header)?;
            file.sync_all()?;
            Ok(())
        })();
        if let Err(e) = result {
            self.file = Some(file);
            return Err(e);
        }
        drop(file);
        std::fs::rename(&self.staging, output)?;
        self.finished = true;
        // Make the rename itself durable. Best effort: not every platform
        // lets a directory be opened and synced.
        if let Some(dir) = output.parent()
            && let Ok(d) = File::open(if dir.as_os_str().is_empty() {
                Path::new(".")
            } else {
                dir
            })
        {
            let _ = d.sync_all();
        }
        Ok(())
    }

    /// Gives up: deletes the staging file. Same as dropping the assembler.
    pub fn abort(self) {}
}

impl Drop for Assembler {
    fn drop(&mut self) {
        if !self.finished {
            self.file = None;
            let _ = std::fs::remove_file(&self.staging);
        }
    }
}

/// Copies exactly `n` bytes from `reader` to `out` (or discards them).
fn copy_exact<W: Write>(
    reader: &mut impl Read,
    mut out: Option<&mut W>,
    mut n: u64,
    buf: &mut [u8],
    id: u64,
) -> Result<()> {
    while n > 0 {
        let want = buf.len().min(usize::try_from(n).unwrap_or(usize::MAX));
        let got = match reader.read(&mut buf[..want]) {
            Ok(0) => {
                return Err(Error::Invalid(format!(
                    "response for request {id} ended {n} bytes early"
                )));
            }
            Ok(got) => got,
            Err(e) if e.kind() == std::io::ErrorKind::Interrupted => continue,
            Err(e) => return Err(e.into()),
        };
        if let Some(w) = out.as_mut() {
            w.write_all(&buf[..got])?;
        }
        n -= got as u64;
    }
    Ok(())
}
