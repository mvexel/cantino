//! Cantino: an offline OpenStreetMap SDK core for native mobile apps.
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
mod mobile_basemap;
mod model;
mod schema;
pub mod slice;
mod store;
pub use import::{Counts, ImportOptions, ImportReport, import_area};
pub use model::*;
pub use store::*;

/// Every failure of the core. Each variant (and, for SQLite, each SQLite
/// result code) maps to exactly one stable [`ErrorKind`] through
/// [`Error::kind`]; the platform adapters see only that kind plus the
/// message, so pick the variant by what the *caller* should do about it.
#[derive(Debug, thiserror::Error)]
pub enum Error {
    /// A SQLite failure. Its kind depends on the SQLite result code (disk
    /// full is I/O, "not a database" is an invalid file, ...); see
    /// [`Error::kind`].
    #[error("SQLite: {0}")]
    Sqlite(#[from] rusqlite::Error),
    /// The input file is malformed or not a current OSM snapshot.
    #[error("input: {0}")]
    Input(String),
    /// A file decodes to impossible values (damaged or foreign file): an
    /// area database or a PMTiles archive.
    #[error("corrupt file: {0}")]
    Corrupt(String),
    /// A well-formed file this crate does not read: not a Cantino area, an
    /// area of another `FORMAT_VERSION`, or a PMTiles archive with a feature
    /// the extract engine does not support (unclustered, internal
    /// compression other than none/gzip).
    #[error("unsupported file: {0}")]
    Format(String),
    /// JSON (de)serialization. In the core this is always JSON handed in by
    /// the caller (a query, IDs, options, a SliceOSM status document the
    /// adapter passes on), so it classifies as an invalid argument.
    #[error("serialization: {0}")]
    Json(#[from] serde_json::Error),
    #[error("I/O: {0}")]
    Io(#[from] std::io::Error),
    /// A caller error: bad query, ID, bbox, option or handle argument.
    #[error("invalid argument: {0}")]
    Invalid(String),
    /// A thread-confined handle (store, basemap plan or assembler) was used
    /// from a thread other than its owner.
    #[error("wrong thread: {0}")]
    WrongThread(String),
    /// A bug in this crate (a state that should be unreachable). Never the
    /// caller's fault; report it.
    #[error("internal error: {0}")]
    Internal(String),
}
pub type Result<T> = std::result::Result<T, Error>;

/// Stable category of an [`Error`], carried across the C ABI as an integer
/// code (`cantino_last_error_code`, `CANTINO_ERROR_*` in
/// `include/cantino.h`) and across JNI as the Kotlin `CantinoException`
/// subclass. The numeric values are part of the ABI: never renumber, only
/// append.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
#[repr(i32)]
pub enum ErrorKind {
    /// The caller passed something invalid (query, limit, bbox, ID, batch
    /// size, option, NULL pointer, malformed JSON). Fix the call.
    InvalidArgument = 1,
    /// A file is not what it should be: not an area database, an
    /// incompatible format version, a corrupt or unreadable PBF/XML extract,
    /// a malformed or unsupported PMTiles archive.
    InvalidFile = 2,
    /// The environment failed: missing file, permission, disk full, I/O
    /// error, out of memory, a locked database.
    Io = 3,
    /// A thread-confined handle was used from a non-owner thread.
    WrongThread = 4,
    /// A bug in the core (or a caught panic). Not recoverable by the caller.
    Internal = 5,
}

impl ErrorKind {
    /// The ABI code (`CANTINO_ERROR_*`).
    pub fn code(self) -> i32 {
        self as i32
    }

    /// The kind for an ABI code, `None` for 0 (no error) or a code this
    /// version does not know.
    pub fn from_code(code: i32) -> Option<Self> {
        Some(match code {
            1 => Self::InvalidArgument,
            2 => Self::InvalidFile,
            3 => Self::Io,
            4 => Self::WrongThread,
            5 => Self::Internal,
            _ => return None,
        })
    }
}

impl Error {
    /// The stable category of this error. Exhaustive on purpose: adding a
    /// variant must be a compile error here until it is classified.
    pub fn kind(&self) -> ErrorKind {
        match self {
            Self::Sqlite(error) => sqlite_kind(error),
            Self::Input(_) | Self::Corrupt(_) | Self::Format(_) => ErrorKind::InvalidFile,
            Self::Json(_) | Self::Invalid(_) => ErrorKind::InvalidArgument,
            Self::Io(_) => ErrorKind::Io,
            Self::WrongThread(_) => ErrorKind::WrongThread,
            Self::Internal(_) => ErrorKind::Internal,
        }
    }
}

/// Classifies a SQLite failure. SQLite is used for two things here: reading
/// published areas (read-only) and writing the import's staging database, so
/// the question is always "is the file bad, is the environment bad, or is
/// our SQL bad?".
fn sqlite_kind(error: &rusqlite::Error) -> ErrorKind {
    use rusqlite::{Error as E, ffi::ErrorCode as C};
    match error {
        E::SqliteFailure(failure, _) => match failure.code {
            // Not a SQLite file, a damaged one, or values of the wrong type
            // or size: the file is bad. ConstraintViolation can only happen
            // while importing (areas are opened read-only), where it means
            // the input repeats an object.
            C::NotADatabase
            | C::DatabaseCorrupt
            | C::TypeMismatch
            | C::TooBig
            | C::ConstraintViolation => ErrorKind::InvalidFile,
            // The environment: file missing or unopenable, permissions, disk
            // full, I/O errors, locks held by another process, memory.
            C::CannotOpen
            | C::PermissionDenied
            | C::ReadOnly
            | C::DiskFull
            | C::SystemIoFailure
            | C::DatabaseBusy
            | C::DatabaseLocked
            | C::FileLockingProtocolFailed
            | C::NoLargeFileSupport
            | C::OutOfMemory => ErrorKind::Io,
            // Our SQL or our use of the API: bugs. `Unknown` is SQLITE_ERROR
            // ("no such table", a syntax error), which cannot happen with
            // the fixed statements of this crate on a file that passed the
            // application_id/user_version check.
            C::InternalMalfunction
            | C::OperationAborted
            | C::OperationInterrupted
            | C::NotFound
            | C::SchemaChanged
            | C::ApiMisuse
            | C::AuthorizationForStatementDenied
            | C::ParameterOutOfRange
            | C::Unknown => ErrorKind::Internal,
            // `ErrorCode` is #[non_exhaustive]: a code added by a future
            // rusqlite is unknown to us, so it is a bug to look at, not a
            // guess at I/O.
            _ => ErrorKind::Internal,
        },
        // A stored value that does not decode as the schema says: the file
        // is not one this crate wrote (or it was damaged).
        E::FromSqlConversionFailure(..)
        | E::IntegralValueOutOfRange(..)
        | E::Utf8Error(..)
        | E::InvalidColumnType(..)
        | E::QueryReturnedNoRows => ErrorKind::InvalidFile,
        // A path SQLite cannot take (rusqlite rejects non-UTF-8 paths).
        E::InvalidPath(_) | E::NulError(_) => ErrorKind::InvalidArgument,
        // Everything else is a mistake in our statements or bindings.
        // rusqlite::Error is #[non_exhaustive], hence the wildcard; it is
        // deliberately Internal (a bug to fix), never a silent I/O.
        _ => ErrorKind::Internal,
    }
}

/// A stored value of the wrong SQLite type means the file is not one this
/// crate wrote (or was damaged), not a caller error.
impl From<rusqlite::types::FromSqlError> for Error {
    fn from(error: rusqlite::types::FromSqlError) -> Self {
        Self::Corrupt(error.to_string())
    }
}
