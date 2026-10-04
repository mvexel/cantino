//! Streaming readers for OSM snapshot files. Both feed a `Sink` (the
//! `Importer`, or a profile scan before it) in file
//! order and hold at most one PBF block or one XML element in memory.
//!
//! Readers only translate formats. Content rules (ID order, duplicates,
//! deleted objects, coordinate ranges, duplicate tag keys) live in `Importer`
//! so PBF and XML enforce exactly the same contract.
use crate::{
    Error, Result,
    import::{Sink, SourceInfo},
    schema::{NODE, RELATION, WAY},
};
use osmpbf::{BlobDecode, BlobReader, Element, RelMemberType};
use quick_xml::{
    XmlVersion,
    events::{BytesStart, Event},
};
use std::{fs::File, io::BufReader, io::Read, path::Path};

/// Sniffs the format and streams `path` into `importer`.
pub(crate) fn read(path: &Path, importer: &mut impl Sink) -> Result<()> {
    let mut head = [0u8; 64];
    let length = File::open(path)?.read(&mut head)?;
    let text = head[..length]
        .strip_prefix(b"\xEF\xBB\xBF")
        .unwrap_or(&head[..length]);
    if text.iter().find(|b| !b.is_ascii_whitespace()) == Some(&b'<') {
        read_xml(path, importer)
    } else {
        read_pbf(path, importer)
    }
}

/// Classifies an osmpbf failure. osmpbf reports every read failure as
/// `ErrorKind::Io`, including a file that simply ends early or does not
/// inflate; those are a bad file (InvalidFile), while a missing file or a
/// failing disk is the environment (Io).
fn pbf_error(error: osmpbf::Error) -> Error {
    use std::io::ErrorKind as Io;
    let environment = matches!(
        error.kind(),
        osmpbf::ErrorKind::Io(io) if !matches!(io.kind(), Io::UnexpectedEof | Io::InvalidData)
    );
    if environment {
        let osmpbf::ErrorKind::Io(io) = error.into_kind() else {
            unreachable!("matched as Io above")
        };
        return Error::Io(io);
    }
    Error::Input(format!("PBF: {error}"))
}

/// PBF features this importer understands. `HistoricalInformation` (history
/// files) is refused with a specific message; anything else unknown is refused
/// because ignoring a required feature could misread the data.
const SUPPORTED_FEATURES: [&str; 2] = ["OsmSchema-V0.6", "DenseNodes"];

fn read_pbf(path: &Path, importer: &mut impl Sink) -> Result<()> {
    let reader = BlobReader::from_path(path).map_err(pbf_error)?;
    let mut header = false;
    for blob in reader {
        let blob = blob.map_err(|error| match header {
            true => pbf_error(error),
            false => Error::Input(format!("not an OSM PBF or XML file ({error})")),
        })?;
        match blob.decode().map_err(pbf_error)? {
            BlobDecode::OsmHeader(block) => {
                for feature in block.required_features() {
                    if feature == "HistoricalInformation" {
                        return Err(Error::Input(
                            "PBF history files are not supported; import a current snapshot".into(),
                        ));
                    }
                    if !SUPPORTED_FEATURES.contains(&feature.as_str()) {
                        return Err(Error::Input(format!(
                            "unsupported required PBF feature {feature:?}"
                        )));
                    }
                }
                header = true;
            }
            BlobDecode::OsmData(block) => {
                if !header {
                    return Err(Error::Input("PBF data before its header block".into()));
                }
                for element in block.elements() {
                    pbf_element(element, importer)?;
                }
            }
            // The format allows unknown blob types; readers must skip them.
            BlobDecode::Unknown(_) => {}
        }
    }
    if !header {
        return Err(Error::Input("not an OSM PBF file (no header block)".into()));
    }
    Ok(())
}

