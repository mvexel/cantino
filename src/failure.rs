//! Classification of area download failures: what the download does next
//! and what the app is told, for every failure input the adapters see.
//!
//! This is the table Cantino 0.2.0's Kotlin adapter implemented in
//! `SliceHttp` (HTTP status handling, network vs disk I/O) and
//! `AreaDownloadWorker` / `BasemapExtract` (native errors), moved into the
//! core so the Swift adapter classifies identically. The adapters keep their
//! messages and their HTTP stack; they ask here which class and
//! [`Reason`] a failure has.
//!
//! Classes and what the scheduler does with them (0.2.0 behaviour):
//!
//! | Class | Inline retry (within the run) | Scheduler retry (WorkManager / BGTask) |
//! | --- | --- | --- |
//! | `transient` | yes, with doubling delays | yes, once inline retries ran out |
//! | `storage` | **no**: retrying would fill the same disk | yes; the next run waits for storage-not-low (decided 2026-10-03) |
//! | `job_gone` | no; a resumed job is resubmitted once within the run | yes, with the job checkpoint cleared (fresh submit) |
//! | `permanent` | no | no: the run fails, `retryable = false` |
//!
//! When scheduler retries run out, a non-permanent failure ends as Failed
//! with `retryable = true`.
//!
//! Not covered here (fixed classes at their call sites, no input to
//! classify): a truncated body (transient, network), a download whose size
//! differs from the job's (transient, server), a slicing job that never
//! finishes or is lost twice (transient, server), range responses with a
//! wrong `Content-Range`/`Content-Length` (permanent, server), an
//! interrupted publish (transient, storage), and a `HEAD` probe where 404
//! means "absent" rather than a failure.
use crate::{Error, ErrorKind, Result};
use serde::Serialize;
use serde_json::Value;

/// What the download does next (see the module table).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum Class {
    Transient,
    Permanent,
    Storage,
    JobGone,
}

/// Why the download failed, by what the app can do about it: the Kotlin
/// `FailureReason` (wire names are its constants in lower case).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum Reason {
    Network,
    Server,
    InvalidRequest,
    Storage,
    InvalidData,
    Unknown,
}

/// The answer for one failure.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
pub struct Classification {
    pub class: Class,
    pub reason: Reason,
    /// Retried within the run (only `transient`).
    pub inline_retry: bool,
    /// Retried by the platform scheduler (everything but `permanent`).
    pub scheduler_retry: bool,
}

impl Classification {
    fn new(class: Class, reason: Reason) -> Self {
        Self {
            class,
            reason,
            inline_retry: class == Class::Transient,
            scheduler_retry: class != Class::Permanent,
        }
    }
}

/// Which request an HTTP status answers; the same status means different
/// things for different requests.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum HttpContext {
    /// A SliceOSM job status or file GET: 404 means the server no longer
    /// knows the job (0.2.0 `checkStatus(goneOn404 = true)`).
    Job,
    /// Any other request (the job submission, a basemap URL download): 404
    /// is a bad request (`goneOn404 = false`).
    Request,
    /// An HTTP range request of a basemap extract: only 206 succeeds; 200
    /// (Range ignored) and 416 are the server's fault (0.2.0 `openRange`).
    Range,
}

/// Where an I/O error happened. 0.2.0 classified I/O by location, not by
/// errno: reading from the network is the network, writing a local file is
/// the device's storage.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum IoContext {
    Network,
    Storage,
}

/// Which call a native (core) error came from.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum NativeContext {
    /// The import, PMTiles validation of a downloaded basemap: storage
    /// errors are retried by the scheduler, everything else is permanent
    /// (0.2.0 `failure(message, error)`).
    Default,
    /// The basemap extract engine, and reading back the extracted file:
    /// always permanent, with the reason from the error kind (0.2.0
    /// `engine { }` and "extracted basemap is unreadable"). Note that this
    /// makes an I/O error here a permanent `storage` failure, unlike
    /// `Default`; kept as 0.2.0 shipped it.
    Engine,
    /// Building a SliceOSM request from the app's input: the request is
    /// invalid, whatever the error kind.
    ProtocolRequest,
    /// Reading a SliceOSM answer: the server sent something unusable
    /// (transient), whatever the error kind.
    ProtocolResponse,
}

