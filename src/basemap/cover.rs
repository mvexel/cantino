//! Which tiles an extract keeps: the "relevance set" of go-pmtiles, computed
//! arithmetically instead of materialized as a bitmap.
//!
//! Semantics (mirroring go-pmtiles `extract --bbox` exactly):
//!
//! * At `max_zoom` the set is every tile that contains some point of the
//!   bbox, using web-mercator tile fractions floored to integers: x from
//!   `floor(fx(west))` to `floor(fx(east))`, y from `floor(fy(north))` to
//!   `floor(fy(south))`. A bbox edge lying exactly on a tile boundary
//!   therefore pulls in the neighbouring tile (go-pmtiles traces the bbox
//!   ring with a tile cover whose corners are floored the same way).
//!   Latitudes beyond +-85.0511 snap to the first/last row as in orb.
//! * Each lower zoom down to `min_zoom` holds the parents of those tiles,
//!   which for a rectangle is the same rectangle shifted right by the zoom
//!   difference. Zooms below `min_zoom` are not included at all; in
//!   particular low zooms are **not** whole-world, only the ancestors of the
//!   bbox (at z0 that is the single world tile).
//! * `west > east` means the bbox crosses the antimeridian; it becomes two
//!   x intervals, as go-pmtiles splits it into two polygons.
//!
//! Deviation: an `east` of exactly 180 (or a bbox reaching it) floors to
//! column `2^z`, which does not exist; go-pmtiles would compute a bogus tile
//! id for it, here it is clamped to the last column.
//!
//! Memory is O(zooms): membership and range-intersection are answered from
//! the per-zoom rectangles, so a large bbox at a high zoom costs nothing
//! extra (go-pmtiles keeps a roaring bitmap of every id).

use serde::{Deserialize, Serialize};

use super::tile_id::{MAX_ZOOM, descendant_range, id_to_zxy, zoom_start};
use crate::{Error, Result};

/// A WGS84 bounding box in degrees. `west > east` crosses the antimeridian.
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
pub struct BBox {
    pub west: f64,
    pub south: f64,
    pub east: f64,
    pub north: f64,
}

impl BBox {
    pub fn new(west: f64, south: f64, east: f64, north: f64) -> Self {
        Self {
            west,
            south,
            east,
            north,
        }
    }

    pub fn validate(&self) -> Result<()> {
        let all = [self.west, self.south, self.east, self.north];
        if all.iter().any(|v| !v.is_finite()) {
            return Err(Error::Invalid("bbox has a non-finite coordinate".into()));
        }
        if !(-180.0..=180.0).contains(&self.west) || !(-180.0..=180.0).contains(&self.east) {
            return Err(Error::Invalid("bbox longitude outside -180..180".into()));
        }
        if !(-90.0..=90.0).contains(&self.south) || !(-90.0..=90.0).contains(&self.north) {
            return Err(Error::Invalid("bbox latitude outside -90..90".into()));
        }
        if self.south > self.north {
            return Err(Error::Invalid("bbox south is greater than north".into()));
        }
        Ok(())
    }
}

/// orb `maptile.Fraction`: fractional tile coordinates of a point.
fn fraction(lon: f64, lat: f64, z: u8) -> (f64, f64) {
    let tiles = (1u64 << z) as f64;
    let fx = (lon / 360.0 + 0.5) * tiles;
    let fy = if lat < -85.0511 {
        tiles - 1.0
    } else if lat > 85.0511 {
        0.0
    } else {
        let siny = (lat * std::f64::consts::PI / 180.0).sin();
        (0.5 + 0.5 * ((1.0 + siny) / (1.0 - siny)).ln() / (-2.0 * std::f64::consts::PI)) * tiles
    };
    (fx, fy)
}

fn tile_index(f: f64, z: u8) -> u32 {
    let last = ((1u64 << z) - 1) as f64;
    f.floor().clamp(0.0, last) as u32
}

/// The covered tiles of one zoom: rows `y0..=y1`, and sorted, disjoint,
/// non-adjacent column intervals (one, or two across the antimeridian).
#[derive(Debug, Clone)]
struct ZoomCover {
    y0: u32,
    y1: u32,
    xs: Vec<(u32, u32)>,
}

impl ZoomCover {
    fn contains(&self, x: u32, y: u32) -> bool {
        (self.y0..=self.y1).contains(&y) && self.xs.iter().any(|&(a, b)| (a..=b).contains(&x))
    }