fn pbf_element(element: Element, importer: &mut impl Sink) -> Result<()> {
    match element {
        Element::DenseNode(node) => {
            // Dense nodes without an info block carry no metadata; zeros are
            // the model's representation of absent fields.
            let info = match node.info() {
                Some(info) => SourceInfo {
                    version: i64::from(info.version()),
                    timestamp: info.milli_timestamp().div_euclid(1000),
                    changeset: info.changeset(),
                    uid: i64::from(info.uid()),
                    user: info.user().map_err(pbf_error)?,
                    visible: info.visible(),
                },
                None => SourceInfo {
                    version: 0,
                    timestamp: 0,
                    changeset: 0,
                    uid: 0,
                    user: "",
                    visible: true,
                },
            };
            importer.node(
                node.id(),
                node.decimicro_lat(),
                node.decimicro_lon(),
                node.tags(),
                &info,
            )
        }
        Element::Node(node) => {
            let info = pbf_info(&node.info())?;
            importer.node(
                node.id(),
                node.decimicro_lat(),
                node.decimicro_lon(),
                node.tags(),
                &info,
            )
        }
        Element::Way(way) => {
            let info = pbf_info(&way.info())?;
            importer.way(way.id(), way.refs(), way.tags(), &info)
        }
        Element::Relation(relation) => {
            let info = pbf_info(&relation.info())?;
            let mut members = Vec::new();
            for member in relation.members() {
                let kind = match member.member_type {
                    RelMemberType::Node => NODE,
                    RelMemberType::Way => WAY,
                    RelMemberType::Relation => RELATION,
                };
                members.push((kind, member.member_id, member.role().map_err(pbf_error)?));
            }
            importer.relation(relation.id(), members, relation.tags(), &info)
        }
    }
}

fn pbf_info<'a>(info: &osmpbf::Info<'a>) -> Result<SourceInfo<'a>> {
    Ok(SourceInfo {
        version: i64::from(info.version().unwrap_or(0)),
        timestamp: info.milli_timestamp().unwrap_or(0).div_euclid(1000),
        changeset: info.changeset().unwrap_or(0),
        uid: i64::from(info.uid().unwrap_or(0)),
        user: info.user().transpose().map_err(pbf_error)?.unwrap_or(""),
        visible: info.visible() && !info.deleted(),
    })
}

/// One XML object being assembled. Buffers are reused between elements.
#[derive(Default)]
struct Pending {
    kind: i64,
    id: i64,
    lat_e7: i32,
    lon_e7: i32,
    version: i64,
    timestamp: i64,
    changeset: i64,
    uid: i64,
    user: String,
    visible: bool,
    tags: Vec<(String, String)>,
    refs: Vec<i64>,
    members: Vec<(i64, i64, String)>,
}

fn xml_error(error: impl std::fmt::Display) -> Error {
    Error::Input(format!("XML: {error}"))
}

fn read_xml(path: &Path, importer: &mut impl Sink) -> Result<()> {
    let file = BufReader::with_capacity(1 << 16, File::open(path)?);
    let mut reader = quick_xml::Reader::from_reader(file);
    let mut buffer = Vec::new();
    // Number of currently open elements; objects live at depth 1 under <osm>.
    let mut depth = 0usize;
    let mut pending = Pending::default();
    let mut open = false;
    let mut root = false;
    loop {
        let event = reader.read_event_into(&mut buffer).map_err(xml_error)?;
        match event {
            Event::Start(ref element) | Event::Empty(ref element) => {
                let empty = matches!(event, Event::Empty(_));
                let name = element.name();
                let name = name.as_ref();
                match depth {
                    0 => match name {
                        "osm" => root = true,
                        "osmChange" => {
                            return Err(Error::Input(
                                "osmChange files are not supported; import a snapshot".into(),
                            ));
                        }
                        other => {
                            return Err(Error::Input(format!(
                                "not an OSM XML file (root element <{other}>)"
                            )));
                        }
                    },
                    1 => {
                        if let Some(kind) = match name {
                            "node" => Some(NODE),
                            "way" => Some(WAY),
                            "relation" => Some(RELATION),
                            _ => None, // <bounds>, <note>, <meta>: not data
                        } {
                            start_object(&mut pending, kind, element)?;
                            open = true;
                            if empty {
                                flush(&mut pending, importer)?;
                                open = false;
                            }
                        }
                    }
                    2 if open => child(&mut pending, name, element)?,
                    _ => {}
                }
                if !empty {
                    depth += 1;
                }
            }
            Event::End(_) => {
                depth = depth.saturating_sub(1);
                if depth == 1 && open {
                    flush(&mut pending, importer)?;
                    open = false;
                }
            }
            Event::Eof => break,
            _ => {}
        }
        buffer.clear();
    }
    if !root {
        return Err(Error::Input("not an OSM XML file (no <osm> root)".into()));
    }
    Ok(())
}

