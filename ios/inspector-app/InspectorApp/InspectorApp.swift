import Cantino
import SwiftUI

/// Inspector: what is in the OpenStreetMap data under your feet, offline.
/// iOS counterpart of android/inspector-app; structure follows ios/cafe-app.
@main
struct InspectorApp: App {
    @State private var model = AppModel()

    static let isTestHost = ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] != nil

    var body: some Scene {
        WindowGroup {
            if Self.isTestHost {
                // Hosting the unit tests: no area flow (it would ask for the location and download).
                MessageView(title: "Inspector", text: "Running unit tests.")
            } else {
                RootView(model: model)
                    .task { await model.start() }
            }
        }
    }
}

struct RootView: View {
    let model: AppModel
    @State private var path: [OsmId] = []

    var body: some View {
        switch model.screen {
        case .opening:
            MessageView(title: "Inspector", text: "Opening…")
        case .message(let title, let body):
            MessageView(title: title, text: body)
        case .chooser(let reason):
            ChooserView(model: model, reason: reason)
        case .offer(let center, let source):
            OfferView(model: model, center: center, source: source)
        case .progress:
            ProgressScreen(model: model)
        case .failure(let message, let retryable):
            FailureView(model: model, message: message, retryable: retryable)
        case .map:
            NavigationStack(path: $path) {
                MapScreen(app: model)
                    .id(model.mapGeneration) // a new area: reload everything
                    .navigationDestination(for: OsmId.self) { id in
                        ObjectView(id: id) { shown in
                            // "Show on map": back to the root, which highlights it.
                            model.showOnMap = shown
                            path.removeAll()
                        }
                    }
            }
            .environment(\.openObject) { path.append($0) }
        }
    }
}

/// Opens the object navigator (set by ``RootView`` through the navigation
/// path), so the UIKit map and the sheets can navigate.
struct OpenObjectKey: EnvironmentKey {
    static let defaultValue: @MainActor @Sendable (OsmId) -> Void = { _ in }
}

extension EnvironmentValues {
    var openObject: @MainActor @Sendable (OsmId) -> Void {
        get { self[OpenObjectKey.self] }
        set { self[OpenObjectKey.self] = newValue }
    }
}
