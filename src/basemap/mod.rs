//! Offline basemap: extract a bbox from a remote PMTiles v3 archive.
//!
//! A port of go-pmtiles `extract` as a sans-IO engine: this module decides
//! which byte ranges of the source archive to fetch and assembles the output
//! archive; the platform (Android WorkManager, iOS URLSession, a desktop
//! HTTP client) performs the HTTP range requests. Nothing here does network
//! I/O or needs an async runtime.
//!
//! ```text
//! let mut plan = ExtractPlan::new(bbox, None, Some(15), 0.05)?;
//! let mut step = plan.feed(0, &http_get(plan.first_request()))?;
//! loop {
//!     match step {
//!         Step::Fetch(ranges) => { for r in ranges { step = plan.feed(r.id, &http_get(r))?; } }
//!         Step::Wait => unreachable!("only when requests are still outstanding"),
//!         Step::TilesReady(tiles) => break,
//!     }
//! }
//! let mut asm = plan.into_assembler("out.pmtiles.part")?;
//! for r in asm.remaining() { asm.write_range(r.id, &http_get(r))?; }
//! asm.finish("out.pmtiles")?;
//! ```
//!
//! (With requests fed one at a time, as above, `Wait` appears whenever a
//! batch still has outstanding members; see `examples/basemap_extract.rs`
//! for a complete driver.)
//!
//! What the output contains: every tile of the source whose (z, x, y) lies in
//! the bbox cover (see `cover`) for the zoom range, tile bytes copied
//! unchanged (tile compression passes through), identical tiles stored once,
//! the source's JSON metadata copied verbatim, and a header whose bounds,
//! center and zooms describe the extract.
mod assemble;
pub mod cover;
mod extract;
pub mod format;
pub mod ranges;
pub mod tile_id;

pub use assemble::{Assembler, Progress};
pub use cover::{BBox, TileCover};
pub use extract::{ByteRange, ExtractPlan, Step, TilePlan};