    /// Whether the inclusive tile rectangle `[x0,x1] x [y0,y1]` (same zoom)
    /// overlaps the cover.
    fn overlaps(&self, x0: u64, x1: u64, y0: u64, y1: u64) -> bool {
        y0 <= u64::from(self.y1)
            && u64::from(self.y0) <= y1
            && self
                .xs
                .iter()
                .any(|&(a, b)| x0 <= u64::from(b) && u64::from(a) <= x1)
    }

    fn count(&self) -> u64 {
        let rows = u64::from(self.y1 - self.y0) + 1;
        rows * self
            .xs
            .iter()
            .map(|&(a, b)| u64::from(b - a) + 1)
            .sum::<u64>()
    }
}

/// The relevance set of an extract: tiles of `min_zoom..=max_zoom` covering
/// the bbox (see the module docs for the exact semantics).
#[derive(Debug, Clone)]
pub struct TileCover {
    min_zoom: u8,
    max_zoom: u8,
    /// Indexed by `z - min_zoom`.
    zooms: Vec<ZoomCover>,
}

impl TileCover {
    pub fn new(bbox: &BBox, min_zoom: u8, max_zoom: u8) -> Result<Self> {
        bbox.validate()?;
        if min_zoom > max_zoom || max_zoom > MAX_ZOOM {
            return Err(Error::Invalid(format!(
                "invalid zoom range {min_zoom}..={max_zoom} (max zoom is {MAX_ZOOM})"
            )));
        }
        let z = max_zoom;
        let last = ((1u64 << z) - 1) as u32;
        let (fx_w, fy_n) = fraction(bbox.west, bbox.north, z);
        let (fx_e, fy_s) = fraction(bbox.east, bbox.south, z);
        let (y0, y1) = (tile_index(fy_n, z), tile_index(fy_s, z));
        let (xw, xe) = (tile_index(fx_w, z), tile_index(fx_e, z));
        let base: Vec<(u32, u32)> = if bbox.west > bbox.east {
            vec![(0, xe), (xw, last)]
        } else {
            vec![(xw, xe)]
        };
        let zooms = (min_zoom..=max_zoom)
            .map(|zz| {
                let shift = max_zoom - zz;
                // Shifting can make the two antimeridian intervals touch or
                // overlap at low zooms; merge so `count` never double counts.
                let mut xs: Vec<(u32, u32)> = Vec::new();
                for &(a, b) in &base {
                    let (a, b) = (a >> shift, b >> shift);
                    match xs.last_mut() {
                        Some(prev) if a <= prev.1 + 1 => prev.1 = prev.1.max(b),
                        _ => xs.push((a, b)),
                    }
                }
                ZoomCover {
                    y0: y0 >> shift,
                    y1: y1 >> shift,
                    xs,
                }
            })
            .collect();
        Ok(Self {
            min_zoom,
            max_zoom,
            zooms,
        })
    }

    pub fn min_zoom(&self) -> u8 {
        self.min_zoom
    }

    pub fn max_zoom(&self) -> u8 {
        self.max_zoom
    }

    /// Number of tiles in the set (go-pmtiles' "Region tiles").
    pub fn count(&self) -> u64 {
        self.zooms.iter().map(ZoomCover::count).sum()
    }

    fn zoom(&self, z: u8) -> Option<&ZoomCover> {
        if z < self.min_zoom || z > self.max_zoom {
            return None;
        }
        self.zooms.get(usize::from(z - self.min_zoom))
    }

    pub fn contains(&self, id: u64) -> bool {
        if id >= zoom_start(self.max_zoom + 1) {
            return false;
        }
        let (z, x, y) = id_to_zxy(id);
        self.zoom(z).is_some_and(|c| c.contains(x, y))
    }

    /// Whether any tile id in `[start, end)` is in the set. Used to decide
    /// whether a leaf directory (which covers such a range) is needed.
    pub fn intersects_range(&self, start: u64, end: u64) -> bool {
        (self.min_zoom..=self.max_zoom).any(|z| {
            let a = start.max(zoom_start(z));
            let b = end.min(zoom_start(z + 1));
            a < b && self.descend(z, 0, 0, 0, a, b)
        })
    }

