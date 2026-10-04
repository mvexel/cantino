use crate::{Error, Result};
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

// Newtypes prevent accidentally passing a way ID where a node ID is expected.
// Signed IDs also leave room for a future offline-edit layer's temporary IDs.
macro_rules! id_type {
    ($name:ident) => {
        #[derive(
            Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize,
        )]
        pub struct $name(pub i64);
    };
}
id_type!(NodeId);
id_type!(WayId);
id_type!(RelationId);

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize)]
#[serde(tag = "type", content = "id", rename_all = "lowercase")]
pub enum OsmId {
    Node(NodeId),
    Way(WayId),
    Relation(RelationId),
}
impl OsmId {
    // OSM namespaces have independent identities. Node 42 and way 42 must remain distinct.
    pub(crate) fn parts(self) -> (i64, i64) {
        match self {
            Self::Node(NodeId(id)) => (0, id),
            Self::Way(WayId(id)) => (1, id),
            Self::Relation(RelationId(id)) => (2, id),
        }
    }
}

/// Raw OSM strings: no normalization, interpretation, or fixed tag vocabulary.
pub type Tags = BTreeMap<String, String>;

/// OSMExpress's stored metadata. Its format uses zero/empty defaults for absent
/// source fields, so presence cannot be reconstructed from an existing .osmx.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Metadata {
    pub version: u32,
    /// Unix seconds, matching the OSMExpress schema.
    pub timestamp: u64,
    pub changeset: u32,
    pub uid: u32,
    pub user: String,
}

/// Coordinates use integer units of 10⁻⁷ degrees (the OSM API's precision, and PBF's) without float drift.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct Coordinate {
    pub lat_e7: i32,
    pub lon_e7: i32,
}
impl Coordinate {
    pub fn new(lat_e7: i32, lon_e7: i32) -> Result<Self> {
        let value = Self { lat_e7, lon_e7 };
        value.validate()?;
        Ok(value)
    }
    pub fn from_degrees(lat: f64, lon: f64) -> Result<Self> {
        if !lat.is_finite()
            || !lon.is_finite()
            || !(-90.0..=90.0).contains(&lat)
            || !(-180.0..=180.0).contains(&lon)
        {
            return Err(Error::Invalid("coordinates outside WGS84 bounds".into()));
        }
        Self::new((lat * 1e7).round() as i32, (lon * 1e7).round() as i32)
    }
    pub fn lat(self) -> f64 {
        self.lat_e7 as f64 / 1e7
    }
    pub fn lon(self) -> f64 {
        self.lon_e7 as f64 / 1e7
    }
    pub(crate) fn validate(self) -> Result<()> {
        if !(-900_000_000..=900_000_000).contains(&self.lat_e7)
            || !(-1_800_000_000..=1_800_000_000).contains(&self.lon_e7)
        {
            return Err(Error::Invalid("coordinates outside WGS84 bounds".into()));
        }
        Ok(())
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Node {
    pub id: NodeId,
    pub coordinate: Coordinate,
    pub location_version: i32,
    pub tags: Tags,
    pub metadata: Option<Metadata>,
}
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Way {
    pub id: WayId,
    pub nodes: Vec<NodeId>,
    pub tags: Tags,
    pub metadata: Option<Metadata>,
}
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Member {
    pub id: OsmId,
    pub role: String,
}
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Relation {
    pub id: RelationId,
    pub members: Vec<Member>,
    pub tags: Tags,
    pub metadata: Option<Metadata>,
}
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "lowercase")]
pub enum Object {
    Node(Node),
    Way(Way),
    Relation(Relation),
}

impl Way {
    pub fn tag(&self, key: &str) -> Option<&str> {
        self.tags.get(key).map(String::as_str)
    }
}
impl Object {
    pub fn id(&self) -> OsmId {
        match self {
            Self::Node(n) => OsmId::Node(n.id),
            Self::Way(w) => OsmId::Way(w.id),
            Self::Relation(r) => OsmId::Relation(r.id),
        }
    }
    pub fn tags(&self) -> &Tags {
        match self {
            Self::Node(n) => &n.tags,
            Self::Way(w) => &w.tags,
            Self::Relation(r) => &r.tags,
        }
    }
    pub fn tag(&self, key: &str) -> Option<&str> {
        self.tags().get(key).map(String::as_str)
    }
    pub fn metadata(&self) -> Option<&Metadata> {
        match self {
            Self::Node(n) => n.metadata.as_ref(),
            Self::Way(w) => w.metadata.as_ref(),
            Self::Relation(r) => r.metadata.as_ref(),
        }
    }
    /// Order and duplicate references are significant and must survive round trips.
    pub fn references(&self) -> Vec<OsmId> {
        match self {
            Self::Node(_) => vec![],
            Self::Way(w) => w.nodes.iter().copied().map(OsmId::Node).collect(),
            Self::Relation(r) => r.members.iter().map(|m| m.id).collect(),
        }
    }
}

/// A non-wrapping WGS84 rectangle. Split dateline-crossing requests into two boxes.
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
pub struct Bbox {
    pub west: f64,
    pub south: f64,
    pub east: f64,
    pub north: f64,
}
impl Bbox {
    pub fn new(west: f64, south: f64, east: f64, north: f64) -> Result<Self> {
        let bbox = Self {
            west,
            south,
            east,
            north,
        };
        bbox.validate()?;
        Ok(bbox)
    }
    pub(crate) fn validate(self) -> Result<()> {
        Coordinate::from_degrees(self.south, self.west)?;
        Coordinate::from_degrees(self.north, self.east)?;
        if self.west > self.east || self.south > self.north {
            return Err(Error::Invalid("inverted or dateline-crossing bbox".into()));
        }
        Ok(())
    }
}
