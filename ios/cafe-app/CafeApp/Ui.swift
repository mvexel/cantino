import os
import SwiftUI

/*
 * Small shared UI pieces: colours (the Android app's palette), the coloured
 * status line, and loggers. Plain SwiftUI, no third-party UI code: the
 * reference app depends on Cantino and MapLibre only.
 */

enum Palette {
    static let muted = Color(red: 0x6b / 255, green: 0x6b / 255, blue: 0x6b / 255)
    static let open = Color(red: 0x2e / 255, green: 0x7d / 255, blue: 0x32 / 255)
    static let closed = Color(red: 0xc6 / 255, green: 0x28 / 255, blue: 0x28 / 255)
    static let unknown = Color(red: 0x8a / 255, green: 0x8a / 255, blue: 0x8a / 255)
    static let me = Color(red: 0x15 / 255, green: 0x65 / 255, blue: 0xc0 / 255)

    static func of(_ state: OpenState) -> Color {
        switch state {
        case .open: open
        case .closed: closed
        case .unknown: unknown
        }
    }
}

/// "● Open now" with a coloured bullet.
func coloredStatus(_ state: OpenState) -> Text {
    Text("\(Text("●").foregroundStyle(Palette.of(state))) \(state.label)")
}

enum Log {
    static let app = Logger(subsystem: "lol.osm.cantino.cafe", category: "CafeApp")
    static let download = Logger(subsystem: "lol.osm.cantino.cafe", category: "CafeDownload")
    static let map = Logger(subsystem: "lol.osm.cantino.cafe", category: "CafeMap")
}
