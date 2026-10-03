//! Turning the wanted directory entries into few, large HTTP range requests.
//!
//! Two steps, both ports of go-pmtiles `extract.go`:
//!
//! 1. `reencode` assigns each distinct tile content a place in the output's
//!    tile section (in tile-id order, so the output is clustered), shares the
//!    place between entries that pointed at the same source bytes (dedup and
//!    run-length entries), and records which source bytes go where as
//!    `SrcDst` copies, coalescing copies that are contiguous in the source.
//! 2. `merge_ranges` joins neighbouring copies into one request when the gap
//!    between them is small, spending an "overfetch" budget (a fraction of the
//!    wanted bytes) on downloading and discarding the gaps. Fewer requests
//!    matter more than a few extra bytes on mobile networks.

use std::collections::HashMap;

use super::format::Entry;

/// A copy from the source tile section to the output tile section. Offsets
/// are relative to the respective tile data sections.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SrcDst {
    pub src: u64,
    pub dst: u64,
    pub length: u64,
}

/// Result of `reencode`.
#[derive(Debug, Clone)]
pub struct Reencoded {
    /// The output's tile entries (same ids/run lengths, new offsets).
    pub entries: Vec<Entry>,
    /// Copies sorted by `dst`, covering `0..tile_data_length` without gaps.
    pub copies: Vec<SrcDst>,
    pub tile_data_length: u64,
    /// Sum of run lengths: tiles a reader can address.
    pub addressed_tiles: u64,
    /// Distinct tile contents (distinct source offsets).
    pub tile_contents: u64,
}

/// `entries` must be tile entries (run_length >= 1) sorted by tile id.
pub fn reencode(entries: &[Entry]) -> Reencoded {
    let mut out = Vec::with_capacity(entries.len());
    let mut seen: HashMap<u64, u64> = HashMap::new();
    let mut copies: Vec<SrcDst> = Vec::new();
    let mut addressed = 0u64;
    let mut dst = 0u64;
    for e in entries {
        if let Some(&at) = seen.get(&e.offset) {
            // Same source bytes as an earlier entry: point at its copy.
            out.push(Entry { offset: at, ..*e });
        } else {
            let length = u64::from(e.length);
            match copies.last_mut() {
                Some(last) if last.src + last.length == e.offset => last.length += length,
                _ => copies.push(SrcDst {
                    src: e.offset,
                    dst,
                    length,
                }),
            }
            out.push(Entry { offset: dst, ..*e });
            seen.insert(e.offset, dst);
            dst += length;
        }
        addressed += u64::from(e.run_length);
    }
    Reencoded {
        entries: out,
        copies,
        tile_data_length: dst,
        addressed_tiles: addressed,
        tile_contents: seen.len() as u64,
    }
}

/// "Keep the next `wanted` bytes, then skip `discard` bytes."
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct CopyDiscard {
    pub wanted: u64,
    pub discard: u64,
}

/// One request: source bytes `src..src + length`, whose wanted parts are
/// written consecutively starting at `dst` (consecutive input copies are
/// contiguous in `dst`, so this holds after merging too).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MergedRange {
    pub src: u64,
    pub dst: u64,
    pub length: u64,
    pub parts: Vec<CopyDiscard>,
}

