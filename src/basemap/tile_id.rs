//! PMTiles tile ids: one `u64` per (z, x, y), ordered by zoom and then along
//! a Hilbert curve within the zoom.
//!
//! Zoom `z` owns the contiguous id range `[acc(z), acc(z + 1))` where
//! `acc(z) = (4^z - 1) / 3` is the number of tiles in all lower zooms. Inside
//! a zoom the position is the tile's index on the Hilbert curve of order `z`.
//! Because the Hilbert curve refines itself, the descendants of a tile at a
//! deeper zoom form one contiguous id range; `descendant_range` relies on it.
//!
//! Ported from go-pmtiles `tile_id.go`; the arithmetic is kept identical so
//! ids match byte for byte. Zooms are limited to 0..=31 (ids fit in u64 and
//! x/y fit in u32); callers validate before calling.

/// Highest zoom with a representable tile id range (the spec's limit too).
pub const MAX_ZOOM: u8 = 31;

/// Number of tiles in all zooms below `z`, i.e. the first id of zoom `z`.
pub fn zoom_start(z: u8) -> u64 {
    debug_assert!(z <= MAX_ZOOM + 1);
    ((1u128 << (2 * u32::from(z))) - 1) as u64 / 3
}

/// The Hilbert rotation step shared by both directions. `x`/`y` may exceed
/// `n` in `zxy_to_id` (they still carry lower bits); Go uses wrapping uint32
/// arithmetic there and the wrapped high bits are masked off later, so the
/// subtraction must wrap here too.
fn rotate(n: u32, x: u32, y: u32, rx: u32, ry: u32) -> (u32, u32) {
    if ry == 0 {
        let (x, y) = if rx != 0 {
            (
                n.wrapping_sub(1).wrapping_sub(x),
                n.wrapping_sub(1).wrapping_sub(y),
            )
        } else {
            (x, y)
        };
        return (y, x);
    }
    (x, y)
}

/// Converts tile coordinates to a PMTiles tile id. Requires `z <= 31` and
/// `x, y < 2^z`.
pub fn zxy_to_id(z: u8, x: u32, y: u32) -> u64 {
    debug_assert!(z <= MAX_ZOOM);
    let mut acc = zoom_start(z);
    if z == 0 {
        return acc;
    }
    let (mut x, mut y) = (x, y);
    let mut s: u32 = 1 << (z - 1);
    let mut n = u32::from(z) - 1;
    loop {
        let rx = s & x;
        let ry = s & y;
        acc += ((3 * u64::from(rx)) ^ u64::from(ry)) << n;
        (x, y) = rotate(s, x, y, rx, ry);
        s >>= 1;
        if s == 0 {
            break;
        }
        n -= 1;
    }
    acc
}

/// Zoom of a tile id (the inverse of `zoom_start`).
pub fn id_zoom(id: u64) -> u8 {
    // 3 * id + 1 lies in [4^z, 4^(z+1)), so its bit length gives 2z + 1.
    let v = 3u128 * u128::from(id) + 1;
    ((128 - v.leading_zeros() - 1) / 2) as u8
}

/// Converts a tile id back to (z, x, y).
pub fn id_to_zxy(id: u64) -> (u8, u32, u32) {
    let z = id_zoom(id);
    let mut t = id - zoom_start(z);
    let (mut tx, mut ty) = (0u32, 0u32);
    for a in 0..z {
        let s = 1u32 << a;
        let rx = 1 & ((t as u32) >> 1);
        let ry = 1 & ((t as u32) ^ rx);
        (tx, ty) = rotate(s, tx, ty, rx, ry);
        tx += rx << a;
        ty += ry << a;
        t >>= 2;
    }
    (z, tx, ty)
}

/// The id range `[start, end)` that the descendants of tile `(zq, xq, yq)`
/// occupy at zoom `z >= zq`. Uses the Hilbert refinement property: the
/// in-zoom index of every descendant starts with the parent's index.
pub fn descendant_range(zq: u8, xq: u32, yq: u32, z: u8) -> (u64, u64) {
    debug_assert!(zq <= z && z <= MAX_ZOOM);
    let d = 2 * u32::from(z - zq);
    let index = zxy_to_id(zq, xq, yq) - zoom_start(zq);
    let start = zoom_start(z) + (index << d);
    (start, start + (1u64 << d))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn known_ids_match_spec_examples() {
        // Values from the PMTiles spec / go-pmtiles tests.
        assert_eq!(zxy_to_id(0, 0, 0), 0);
        assert_eq!(zxy_to_id(1, 0, 0), 1);
        assert_eq!(zxy_to_id(1, 0, 1), 2);
        assert_eq!(zxy_to_id(1, 1, 1), 3);
        assert_eq!(zxy_to_id(1, 1, 0), 4);
        assert_eq!(zxy_to_id(2, 0, 0), 5);
        assert_eq!(zxy_to_id(3, 0, 0), 21);
        assert_eq!(zxy_to_id(3, 7, 0), 84);
        assert_eq!(id_to_zxy(19078479), (12, 3423, 1763));
    }

    #[test]
    fn round_trip_all_tiles_to_zoom_6_and_samples_deeper() {
        let mut expected = 0u64;
        for z in 0..=6u8 {
            // Every id of the zoom is hit exactly once, in order.
            let mut ids: Vec<u64> = Vec::new();
            for x in 0..(1u32 << z) {
                for y in 0..(1u32 << z) {
                    let id = zxy_to_id(z, x, y);
                    assert_eq!(id_to_zxy(id), (z, x, y));
                    ids.push(id);
                }
            }
            ids.sort_unstable();
            for id in ids {
                assert_eq!(id, expected);
                expected += 1;
            }
        }
        for &(z, x, y) in &[(15, 6200, 12300), (20, 1, 1048575), (31, (1 << 31) - 1, 5)] {
            assert_eq!(id_to_zxy(zxy_to_id(z, x, y)), (z, x, y));
        }
    }

    #[test]
    fn descendants_are_contiguous() {
        let (start, end) = descendant_range(2, 1, 2, 5);
        assert_eq!(end - start, 64);
        for id in start..end {
            let (z, x, y) = id_to_zxy(id);
            assert_eq!((z, x >> 3, y >> 3), (5, 1, 2));
        }
    }
}