fn attributes(element: &BytesStart) -> Result<Vec<(String, String)>> {
    element
        .attributes()
        .map(|attribute| {
            let attribute = attribute.map_err(xml_error)?;
            let value = attribute
                .normalized_value(XmlVersion::Implicit1_0)
                .map_err(xml_error)?;
            Ok((attribute.key.as_ref().to_owned(), value.into_owned()))
        })
        .collect()
}

fn number(value: &str, what: &str) -> Result<i64> {
    value
        .parse()
        .map_err(|_| Error::Input(format!("invalid {what} {value:?}")))
}

fn degrees_e7(value: &str, what: &str) -> Result<i32> {
    let degrees: f64 = value
        .parse()
        .map_err(|_| Error::Input(format!("invalid {what} {value:?}")))?;
    let scaled = (degrees * 1e7).round();
    // Range is checked by the importer; this only guards the cast.
    if !scaled.is_finite() || scaled.abs() > f64::from(i32::MAX) {
        return Err(Error::Input(format!("{what} {value} outside WGS84")));
    }
    Ok(scaled as i32)
}

fn start_object(pending: &mut Pending, kind: i64, element: &BytesStart) -> Result<()> {
    pending.kind = kind;
    pending.id = 0;
    pending.lat_e7 = i32::MIN;
    pending.lon_e7 = i32::MIN;
    pending.version = 0;
    pending.timestamp = 0;
    pending.changeset = 0;
    pending.uid = 0;
    pending.user.clear();
    pending.visible = true;
    pending.tags.clear();
    pending.refs.clear();
    pending.members.clear();
    let mut has_id = false;
    for (key, value) in attributes(element)? {
        match key.as_str() {
            "id" => {
                pending.id = number(&value, "id")?;
                has_id = true;
            }
            "lat" => pending.lat_e7 = degrees_e7(&value, "lat")?,
            "lon" => pending.lon_e7 = degrees_e7(&value, "lon")?,
            "version" => pending.version = number(&value, "version")?,
            "timestamp" => pending.timestamp = parse_timestamp(&value)?,
            "changeset" => pending.changeset = number(&value, "changeset")?,
            "uid" => pending.uid = number(&value, "uid")?,
            "user" => pending.user = value,
            "visible" => pending.visible = value != "false",
            // JOSM files mark local deletions this way; they are not snapshots.
            "action" if value == "delete" => pending.visible = false,
            _ => {}
        }
    }
    if !has_id {
        return Err(Error::Input("object without id attribute".into()));
    }
    Ok(())
}

fn child(pending: &mut Pending, name: &str, element: &BytesStart) -> Result<()> {
    let attributes = attributes(element)?;
    let get = |wanted: &str| {
        attributes
            .iter()
            .find(|(key, _)| key == wanted)
            .map(|(_, value)| value.as_str())
    };
    let missing = |what: &str| Error::Input(format!("<{name}> without {what} attribute"));
    match name {
        "tag" => pending.tags.push((
            get("k").ok_or_else(|| missing("k"))?.to_owned(),
            get("v").ok_or_else(|| missing("v"))?.to_owned(),
        )),
        "nd" if pending.kind == WAY => {
            pending
                .refs
                .push(number(get("ref").ok_or_else(|| missing("ref"))?, "ref")?);
        }
        "member" if pending.kind == RELATION => {
            let kind = match get("type").ok_or_else(|| missing("type"))? {
                "node" => NODE,
                "way" => WAY,
                "relation" => RELATION,
                other => return Err(Error::Input(format!("invalid member type {other:?}"))),
            };
            let id = number(get("ref").ok_or_else(|| missing("ref"))?, "ref")?;
            pending
                .members
                .push((kind, id, get("role").unwrap_or("").to_owned()));
        }
        _ => {}
    }
    Ok(())
}

