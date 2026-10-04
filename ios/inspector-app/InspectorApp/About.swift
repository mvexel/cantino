import Cantino
import Foundation

/*
 * "About this area": what the published area is, from its metadata sidecar
 * (AreaMetadata: snapshot, import report, basemap download facts) and from
 * the basemap file itself (PmtilesInfo.read). Port of android/inspector-app About.kt.
 */

func formatInstant(_ date: Date) -> String {
    let formatter = DateFormatter()
    formatter.dateFormat = "yyyy-MM-dd HH:mm 'UTC'"
    formatter.timeZone = TimeZone(identifier: "UTC")
    formatter.locale = Locale(identifier: "en_US_POSIX")
    return formatter.string(from: date)
}

func formatCount(_ value: Int64) -> String {
    let formatter = NumberFormatter()
    formatter.numberStyle = .decimal
    formatter.locale = Locale(identifier: "en_US_POSIX")
    formatter.groupingSeparator = ","
    formatter.usesGroupingSeparator = true
    return formatter.string(from: NSNumber(value: value)) ?? "\(value)"
}

/// "45 min", "5 h", "3 days": how old a snapshot is.
func formatAge(from: Date, to: Date) -> String {
    let minutes = max(0, Int(to.timeIntervalSince(from) / 60))
    if minutes < 60 { return "\(minutes) min" }
    if minutes < 48 * 60 { return "\(minutes / 60) h" }
    return "\(minutes / (24 * 60)) days"
}

enum About {
    static func text(_ area: AreaInfo, info: PmtilesInfo?, now: Date) -> String {
        var lines: [String] = []
        if let m = area.metadata {
            lines.append("Snapshot   " + (m.snapshotTimestamp.map { "\(formatInstant($0)) (\(formatAge(from: $0, to: now)) old)" } ?? "unknown"))
            lines.append("Imported   \(formatInstant(Date(timeIntervalSince1970: Double(m.importedAtMillis) / 1000)))")
            let b = m.bbox
            lines.append(String(format: "Bbox       W %.5f S %.5f\n           E %.5f N %.5f", b.west, b.south, b.east, b.north))
            let mid = Geo.center(b).lat
            lines.append(String(format: "Size       %.1f × %.1f km",
                                Geo.distanceMeters(LatLon(lat: mid, lon: b.west), LatLon(lat: mid, lon: b.east)) / 1000,
                                Geo.distanceMeters(LatLon(lat: b.south, lon: b.west), LatLon(lat: b.north, lon: b.west)) / 1000))
            lines.append("Name       \(m.name)")
            if let report = m.report {
                lines.append("Import     " + (report.profile.map { "profile, \($0.keep.count) keep rules" } ?? "full (no import profile)"))
                lines.append("Nodes      \(formatCount(report.counts.nodes))")
                lines.append("Ways       \(formatCount(report.counts.ways))")
                lines.append("Relations  \(formatCount(report.counts.relations))")
                let file = area.dataURL.flatMap { try? FileManager.default.attributesOfItem(atPath: $0.path)[.size] as? NSNumber }
                lines.append("Database   \(formatBytes(report.databaseBytes)) at import" + (file.map { ", file \(formatBytes($0.int64Value))" } ?? ""))
            } else {
                lines.append("Data       none (basemap-only area)")
            }
            if let basemap = m.basemap {
                lines.append("Basemap    \(basemap.kind.rawValue) of")
                lines.append("           \(basemap.sourceUrl)")
                lines.append("           z\(basemap.minZoom)–\(basemap.maxZoom), \(formatCount(basemap.addressedTiles)) tiles, \(formatBytes(basemap.fileBytes))")
                lines.append("           \(formatCount(basemap.requests)) requests, \(formatBytes(basemap.transferredBytes)) transferred")
            } else {
                lines.append("Basemap    none")
            }
        } else {
            lines.append("No metadata for this area.")
        }
        if let info {
            lines.append("PMTiles    v\(info.specVersion), z\(info.minZoom)–\(info.maxZoom), \(formatCount(info.addressedTiles)) tiles")
            lines.append("           \(formatCount(info.tileEntries)) entries, \(formatCount(info.tileContents)) contents, \(formatBytes(info.fileBytes))")
            lines.append("           clustered \(info.clustered), tile type \(info.tileType)")
            lines.append("           compression \(info.tileCompression)")
            let b = info.bounds
            lines.append(String(format: "           bounds W %.4f S %.4f\n                  E %.4f N %.4f", b.west, b.south, b.east, b.north))
        } else if area.basemapURL != nil {
            lines.append("PMTiles    could not be read")
        }
        return lines.joined(separator: "\n")
    }
}