    /// Quadtree descent from tile `(zq, xq, yq)` towards zoom `z`: prunes
    /// subtrees whose descendant id range misses `[a, b)` or whose footprint
    /// misses the cover, and succeeds once a subtree lies entirely inside
    /// `[a, b)` while touching the cover. Visits O(depth * boundary) nodes.
    fn descend(&self, z: u8, zq: u8, xq: u32, yq: u32, a: u64, b: u64) -> bool {
        let (start, end) = descendant_range(zq, xq, yq, z);
        if end <= a || start >= b {
            return false;
        }
        let d = z - zq;
        let (x0, y0) = (u64::from(xq) << d, u64::from(yq) << d);
        let span = 1u64 << d;
        let cover = self.zoom(z).expect("z within cover zooms");
        if !cover.overlaps(x0, x0 + span - 1, y0, y0 + span - 1) {
            return false;
        }
        if a <= start && end <= b {
            return true;
        }
        // d > 0 here: a single tile range is either disjoint or contained.
        let (cx, cy) = (xq * 2, yq * 2);
        [(0, 0), (0, 1), (1, 0), (1, 1)]
            .iter()
            .any(|&(i, j)| self.descend(z, zq + 1, cx + i, cy + j, a, b))
    }
}

#[cfg(test)]
mod tests {
    use super::super::tile_id::zxy_to_id;
    use super::*;

    fn slc() -> BBox {
        BBox::new(-111.91, 40.75, -111.87, 40.78)
    }

    #[test]
    fn cover_matches_brute_force() {
        let cover = TileCover::new(&slc(), 0, 10).unwrap();
        let ids: Vec<u64> = (0..zoom_start(11))
            .filter(|&id| cover.contains(id))
            .collect();
        assert_eq!(ids.len() as u64, cover.count());
        // One tile per zoom at this size up to z10 (a ~3 km box).
        assert!(ids.len() >= 11);
        for w in [
            (0, zoom_start(11)),
            (0, 1),
            (ids[3], ids[3] + 1),
            (ids[3] + 1, ids[4]),
        ] {
            let brute = ids.iter().any(|&id| id >= w.0 && id < w.1);
            assert_eq!(cover.intersects_range(w.0, w.1), brute, "{w:?}");
        }
        // Exhaustive on small windows across zooms 9..10.
        let lo = zoom_start(9);
        for a in (lo..zoom_start(11)).step_by(997) {
            for len in [1, 7, 300, 5000] {
                let brute = ids.iter().any(|&id| id >= a && id < a + len);
                assert_eq!(cover.intersects_range(a, a + len), brute);
            }
        }
    }

    #[test]
    fn lower_zooms_are_parents_and_min_zoom_cuts() {
        let cover = TileCover::new(&slc(), 3, 12).unwrap();
        assert!(!cover.contains(0));
        assert!(!cover.contains(zxy_to_id(2, 0, 1)));
        for z in 3..=12u8 {
            let n = (0..(1u32 << z))
                .flat_map(|x| (0..(1u32 << z)).map(move |y| (x, y)))
                .filter(|&(x, y)| cover.contains(zxy_to_id(z, x, y)))
                .count();
            assert!((1..=4).contains(&n), "z{z}: {n}");
        }
    }

    #[test]
    fn antimeridian_two_intervals_merge_at_low_zoom() {
        let cover = TileCover::new(&BBox::new(170.0, -10.0, -170.0, 10.0), 0, 4).unwrap();
        assert!(cover.contains(0));
        assert!(cover.contains(zxy_to_id(4, 0, 7)));
        assert!(cover.contains(zxy_to_id(4, 15, 7)));
        assert!(!cover.contains(zxy_to_id(4, 7, 7)));
        let brute = (0..zoom_start(5)).filter(|&id| cover.contains(id)).count() as u64;
        assert_eq!(cover.count(), brute);
    }

    #[test]
    fn rejects_bad_input() {
        assert!(TileCover::new(&BBox::new(0.0, 10.0, 1.0, 5.0), 0, 5).is_err());
        assert!(TileCover::new(&BBox::new(f64::NAN, 0.0, 1.0, 5.0), 0, 5).is_err());
        assert!(TileCover::new(&slc(), 6, 5).is_err());
        assert!(TileCover::new(&slc(), 0, 32).is_err());
        // The whole world at z2 is all 21 tiles of z0..=2.
        let world = TileCover::new(&BBox::new(-180.0, -90.0, 180.0, 90.0), 0, 2).unwrap();
        assert_eq!(world.count(), 21);
    }
}
