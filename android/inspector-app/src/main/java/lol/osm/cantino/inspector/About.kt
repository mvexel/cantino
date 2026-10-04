package lol.osm.cantino.inspector

import lol.osm.cantino.AreaInfo
import lol.osm.cantino.PmtilesInfo
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * "About this area": what the published area is, from its metadata sidecar
 * (AreaMetadata: snapshot, import report, basemap download facts) and from the
 * basemap file itself (PmtilesInfo.read). Mirrors ios/inspector-app About.swift.
 */

private val INSTANT_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)

fun formatInstant(instant: Instant): String = INSTANT_FORMAT.format(instant)

fun formatCount(value: Long): String = String.format(Locale.ROOT, "%,d", value)

/** "45 min", "5 h", "3 days": how old a snapshot is. */
fun formatAge(from: Instant, to: Instant): String {
    val minutes = Duration.between(from, to).toMinutes().coerceAtLeast(0)
    return when {
        minutes < 60 -> "$minutes min"
        minutes < 48 * 60 -> "${minutes / 60} h"
        else -> "${minutes / (24 * 60)} days"
    }
}

object About {
    fun text(area: AreaInfo, info: PmtilesInfo?, now: Instant): String = buildString {
        val m = area.metadata
        if (m == null) {
            appendLine("No metadata for this area.")
        } else {
            val snapshot = m.snapshotTimestamp
            appendLine("Snapshot   " + (snapshot?.let { "${formatInstant(it)} (${formatAge(it, now)} old)" } ?: "unknown"))
            appendLine("Imported   ${formatInstant(Instant.ofEpochMilli(m.importedAtMillis))}")
            val b = m.bbox
            appendLine(String.format(Locale.ROOT, "Bbox       W %.5f S %.5f\n           E %.5f N %.5f", b.west, b.south, b.east, b.north))
            appendLine(
                String.format(
                    Locale.ROOT, "Size       %.1f × %.1f km",
                    Geo.distanceMeters(LatLon(Geo.center(b).lat, b.west), LatLon(Geo.center(b).lat, b.east)) / 1000,
                    Geo.distanceMeters(LatLon(b.south, b.west), LatLon(b.north, b.west)) / 1000,
                ),
            )
            appendLine("Name       ${m.name}")
            val report = m.report
            if (report == null) {
                appendLine("Data       none (basemap-only area)")
            } else {
                appendLine("Import     " + (report.profile?.let { "profile, ${it.keep.size} keep rules" } ?: "full (no import profile)"))
                appendLine("Nodes      ${formatCount(report.counts.nodes)}")
                appendLine("Ways       ${formatCount(report.counts.ways)}")
                appendLine("Relations  ${formatCount(report.counts.relations)}")
                appendLine("Database   ${formatBytes(report.databaseBytes)} at import" + (area.dataFile?.let { ", file ${formatBytes(it.length())}" } ?: ""))
            }
            val basemap = m.basemap
            if (basemap == null) {
                appendLine("Basemap    none")
            } else {
                appendLine("Basemap    ${basemap.kind.name.lowercase()} of ${basemap.sourceUrl}")
                appendLine("           z${basemap.minZoom}–${basemap.maxZoom}, ${formatCount(basemap.addressedTiles)} tiles, ${formatBytes(basemap.fileBytes)}")
                appendLine("           ${formatCount(basemap.requests)} requests, ${formatBytes(basemap.transferredBytes)} transferred")
            }
        }
        if (info != null) {
            appendLine("PMTiles    v${info.specVersion}, z${info.minZoom}–${info.maxZoom}, ${formatCount(info.addressedTiles)} tiles")
            appendLine("           ${formatCount(info.tileEntries)} entries, ${formatCount(info.tileContents)} contents, ${formatBytes(info.fileBytes)}")
            appendLine("           clustered ${info.clustered}, tile type ${info.tileType}, compression ${info.tileCompression}")
            val b = info.bounds
            append(String.format(Locale.ROOT, "           bounds W %.4f S %.4f E %.4f N %.4f", b.west, b.south, b.east, b.north))
        } else if (area.basemapFile != null) {
            append("PMTiles    could not be read")
        }
    }.trimEnd()
}
