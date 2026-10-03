//! Offline OSM data core for native mobile frameworks.
//!
//! An area is one read-only SQLite file built from an OSM snapshot (PBF or
//! XML) by `import_area` and opened with `Store`. The crate supplies owned
//! Rust objects, staged atomic area publication, index-backed raw-tag and
//! bbox queries, dependency reporting, a C ABI for the platform adapters and
//! a SliceOSM request adapter. See `schema` for the file format.
#[cfg(target_os = "android")]
mod android;
pub mod basemap;
mod encoding;
mod import;
mod input;
mod mobile_api;
mod model;
mod schema;
pub mod slice;
mod store;
pub use import::{Counts, ImportOptions, ImportReport, import_area};
pub use model::*;
pub use store::*;

#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("SQLite: {0}")]
    Sqlite(#[from] rusqlite::Error),
    /// The input file is malformed or not a current OSM snapshot.
    #[error("input: {0}")]
    Input(String),
    /// An area file decodes to impossible values (damaged or foreign file).
    #[error("corrupt area: {0}")]
    Corrupt(String),
    #[error("serialization: {0}")]
    Json(#[from] serde_json::Error),
    #[error("I/O: {0}")]
    Io(#[from] std::io::Error),
    #[error("invalid argument: {0}")]
    Invalid(String),
}
pub type Result<T> = std::result::Result<T, Error>;

/// A stored value of the wrong SQLite type means the file is not one this
/// crate wrote (or was damaged), not a caller error.
impl From<rusqlite::types::FromSqlError> for Error {
    fn from(error: rusqlite::types::FromSqlError) -> Self {
        Self::Corrupt(error.to_string())
    }
}
