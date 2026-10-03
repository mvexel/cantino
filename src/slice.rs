//! SliceOSM's asynchronous job protocol, separated from a phone's HTTP runtime.
//! Kotlin/Swift control connectivity, background work, polling and cancellation.
//! Download a completed PBF into a file, then call `import_area` to activate it.
use crate::{Bbox, Error, Result};
use serde::Deserialize;
use serde_json::{Value, json};

pub const API: &str = "https://slice.openstreetmap.us/api/";
pub const FILES: &str = "https://slice.openstreetmap.us/files/";
/// SliceOSM's bbox order is south, west, north, east (not GeoJSON's lon,lat).
pub fn create_job_body(name: &str, bbox: Bbox) -> Result<Value> {
    bbox.validate()?;
    Ok(
        json!({"Name":name,"RegionType":"bbox","RegionData":[bbox.south,bbox.west,bbox.north,bbox.east]}),
    )
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct JobId(String);
impl JobId {
    pub fn parse(text: &str) -> Result<Self> {
        let text = text.trim().trim_matches('"');
        if text.len() != 36
            || !text.bytes().enumerate().all(|(i, b)| {
                if [8, 13, 18, 23].contains(&i) {
                    b == b'-'
                } else {
                    b.is_ascii_hexdigit()
                }
            })
        {
            return Err(Error::Invalid("invalid SliceOSM job UUID".into()));
        }
        Ok(Self(text.to_owned()))
    }
    pub fn status_url(&self) -> String {
        format!("{API}{}", self.0)
    }
    pub fn download_url(&self) -> String {
        format!("{FILES}{}.osm.pbf", self.0)
    }
}
#[derive(Debug, Deserialize)]
pub struct Progress {
    #[serde(rename = "Complete")]
    pub complete: bool,
    #[serde(rename = "Timestamp", default)]
    pub timestamp: String,
    #[serde(rename = "SizeBytes", default)]
    pub size_bytes: Option<u64>,
}
