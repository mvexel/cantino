import SwiftUI

/*
 * First-run screens: message, location chooser, the download offer with the
 * privacy line, download progress and failure. Same texts as the Android
 * café app, so both apps can be compared side by side.
 */

struct MessageView: View {
    let title: String
    let text: String

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title).font(.title2.bold())
            Text(text)
            Spacer()
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Fallback when there is no location: type coordinates or use a preset.
struct ChooserView: View {
    let model: AppModel
    let reason: String
    @State private var input = ""
    @State private var error = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Choose an area").font(.title2.bold())
            Text("\(reason) Pick the centre of the area to download instead.".trimmingCharacters(in: .whitespaces))
            TextField("lat,lon (e.g. 40.7608,-111.8910)", text: $input)
                .textFieldStyle(.roundedBorder)
                .keyboardType(.numbersAndPunctuation)
                .autocorrectionDisabled()
            if !error.isEmpty { Text(error).foregroundStyle(Palette.closed) }
            Button("Use these coordinates") {
                if let point = AppModel.parseLatLon(input) {
                    model.offerDownload(point, source: .manual)
                } else {
                    error = "Enter latitude,longitude in degrees."
                }
            }
            .buttonStyle(.borderedProminent)
            Button("Use Salt Lake City downtown") { model.offerDownload(AppModel.slcDowntown, source: .manual) }
                .buttonStyle(.bordered)
            Button("Try my location again") { model.startFirstRun() }
                .buttonStyle(.bordered)
            Spacer()
        }
        .padding()
    }
}

/// The offer, with the one-line privacy note.
struct OfferView: View {
    let model: AppModel
    let center: LatLon
    let source: LocationSource

    private var km: String {
        Geo.areaSizeKm.truncatingRemainder(dividingBy: 1) == 0 ? "\(Int(Geo.areaSizeKm))" : "\(Geo.areaSizeKm)"
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Download an offline area?").font(.title2.bold())
            Text("Area centre (\(center.description), \(source.label)).").font(.footnote).foregroundStyle(Palette.muted)
            Text("About \(km) × \(km) km around \(center.description): map data (to find cafés) and a basemap, "
                + "so the app works without a connection. Of the map data, only points of interest "
                + "(cafés, shops, other places) are kept; the basemap shows the rest.")
            Text("Privacy: the area's bounds (≈ your location) are sent to SliceOSM and the Protomaps tile host.")
                .font(.callout)
            Button("Download") { model.startDownload(center: center) }
                .buttonStyle(.borderedProminent)
            Button("Choose another place") { model.showChooser("") }
                .buttonStyle(.bordered)
            Spacer()
        }
        .padding()
    }
}

/// Download progress: phase, bar (determinate only when a fraction or total
/// is known), detail, and the duration of every finished phase.
struct ProgressScreen: View {
    let progress: ProgressModel
    let onCancel: () -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("Downloading your offline area").font(.title2.bold())
                if let requested = progress.requested {
                    Text("\(Int(Geo.areaSizeKm)) × \(Int(Geo.areaSizeKm)) km around \(requested.description)")
                        .font(.footnote).foregroundStyle(Palette.muted)
                }
                Text(progress.phaseName).font(.headline)
                if let fraction = progress.fraction {
                    ProgressView(value: fraction)
                } else {
                    ProgressView().progressViewStyle(.linear)
                }
                Text(progress.detail)
                TimelineView(.periodic(from: .now, by: 0.5)) { context in
                    let running = String(format: "… %@ — %.1f s", progress.phaseName,
                                         context.date.timeIntervalSince(progress.currentStart))
                    let total = String(format: "Total %.1f s", context.date.timeIntervalSince(progress.startedAt))
                    Text((progress.finished + [running, total]).joined(separator: "\n"))
                        .font(.footnote.monospacedDigit()).foregroundStyle(Palette.muted)
                }
                Button("Cancel download", role: .destructive, action: onCancel)
                    .buttonStyle(.bordered)
            }
            .padding()
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

struct FailureView: View {
    let model: AppModel
    let message: String
    let retryable: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Download failed").font(.title2.bold())
            Text(message)
            Text(retryable
                 ? "This looks temporary (network or server). Retrying later may work."
                 : "The request or the data was rejected; retrying the same area may fail again.")
                .font(.footnote).foregroundStyle(Palette.muted)
            if Settings.requested != nil {
                Button("Retry") { model.retry() }.buttonStyle(.borderedProminent)
            }
            Button("Choose another place") { model.showChooser("") }.buttonStyle(.bordered)
            if model.hasPublishedArea {
                Button("Back to the map (previous area is kept)") { model.showMap() }.buttonStyle(.bordered)
            }
            Spacer()
        }
        .padding()
    }
}