/// One failure to classify.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Failure {
    /// An HTTP response status (any integer: a broken response can make
    /// platform stacks report -1).
    Http(i32, HttpContext),
    Io(IoContext),
    /// A core error, by [`ErrorKind`].
    Native(ErrorKind, NativeContext),
}

impl Failure {
    /// Parses the C ABI's JSON form:
    /// `{"http":<status>,"context":"job"|"request"|"range"}`,
    /// `{"io":"network"|"storage"}`, or
    /// `{"native":<CANTINO_ERROR_* code 1..5>,"context":"default"|"engine"|"protocol_request"|"protocol_response"}`.
    pub fn from_json(text: &str) -> Result<Self> {
        let json: Value = serde_json::from_str(text)?;
        let object = json
            .as_object()
            .ok_or_else(|| invalid("a failure must be a JSON object"))?;
        let context = || {
            object
                .get("context")
                .and_then(Value::as_str)
                .ok_or_else(|| invalid("missing string \"context\""))
        };
        let kinds = ["http", "io", "native"]
            .iter()
            .filter(|key| object.contains_key(**key))
            .count();
        if kinds != 1 {
            return Err(invalid(
                "a failure has exactly one of \"http\", \"io\", \"native\"",
            ));
        }
        if let Some(status) = object.get("http") {
            let status = status
                .as_i64()
                .and_then(|status| i32::try_from(status).ok())
                .ok_or_else(|| invalid("\"http\" must be an integer status"))?;
            let context = match context()? {
                "job" => HttpContext::Job,
                "request" => HttpContext::Request,
                "range" => HttpContext::Range,
                other => return Err(invalid(&format!("unknown http context {other:?}"))),
            };
            return Ok(Self::Http(status, context));
        }
        if let Some(io) = object.get("io") {
            return Ok(Self::Io(match io.as_str() {
                Some("network") => IoContext::Network,
                Some("storage") => IoContext::Storage,
                _ => return Err(invalid("\"io\" must be \"network\" or \"storage\"")),
            }));
        }
        let code = object["native"]
            .as_i64()
            .and_then(|code| i32::try_from(code).ok())
            .and_then(ErrorKind::from_code)
            .ok_or_else(|| invalid("\"native\" must be a CANTINO_ERROR_* code 1..5"))?;
        let context = match context()? {
            "default" => NativeContext::Default,
            "engine" => NativeContext::Engine,
            "protocol_request" => NativeContext::ProtocolRequest,
            "protocol_response" => NativeContext::ProtocolResponse,
            other => return Err(invalid(&format!("unknown native context {other:?}"))),
        };
        Ok(Self::Native(code, context))
    }
}

fn invalid(message: &str) -> Error {
    Error::Invalid(message.into())
}

/// Classifies a failure, or `None` when the input is not one (a 2xx status
/// a request accepts; 206 for a range request).
pub fn classify(failure: Failure) -> Option<Classification> {
    use {Class::*, Reason::*};
    let class = |class, reason| Some(Classification::new(class, reason));
    match failure {
        Failure::Http(status, HttpContext::Job) => http_status(status, true),
        Failure::Http(status, HttpContext::Request) => http_status(status, false),
        Failure::Http(status, HttpContext::Range) => match status {
            206 => None,
            // 200: the server ignored Range and is about to send the whole
            // archive. 416: the archive is shorter than its own directories
            // say. Both are the server's file, not the request.
            200 | 416 => class(Permanent, Server),
            // Otherwise the plain status rules; a 2xx other than 206 is
            // still not the range asked for.
            _ => http_status(status, false).or(class(Permanent, Server)),
        },
        Failure::Io(IoContext::Network) => class(Transient, Network),
        Failure::Io(IoContext::Storage) => class(Class::Storage, Reason::Storage),
        Failure::Native(kind, NativeContext::Default) => match native_reason(kind) {
            Reason::Storage => class(Class::Storage, Reason::Storage),
            reason => class(Permanent, reason),
        },
        Failure::Native(kind, NativeContext::Engine) => class(Permanent, native_reason(kind)),
        Failure::Native(_, NativeContext::ProtocolRequest) => class(Permanent, InvalidRequest),
        Failure::Native(_, NativeContext::ProtocolResponse) => class(Transient, Server),
    }
}

