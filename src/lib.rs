//! Offline OSM data core for native mobile frameworks.
//!
//! OSMExpress owns graph storage, PBF/XML import and spatial indexes. This crate
//! supplies owned Rust objects, safe native lifetimes, staged area publication,
//! raw-tag queries, dependency reporting and a SliceOSM request adapter.
#[cfg(target_os = "android")]
mod android;
mod ffi;
mod mobile_api;
mod model;
pub mod slice;
mod store;
pub use model::*;
pub use store::*;

#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("OSMExpress: {0}")]
    Native(String),
    #[error("serialization: {0}")]
    Json(#[from] serde_json::Error),
    #[error("I/O: {0}")]
    Io(#[from] std::io::Error),
    #[error("invalid argument: {0}")]
    Invalid(String),
}
pub type Result<T> = std::result::Result<T, Error>;