/// Port of go-pmtiles `mergeRanges`. `ranges` are non-contiguous copies in
/// output order. The budget is `overfetch * total wanted bytes`; gaps are
/// spent smallest first, joining a range with its successor (only when the
/// successor starts after it in the source). Returns requests sorted by
/// length, largest first (the order go-pmtiles hands them to its workers),
/// and the bytes to transfer including discarded gaps.
///
/// Deviation: a stable sort breaks ties between equal gaps by position, where
/// Go's unstable sort may pick a different (equally good) set of merges.
pub fn merge_ranges(ranges: &[SrcDst], overfetch: f32) -> (Vec<MergedRange>, u64) {
    let n = ranges.len();
    let total: u64 = ranges.iter().map(|r| r.length).sum();
    // Gap to the next range in output order; None when the next range is not
    // after this one in the source (can't merge) or there is no next.
    let gap = |i: usize| -> Option<u64> {
        let next = ranges.get(i + 1)?;
        next.src.checked_sub(ranges[i].src + ranges[i].length)
    };
    let mut items: Vec<Option<MergedRange>> = ranges
        .iter()
        .map(|r| {
            Some(MergedRange {
                src: r.src,
                dst: r.dst,
                length: r.length,
                parts: vec![CopyDiscard {
                    wanted: r.length,
                    discard: 0,
                }],
            })
        })
        .collect();
    // Linked list over item indices, as merges remove items.
    let mut next: Vec<Option<usize>> = (0..n).map(|i| (i + 1 < n).then_some(i + 1)).collect();
    let mut prev: Vec<Option<usize>> = (0..n).map(|i| i.checked_sub(1)).collect();
    let mut order: Vec<(u64, usize)> = (0..n).filter_map(|i| gap(i).map(|g| (g, i))).collect();
    order.sort_by_key(|&(g, _)| g);

    // f32 multiplication like Go, so budgets (and thus merges) agree.
    let mut budget = (total as f32 * overfetch) as i128;
    for (g, i) in order {
        // `gap(i)` only ever measures to the original successor; merging i
        // into i+1 leaves i+1's own gap unchanged, as in Go.
        if budget - i128::from(g) < 0 {
            break;
        }
        let j = next[i].expect("an item with a gap has a successor");
        let item = items[i].take().expect("item merged at most once");
        let succ = items[j].as_mut().expect("successor still present");
        let mut parts = item.parts;
        parts.last_mut().expect("parts non-empty").discard = g;
        parts.append(&mut succ.parts);
        *succ = MergedRange {
            src: item.src,
            dst: item.dst,
            length: item.length + g + succ.length,
            parts,
        };
        prev[j] = prev[i];
        if let Some(p) = prev[i] {
            next[p] = Some(j);
        }
        budget -= i128::from(g);
    }
    let mut merged: Vec<MergedRange> = items.into_iter().flatten().collect();
    merged.sort_by_key(|m| std::cmp::Reverse(m.length));
    let transfer = merged.iter().map(|m| m.length).sum();
    (merged, transfer)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn e(tile_id: u64, offset: u64, length: u32, run_length: u32) -> Entry {
        Entry {
            tile_id,
            offset,
            length,
            run_length,
        }
    }

    #[test]
    fn reencode_dedups_and_coalesces() {
        let r = reencode(&[
            e(1, 100, 10, 1),
            e(2, 110, 5, 1),  // contiguous with the previous copy
            e(3, 100, 10, 1), // duplicate of tile 1
            e(5, 500, 20, 3), // run
            e(9, 520, 1, 1),
        ]);
        assert_eq!(r.tile_data_length, 36);
        assert_eq!(r.addressed_tiles, 7);
        assert_eq!(r.tile_contents, 4);
        assert_eq!(
            r.copies,
            vec![
                SrcDst {
                    src: 100,
                    dst: 0,
                    length: 15
                },
                SrcDst {
                    src: 500,
                    dst: 15,
                    length: 21
                }
            ]
        );
        let offsets: Vec<u64> = r.entries.iter().map(|e| e.offset).collect();
        assert_eq!(offsets, [0, 10, 0, 15, 35]);
    }

    fn sd(src: u64, dst: u64, length: u64) -> SrcDst {
        SrcDst { src, dst, length }
    }

    #[test]
    fn merge_spends_budget_on_smallest_gaps() {
        // Gaps: 10 (0->1), 2 (1->2), 50 (2->3). Total 400 bytes.
        let ranges = [
            sd(0, 0, 100),
            sd(110, 100, 100),
            sd(212, 200, 100),
            sd(362, 300, 100),
        ];
        let (none, t) = merge_ranges(&ranges, 0.0);
        assert_eq!((none.len(), t), (4, 400));
        // Budget 0.03 * 400 = 12: both small gaps fit, the 50 does not.
        let (m, t) = merge_ranges(&ranges, 0.03);
        assert_eq!(t, 412);
        assert_eq!(m.len(), 2);
        assert_eq!(m[0].src, 0);
        assert_eq!(m[0].length, 312);
        assert_eq!(
            m[0].parts,
            vec![
                CopyDiscard {
                    wanted: 100,
                    discard: 10
                },
                CopyDiscard {
                    wanted: 100,
                    discard: 2
                },
                CopyDiscard {
                    wanted: 100,
                    discard: 0
                },
            ]
        );
        assert_eq!(
            m[1],
            MergedRange {
                src: 362,
                dst: 300,
                length: 100,
                parts: vec![CopyDiscard {
                    wanted: 100,
                    discard: 0
                }]
            }
        );
        // A huge budget merges everything into one request.
        let (all, t) = merge_ranges(&ranges, 1.0);
        assert_eq!((all.len(), t), (1, 462));
    }

    #[test]
    fn merge_never_joins_backwards_ranges() {
        // Second range lies before the first in the source (dedup / unclustered).
        let ranges = [sd(1000, 0, 10), sd(0, 10, 10)];
        let (m, _) = merge_ranges(&ranges, 100.0);
        assert_eq!(m.len(), 2);
        assert!(merge_ranges(&[], 0.1).0.is_empty());
    }
}