/// 0.2.0 `checkStatus`: 2xx passes; 404 is a vanished job only where the
/// request names one; 408, 429 and 5xx are the server's temporary trouble;
/// every other status (other 4xx, an unfollowed 3xx, -1) means the server
/// understood and refused the request.
fn http_status(status: i32, gone_on_404: bool) -> Option<Classification> {
    use {Class::*, Reason::*};
    let class = match status {
        200..=299 => return None,
        404 if gone_on_404 => (JobGone, Server),
        408 | 429 => (Transient, Server),
        status if status >= 500 => (Transient, Server),
        _ => (Permanent, InvalidRequest),
    };
    Some(Classification::new(class.0, class.1))
}

/// 0.2.0 `CantinoException.failureReason()`, plus `Internal` (an
/// `IllegalStateException` in Kotlin, which escaped the worker and was read
/// back as UNKNOWN).
fn native_reason(kind: ErrorKind) -> Reason {
    match kind {
        ErrorKind::InvalidArgument => Reason::InvalidRequest,
        ErrorKind::InvalidFile => Reason::InvalidData,
        ErrorKind::Io => Reason::Storage,
        ErrorKind::WrongThread | ErrorKind::Internal => Reason::Unknown,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn of(json: &str) -> Option<(Class, Reason)> {
        classify(Failure::from_json(json).unwrap()).map(|c| (c.class, c.reason))
    }

    #[test]
    fn http_statuses_follow_0_2_0() {
        use {Class::*, Reason::*};
        for context in ["job", "request"] {
            let at = |status: i32| of(&format!(r#"{{"http":{status},"context":"{context}"}}"#));
            assert_eq!(at(200), None);
            assert_eq!(at(201), None);
            assert_eq!(at(408), Some((Transient, Server)));
            assert_eq!(at(429), Some((Transient, Server)));
            assert_eq!(at(500), Some((Transient, Server)));
            assert_eq!(at(503), Some((Transient, Server)));
            assert_eq!(at(400), Some((Permanent, InvalidRequest)));
            assert_eq!(at(304), Some((Permanent, InvalidRequest)));
            assert_eq!(at(-1), Some((Permanent, InvalidRequest)));
        }
        assert_eq!(
            of(r#"{"http":404,"context":"job"}"#),
            Some((JobGone, Server))
        );
        assert_eq!(
            of(r#"{"http":404,"context":"request"}"#),
            Some((Permanent, InvalidRequest))
        );
        let range = |status: i32| of(&format!(r#"{{"http":{status},"context":"range"}}"#));
        assert_eq!(range(206), None);
        assert_eq!(range(200), Some((Permanent, Server)));
        assert_eq!(range(204), Some((Permanent, Server)));
        assert_eq!(range(416), Some((Permanent, Server)));
        assert_eq!(range(404), Some((Permanent, InvalidRequest)));
        assert_eq!(range(502), Some((Transient, Server)));
    }

    #[test]
    fn storage_is_retried_by_the_scheduler_only() {
        let storage = classify(Failure::Io(IoContext::Storage)).unwrap();
        assert_eq!(
            (storage.class, storage.inline_retry, storage.scheduler_retry),
            (Class::Storage, false, true)
        );
        let native = classify(Failure::Native(ErrorKind::Io, NativeContext::Default)).unwrap();
        assert_eq!(native, storage);
        let network = classify(Failure::Io(IoContext::Network)).unwrap();
        assert_eq!(
            (network.inline_retry, network.scheduler_retry),
            (true, true)
        );
    }

    #[test]
    fn malformed_input_is_an_invalid_argument() {
        for input in [
            "[]",
            "{}",
            r#"{"http":200}"#,
            r#"{"http":"200","context":"job"}"#,
            r#"{"http":200,"context":"nope"}"#,
            r#"{"io":"disk"}"#,
            r#"{"native":0,"context":"default"}"#,
            r#"{"native":6,"context":"default"}"#,
            r#"{"http":200,"io":"network","context":"job"}"#,
            "{malformed",
        ] {
            let error = Failure::from_json(input).unwrap_err();
            assert_eq!(error.kind(), ErrorKind::InvalidArgument, "{input}");
        }
    }
}
