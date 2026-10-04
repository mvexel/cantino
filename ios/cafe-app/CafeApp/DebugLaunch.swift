import Cantino
import Foundation

/// Launch arguments for automated runs, Debug builds only (release builds
/// ignore them). `xcrun simctl` can launch an app with arguments but cannot
/// tap, so these stand in for the taps of the acceptance walkthrough:
///
///     xcrun simctl launch booted lol.osm.cantino.cafe -lat 40.7608 -lon -111.8910 \
///         -auto_download YES                 # accept the download offer
///     xcrun simctl launch booted lol.osm.cantino.cafe \
///         -list YES -outdoor yes -now open   # list view with filters set
///     xcrun simctl launch booted lol.osm.cantino.cafe -object way/123456   # open the inspector
///     xcrun simctl launch booted lol.osm.cantino.cafe -refresh YES          # accept "Refresh area"
///     xcrun simctl launch booted lol.osm.cantino.cafe -refresh YES -cancel_after 5   # ... and cancel it
///
/// Unlike the location override (``DebugLocation``) none of this is sticky.
struct DebugLaunch: Sendable {
    var autoDownload = false
    var showList = false
    var outdoor: MapModel.OutdoorFilter?
    var now: MapModel.OpenFilter?
    var object: OsmId?
    var refresh = false
    /// Seconds after a download starts at which to press "Cancel download".
    var cancelAfter: Double?

    static func read(arguments: [String] = ProcessInfo.processInfo.arguments) -> DebugLaunch {
        var launch = DebugLaunch()
        #if DEBUG
        func value(_ flag: String) -> String? {
            guard let index = arguments.firstIndex(of: flag), index + 1 < arguments.count else { return nil }
            return arguments[index + 1]
        }
        func flag(_ name: String) -> Bool { ["yes", "true", "1"].contains(value(name)?.lowercased() ?? "") }
        launch.autoDownload = flag("-auto_download")
        launch.showList = flag("-list")
        launch.outdoor = value("-outdoor").flatMap { v in MapModel.OutdoorFilter.allCases.first { $0.rawValue.lowercased() == v.lowercased() } }
        launch.now = value("-now").flatMap { v in MapModel.OpenFilter.allCases.first { $0.rawValue.lowercased() == v.lowercased() } }
        launch.refresh = flag("-refresh")
        launch.cancelAfter = value("-cancel_after").flatMap(Double.init)
        if let object = value("-object"), let slash = object.firstIndex(of: "/"),
           let id = Int64(object[object.index(after: slash)...]), id > 0 {
            let kind = OsmKind.allCases.first { $0.label == object[..<slash].lowercased() }
            launch.object = kind.map { OsmId($0, id) }
        }
        #endif
        return launch
    }
}
