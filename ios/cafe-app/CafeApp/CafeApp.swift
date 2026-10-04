import Cantino
import SwiftUI

/// The café reference app: download an offline area once, then find cafés
/// without a network. iOS counterpart of android/cafe-app.
@main
struct CafeApp: App {
    @State private var model = AppModel()

    static let isTestHost = ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] != nil

    var body: some Scene {
        WindowGroup {
            if Self.isTestHost {
                // Hosting the unit tests: no first-run flow (it would ask for
                // the location and start downloads).
                MessageView(title: "Offline cafés", text: "Running unit tests.")
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
            MessageView(title: "Offline cafés", text: "Opening…")
        case .message(let title, let body):
            MessageView(title: title, text: body)
        case .chooser(let reason):
            ChooserView(model: model, reason: reason)
        case .offer(let center, let source):
            OfferView(model: model, center: center, source: source)
        case .progress:
            if let progress = model.progress {
                ProgressScreen(progress: progress) { model.cancelDownload() }
            }
        case .failure(let message, let retryable):
            FailureView(model: model, message: message, retryable: retryable)
        case .map:
            NavigationStack(path: $path) {
                MapScreen(app: model)
                    .id(model.mapGeneration) // a new area: reload everything
                    .navigationDestination(for: OsmId.self) { DetailView(id: $0) }
            }
            .environment(\.openCafe) { path.append($0) }
        }
    }
}
