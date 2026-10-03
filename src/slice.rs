//! SliceOSM's asynchronous job protocol, separated from a phone's HTTP runtime.
//!
//! The protocol knowledge (request body, bbox order, job-ID validation, URL
//! layout, progress interpretation) lives here so the Android and iOS adapters
//! share one implementation through the C ABI (`mobile_api`). The platform
//! side owns everything that depends on the OS: HTTP, connectivity, background
//! scheduling, polling cadence, retries and cancellation.
//!
//! Protocol, as served by <https://slice.openstreetmap.us/>:
//! 1. `POST <base>api/` with `{"Name","RegionType":"bbox","RegionData":[s,w,n,e]}`
//!    answers with the job UUID as plain text (HTTP 201).
//! 2. `GET <base>api/<uuid>` answers with a JSON status object
//!    (`Complete`, `NodesProg`/`NodesTotal`, `ElemsProg`/`ElemsTotal`,
//!    `SizeBytes`, `Timestamp`, ...). Unknown or expired jobs answer 404.
//! 3. Once `Complete` is true, `GET <base>files/<uuid>.osm.pbf` serves the PBF.
//!
//! Download a completed PBF into a file, then call `import_area` to activate it.
use crate::{Bbox, Error, Result};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

/// The public SliceOSM instance. Adapters may point at another base URL
/// (a self-hosted instance, or a fake server in tests) through [`Service`].
pub const BASE: &str = "https://slice.openstreetmap.us/";
pub const API: &str = "https://slice.openstreetmap.us/api/";
pub const FILES: &str = "https://slice.openstreetmap.us/files/";

/// SliceOSM's bbox order is south, west, north, east (not GeoJSON's lon,lat).
pub fn create_job_body(name: &str, bbox: Bbox) -> Result<Value> {
    bbox.validate()?;
    Ok(
        json!({"Name":name,"RegionType":"bbox","RegionData":[bbox.south,bbox.west,bbox.north,bbox.east]}),
    )
}

/// One SliceOSM deployment, identified by its base URL. All endpoint URLs are
/// derived from the base so an adapter never concatenates protocol paths itself.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Service {
    /// Always ends with `/`, so endpoint paths can be appended directly.
    base: String,
}
impl Default for Service {
    fn default() -> Self {
        Self { base: BASE.into() }
    }
}
impl Service {
    /// Accepts an `http://` or `https://` base URL, with or without a trailing
    /// slash. Plain HTTP is allowed for local test servers; the default is HTTPS.
    pub fn new(base: &str) -> Result<Self> {
        let base = base.trim();
        let rest = base
            .strip_prefix("https://")
            .or_else(|| base.strip_prefix("http://"))
            .ok_or_else(|| Error::Invalid("SliceOSM base URL must be http(s)".into()))?;
        // A host is required; query strings and fragments would break the
        // appended paths, so they are rejected rather than mangled.
        if rest.is_empty() || rest.starts_with('/') || base.contains(['?', '#', ' ']) {
            return Err(Error::Invalid("invalid SliceOSM base URL".into()));
        }
        let mut base = base.to_owned();
        if !base.ends_with('/') {
            base.push('/');
        }
        Ok(Self { base })
    }
    pub fn base(&self) -> &str {
        &self.base
    }
    /// Endpoint that accepts [`create_job_body`] as a JSON POST.
    pub fn submit_url(&self) -> String {
        format!("{}api/", self.base)
    }
    pub fn status_url(&self, job: &JobId) -> String {
        format!("{}api/{}", self.base, job.0)
    }
    pub fn download_url(&self, job: &JobId) -> String {
        format!("{}files/{}.osm.pbf", self.base, job.0)
    }
}

/// A validated SliceOSM job UUID. Validation matters because the ID is pasted
/// into URLs and (by the adapters) persisted for resumption: anything that is
/// not a canonical UUID is rejected instead of being trusted.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct JobId(String);
impl JobId {
    /// Parses the submit response body: the UUID, optionally quoted and/or
    /// surrounded by whitespace.
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
    pub fn as_str(&self) -> &str {
        &self.0
    }
    pub fn status_url(&self) -> String {
        Service::default().status_url(self)
    }
    pub fn download_url(&self) -> String {
        Service::default().download_url(self)
    }
}

/// The job status document (`GET api/<uuid>`). Only `Complete` is required;
/// every counter is optional so a server that omits one still parses.
#[derive(Debug, Deserialize)]
pub struct Progress {
    #[serde(rename = "Complete")]
    pub complete: bool,
    /// Replication timestamp of the snapshot the extract was cut from
    /// (ISO 8601), i.e. the age of the data, not of the job.
    #[serde(rename = "Timestamp", default)]
    pub timestamp: String,
    #[serde(rename = "SizeBytes", default)]
    pub size_bytes: Option<u64>,
    #[serde(rename = "NodesProg", default)]
    pub nodes_progress: Option<u64>,
    #[serde(rename = "NodesTotal", default)]
    pub nodes_total: Option<u64>,
    #[serde(rename = "ElemsProg", default)]
    pub elements_progress: Option<u64>,
    #[serde(rename = "ElemsTotal", default)]
    pub elements_total: Option<u64>,
}
impl Progress {
    /// Best-effort completion in `0..=1`, or `None` when the server has not
    /// reported totals yet. Elements (nodes + ways + relations) are preferred
    /// over nodes because they cover the whole job. A complete job is 1.0 even
    /// when the counters disagree: the live service reports `Complete` with
    /// `ElemsProg` slightly below `ElemsTotal`.
    pub fn fraction(&self) -> Option<f64> {
        if self.complete {
            return Some(1.0);
        }
        let ratio = |done: Option<u64>, total: Option<u64>| match (done, total) {
            (Some(done), Some(total)) if total > 0 => Some((done as f64 / total as f64).min(1.0)),
            _ => None,
        };
        ratio(self.elements_progress, self.elements_total)
            .or_else(|| ratio(self.nodes_progress, self.nodes_total))
    }
    /// The adapter-facing summary returned by the C ABI.
    pub fn summary(&self) -> ProgressSummary {
        ProgressSummary {
            complete: self.complete,
            fraction: self.fraction(),
            size_bytes: self.size_bytes,
            timestamp: (!self.timestamp.is_empty()).then(|| self.timestamp.clone()),
        }
    }
}

/// Normalized job progress: what a UI or scheduler needs, nothing more.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct ProgressSummary {
    pub complete: bool,
    pub fraction: Option<f64>,
    /// Size of the finished PBF, when known; adapters use it to report
    /// download progress and to detect truncated downloads.
    pub size_bytes: Option<u64>,
    pub timestamp: Option<String>,
}

/// Everything an adapter needs to submit a job: where to POST and the exact body.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct JobRequest {
    pub url: String,
    /// Compact JSON, to be sent verbatim with `Content-Type: application/json`.
    pub body: String,
}
pub fn job_request(service: &Service, name: &str, bbox: Bbox) -> Result<JobRequest> {
    Ok(JobRequest {
        url: service.submit_url(),
        body: create_job_body(name, bbox)?.to_string(),
    })
}

/// A parsed job with its endpoint URLs.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct JobUrls {
    pub job_id: String,
    pub status_url: String,
    pub download_url: String,
}
pub fn job_urls(service: &Service, response: &str) -> Result<JobUrls> {
    let job = JobId::parse(response)?;
    Ok(JobUrls {
        status_url: service.status_url(&job),
        download_url: service.download_url(&job),
        job_id: job.0,
    })
}
