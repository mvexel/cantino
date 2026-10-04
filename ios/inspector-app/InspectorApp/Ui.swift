import os
import SwiftUI

/*
 * Small shared UI pieces: colours (the Android app's palette), formatting and
 * loggers. Plain SwiftUI: the example depends on Cantino and MapLibre only.
 */

enum Palette {
    static let muted = Color(red: 0x6b / 255, green: 0x6b / 255, blue: 0x6b / 255)
    static let error = Color(red: 0xc6 / 255, green: 0x28 / 255, blue: 0x28 / 255)
    static let hit = Color(red: 0x2e / 255, green: 0x7d / 255, blue: 0x32 / 255)
    /// Query results on the map.
    static let result = UIColor(red: 0xff / 255, green: 0x6d / 255, blue: 0x00 / 255, alpha: 1)
    /// The selected object: a strong colour that the light basemap never uses.
    static let highlight = UIColor(red: 0xd5 / 255, green: 0x00 / 255, blue: 0xf9 / 255, alpha: 1)
}

func formatBytes(_ bytes: Int64) -> String {
    if bytes >= 1_000_000 { return String(format: "%.1f MB", Double(bytes) / 1e6) }
    if bytes >= 1_000 { return String(format: "%.0f kB", Double(bytes) / 1e3) }
    return "\(bytes) B"
}

enum Log {
    static let app = Logger(subsystem: "lol.osm.cantino.inspector", category: "Inspector")
    static let map = Logger(subsystem: "lol.osm.cantino.inspector", category: "InspectorMap")
    static let counts = Logger(subsystem: "lol.osm.cantino.inspector", category: "InspectorCounts")
}
