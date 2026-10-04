import SwiftUI

/*
 * Area screens: message, chooser, offer with the privacy line, one progress
 * line and failure. Trimmed from ios/cafe-app FirstRunViews.swift; same texts
 * as android/inspector-app MainActivity.
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

/// Type coordinates or use a preset (also "Download another area" from the map).
struct ChooserView: View {
    let model: AppModel
    let reason: String
    @State private var input = ""
    @State private var error = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Choose an area").font(.title2.bold())
            Text("\(reason) Pick the centre of the area to download.".trimmingCharacters(in: .whitespaces))
            TextField("lat,lon (e.g. 40.7608,-111.8910)", text: $input)
                .textFieldStyle(.roundedBorder)
                .keyboardType(.numbersAndPunctuation)
                .autocorrectionDisabled()
            if !error.isEmpty { Text(error).foregroundStyle(Palette.error) }
            Button("Use these coordinates") {
                if let point = AppModel.parseLatLon(input) {
                    model.offerDownload(point, source: .manual)
                } else {
                    error = "Enter latitude,longitude in degrees."
                }
            }
            .buttonStyle(.borderedProminent)
            Button("Salt Lake City downtown") { model.offerDownload(AppModel.slcDowntown, source: .manual) }.buttonStyle(.bordered)
            Button("Zürich centre") { model.offerDownload(AppModel.zurichCentre, source: .manual) }.buttonStyle(.bordered)
            Button("Try my location again") { model.startFirstRun() }.buttonStyle(.bordered)
            if model.hasPublishedArea {
                Button("Back to the map") { model.showMap() }.buttonStyle(.bordered)
            }
            Spacer()
        }
        .padding()
    }
}

/// The offer: the area centred on `center`, the radius choice
/// (``AreaRadius/choicesKm``) and the one-line privacy note.
struct OfferView: View {
    @Bindable var model: AppModel
    let center: LatLon
    let source: LocationSource

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Download an offline area?").font(.title2.bold())
            Text("Area centre (\(center.description), \(source.label)).").font(.footnote).foregroundStyle(Palette.muted)
            Text("Radius").bold()
            Picker("Radius", selection: $model.radiusKm) {
                ForEach(AreaRadius.choicesKm, id: \.self) { Text(AreaRadius.label($0)).tag($0) }
            }
            .pickerStyle(.segmented)
            Text("About \(AreaRadius.sideLabel(model.radiusKm)) around \(center.description) (radius \(AreaRadius.label(model.radiusKm))): "
                + "every OpenStreetMap object (a full import) and a basemap, so you can inspect the data without a connection.")
            Text("Privacy: the area's bounds (≈ your location) are sent to SliceOSM and the Protomaps tile host.")
                .font(.callout)
            Button("Download") { model.startDownload(center: center) }.buttonStyle(.borderedProminent)
            Button("Choose another place") { model.showChooser("") }.buttonStyle(.bordered)
            Spacer()
        }
        .padding()
    }
}

/// One progress line.
struct ProgressScreen: View {
    let model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Downloading the offline area").font(.title2.bold())
            Text(model.progressLine)
            if let fraction = model.progressFraction {
                ProgressView(value: fraction)
            } else {
                ProgressView().progressViewStyle(.linear)
            }
            Button("Cancel download", role: .destructive) { model.cancelDownload() }.buttonStyle(.bordered)
            Spacer()
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
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
            Button("Choose an area") { model.showChooser("") }.buttonStyle(.borderedProminent)
            if model.hasPublishedArea {
                Button("Back to the map (previous area is kept)") { model.showMap() }.buttonStyle(.bordered)
            }
            Spacer()
        }
        .padding()
    }
}