fn flush(pending: &mut Pending, importer: &mut impl Sink) -> Result<()> {
    let info = SourceInfo {
        version: pending.version,
        timestamp: pending.timestamp,
        changeset: pending.changeset,
        uid: pending.uid,
        user: &pending.user,
        visible: pending.visible,
    };
    let tags = pending.tags.iter().map(|(k, v)| (k.as_str(), v.as_str()));
    match pending.kind {
        NODE => {
            // Deleted nodes legitimately lack coordinates; let the importer
            // report the deletion rather than a missing attribute.
            if pending.visible && (pending.lat_e7 == i32::MIN || pending.lon_e7 == i32::MIN) {
                return Err(Error::Input(format!("node {} without lat/lon", pending.id)));
            }
            importer.node(pending.id, pending.lat_e7, pending.lon_e7, tags, &info)
        }
        WAY => importer.way(pending.id, pending.refs.iter().copied(), tags, &info),
        _ => importer.relation(
            pending.id,
            pending
                .members
                .iter()
                .map(|(kind, id, role)| (*kind, *id, role.as_str())),
            tags,
            &info,
        ),
    }
}

/// Parses the OSM API's timestamp form `YYYY-MM-DDTHH:MM:SSZ` into Unix
/// seconds. Other ISO 8601 variants are rejected rather than guessed at.
fn parse_timestamp(text: &str) -> Result<i64> {
    let invalid = || Error::Input(format!("invalid timestamp {text:?}"));
    let bytes = text.as_bytes();
    if bytes.len() != 20
        || bytes[4] != b'-'
        || bytes[7] != b'-'
        || bytes[10] != b'T'
        || bytes[13] != b':'
        || bytes[16] != b':'
        || bytes[19] != b'Z'
    {
        return Err(invalid());
    }
    let field = |range: std::ops::Range<usize>| -> Result<i64> {
        let part = &text[range];
        if !part.bytes().all(|b| b.is_ascii_digit()) {
            return Err(invalid());
        }
        part.parse().map_err(|_| invalid())
    };
    let (year, month, day) = (field(0..4)?, field(5..7)?, field(8..10)?);
    let (hour, minute, second) = (field(11..13)?, field(14..16)?, field(17..19)?);
    if !(1..=12).contains(&month)
        || !(1..=31).contains(&day)
        || hour > 23
        || minute > 59
        || second > 60
    {
        return Err(invalid());
    }
    // Days from civil (Howard Hinnant's algorithm), proleptic Gregorian.
    let y = if month <= 2 { year - 1 } else { year };
    let era = y.div_euclid(400);
    let year_of_era = y - era * 400;
    let month_index = (month + 9) % 12;
    let day_of_year = (153 * month_index + 2) / 5 + day - 1;
    let day_of_era = year_of_era * 365 + year_of_era / 4 - year_of_era / 100 + day_of_year;
    let days = era * 146_097 + day_of_era - 719_468;
    Ok(days * 86_400 + hour * 3_600 + minute * 60 + second)
}

#[cfg(test)]
mod tests {
    #[test]
    fn timestamps_parse_as_utc_seconds() {
        assert_eq!(super::parse_timestamp("1970-01-01T00:00:00Z").unwrap(), 0);
        assert_eq!(
            super::parse_timestamp("2026-10-01T12:00:00Z").unwrap(),
            1_790_856_000
        );
        assert!(super::parse_timestamp("2026-10-01 12:00:00").is_err());
    }
}
