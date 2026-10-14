import SwiftUI
import CoreImage
import CoreImage.CIFilterBuiltins

// MARK: - Root

/// The polished home screen: a hero image and what Glass is paired, with everything else
/// tucked behind "Photos" and "Details".
struct ContentView: View {
    @EnvironmentObject private var model: CompanionModel

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    hero
                    PairedGlassCard(info: model.glassInfo, connected: model.glassConnected)
                    navigationRow(title: "Photos",
                                  systemImage: "photo.on.rectangle.angled",
                                  detail: photosSubtitle) { PhotosView() }
                    navigationRow(title: "Details",
                                  systemImage: "slider.horizontal.3",
                                  detail: nil) { DetailsView() }
                }
                .padding()
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle("MyGlass")
        }
    }

    private func navigationRow<Destination: View>(
        title: String, systemImage: String, detail: String?,
        @ViewBuilder destination: () -> Destination
    ) -> some View {
        NavigationLink {
            destination()
        } label: {
            HStack {
                Label(title, systemImage: systemImage)
                    .font(.body.weight(.medium))
                Spacer()
                if let detail = detail {
                    Text(detail).font(.subheadline).foregroundColor(.secondary)
                }
                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.semibold))
                    .foregroundColor(.secondary)
            }
            .padding()
            .background(Color(.secondarySystemGroupedBackground))
            .clipShape(RoundedRectangle(cornerRadius: 16))
        }
        .buttonStyle(.plain)
    }

    private var photosSubtitle: String {
        if let files = model.glassInfo?.files, files > 0 {
            return "\(files) item\(files == 1 ? "" : "s")"
        }
        return model.glassInfo?.ip != nil ? "Open" : "No Wi-Fi"
    }

    private var hero: some View {
        Group {
            if let ui = ContentView.heroImage() {
                Image(uiImage: ui)
                    .resizable()
                    .scaledToFill()
            } else {
                ZStack {
                    Color(.secondarySystemGroupedBackground)
                    Image(systemName: "eyeglasses")
                        .font(.system(size: 56))
                        .foregroundColor(.secondary)
                }
            }
        }
        .frame(height: 190)
        .frame(maxWidth: .infinity)
        .clipped()
        .clipShape(RoundedRectangle(cornerRadius: 20))
    }

    static func heroImage() -> UIImage? {
        if let url = Bundle.main.url(forResource: "hero", withExtension: "jpg") {
            return UIImage(contentsOfFile: url.path)
        }
        return UIImage(named: "hero")
    }
}

// MARK: - Paired Glass card

struct PairedGlassCard: View {
    let info: GlassInfo?
    let connected: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack {
                Text("Paired Glass").font(.headline)
                Spacer()
                statusBadge
            }

            if let info = info {
                HStack(spacing: 12) {
                    Image(systemName: "eyeglasses")
                        .font(.title2)
                        .frame(width: 44, height: 44)
                        .background(Color.accentColor.opacity(0.15))
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                    VStack(alignment: .leading, spacing: 2) {
                        Text(info.name).font(.title3).fontWeight(.semibold)
                        Text(info.model.isEmpty ? "Google Glass" : info.model)
                            .font(.subheadline).foregroundColor(.secondary)
                    }
                    Spacer()
                }

                Divider()

                HStack {
                    Label("Battery", systemImage: info.charging ? "battery.100.bolt"
                          : batterySymbol(info.battery))
                    Spacer()
                    Text(info.battery >= 0 ? "\(info.battery)%" : "\u{2014}")
                        .foregroundColor(.secondary)
                    if info.charging {
                        Image(systemName: "bolt.fill").foregroundColor(.green)
                    }
                }

                if info.total > 0 {
                    VStack(alignment: .leading, spacing: 6) {
                        HStack {
                            Label("Storage", systemImage: "internaldrive")
                            Spacer()
                            Text("\(byteString(info.total - info.free)) of \(byteString(info.total)) used")
                                .foregroundColor(.secondary)
                        }
                        ProgressView(value: Double(info.total - info.free),
                                     total: Double(info.total))
                    }
                }

                if let extra = info.extra, !extra.isEmpty {
                    Divider()
                    ForEach(extra.keys.sorted(), id: \.self) { key in
                        HStack {
                            Text(key.capitalized)
                            Spacer()
                            Text(extra[key] ?? "").foregroundColor(.secondary)
                        }
                    }
                }

                Text("Updated \(info.updated, style: .relative) ago")
                    .font(.caption).foregroundColor(.secondary)
            } else {
                Text("No Glass paired yet")
                    .foregroundColor(.secondary)
                Text("Pair your Glass, then run \u{201C}ok glass, my glass\u{201D} to connect.")
                    .font(.footnote).foregroundColor(.secondary)
            }
        }
        .padding()
        .background(Color(.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 20))
    }

    private var statusBadge: some View {
        HStack(spacing: 6) {
            Circle()
                .fill(connected ? Color.green : Color.gray)
                .frame(width: 8, height: 8)
            Text(connected ? "Connected" : "Offline")
                .font(.caption).foregroundColor(.secondary)
        }
    }

    private func batterySymbol(_ level: Int) -> String {
        switch level {
        case ..<13: return "battery.0"
        case 13..<38: return "battery.25"
        case 38..<63: return "battery.50"
        case 63..<88: return "battery.75"
        default: return "battery.100"
        }
    }
}

func byteString(_ bytes: Int64) -> String {
    let formatter = ByteCountFormatter()
    formatter.countStyle = .file
    return formatter.string(fromByteCount: bytes)
}

// MARK: - Photos

struct PhotosView: View {
    @EnvironmentObject private var model: CompanionModel
    @StateObject private var sync = PhotoSync()
    @State private var preview: PhotoSync.Item?

    private let columns = [GridItem(.adaptive(minimum: 104), spacing: 8)]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                if model.glassInfo?.ip == nil {
                    notice("Glass isn't on Wi-Fi",
                           "Connect Glass to the same Wi-Fi network as this iPhone, then refresh.")
                }

                LazyVGrid(columns: columns, spacing: 8) {
                    ForEach(sync.items) { item in
                        PhotoCell(item: item,
                                  image: sync.thumbnails[item.name],
                                  saved: sync.isSaved(item),
                                  saving: sync.savingName == item.name,
                                  onOpen: { preview = item },
                                  onSave: { Task { await save(item) } })
                    }
                }

                if sync.items.isEmpty && !sync.isLoading {
                    Text("No photos or videos on Glass yet.")
                        .foregroundColor(.secondary)
                }
                if !sync.status.isEmpty {
                    Text(sync.status).font(.footnote).foregroundColor(.secondary)
                }
                if let error = sync.lastError {
                    Text(error).font(.footnote).foregroundColor(.red)
                }
            }
            .padding()
        }
        .navigationTitle("Photos")
        .navigationBarTitleDisplayMode(.inline)
        .overlay {
            if sync.isLoading && sync.items.isEmpty {
                ProgressView("Loading from Glass\u{2026}")
            }
        }
        .toolbar {
            ToolbarItem(placement: .navigationBarLeading) {
                Button("Save New (\(sync.unsavedCount))") {
                    Task { await saveAllNew() }
                }
                .disabled(sync.isLoading || sync.unsavedCount == 0 || endpoint == nil)
            }
            ToolbarItem(placement: .navigationBarTrailing) {
                Button {
                    Task { await refresh() }
                } label: {
                    Image(systemName: "arrow.clockwise")
                }
                .disabled(sync.isLoading || endpoint == nil)
            }
        }
        .task { await refresh() }
        .sheet(item: $preview) { item in
            PhotoPreview(item: item,
                         image: sync.thumbnails[item.name],
                         saved: sync.isSaved(item),
                         saving: sync.savingName == item.name,
                         loadFull: { await loadFull(item) },
                         onSave: { Task { await save(item) } })
        }
    }

    private var endpoint: (String, Int)? {
        guard let ip = model.glassInfo?.ip, let port = model.glassInfo?.port else { return nil }
        return (ip, port)
    }

    private func notice(_ title: String, _ body: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Label(title, systemImage: "wifi.exclamationmark").font(.subheadline.weight(.semibold))
            Text(body).font(.footnote).foregroundColor(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
        .background(Color(.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 14))
    }

    private func refresh() async {
        guard let (ip, port) = endpoint else { return }
        await sync.refresh(host: ip, port: port)
    }

    private func save(_ item: PhotoSync.Item) async {
        guard let (ip, port) = endpoint else { return }
        await sync.save(item, host: ip, port: port)
    }

    private func saveAllNew() async {
        guard let (ip, port) = endpoint else { return }
        await sync.saveAllNew(host: ip, port: port)
    }

    private func loadFull(_ item: PhotoSync.Item) async -> UIImage? {
        guard let (ip, port) = endpoint else { return nil }
        return await sync.fullImage(name: item.name, host: ip, port: port)
    }
}

struct PhotoCell: View {
    let item: PhotoSync.Item
    let image: UIImage?
    let saved: Bool
    let saving: Bool
    let onOpen: () -> Void
    let onSave: () -> Void

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            Button(action: onOpen) {
                ZStack {
                    RoundedRectangle(cornerRadius: 10).fill(Color(.tertiarySystemFill))
                    if let image = image {
                        Image(uiImage: image)
                            .resizable()
                            .scaledToFill()
                    } else {
                        ProgressView()
                    }
                }
                .frame(height: 104)
                .frame(maxWidth: .infinity)
                .clipShape(RoundedRectangle(cornerRadius: 10))
                .overlay(alignment: .topLeading) {
                    if item.isVideo {
                        Image(systemName: "video.fill")
                            .font(.caption2)
                            .padding(5)
                            .background(.ultraThinMaterial, in: Circle())
                            .padding(4)
                    }
                }
            }
            .buttonStyle(.plain)

            Button(action: onSave) {
                Image(systemName: saved ? "checkmark.circle.fill" : "arrow.down.circle.fill")
                    .font(.title3)
                    .symbolRenderingMode(.palette)
                    .foregroundStyle(saved ? .green : .white, .black.opacity(0.35))
                    .padding(5)
                    .background(.ultraThinMaterial, in: Circle())
                    .padding(4)
            }
            .disabled(saved || saving)
        }
    }
}

struct PhotoPreview: View {
    @Environment(\.dismiss) private var dismiss
    let item: PhotoSync.Item
    let image: UIImage?
    let saved: Bool
    let saving: Bool
    let loadFull: () async -> UIImage?
    let onSave: () -> Void

    @State private var full: UIImage?

    var body: some View {
        NavigationStack {
            VStack(spacing: 16) {
                ZStack {
                    Color.black
                    if let shown = full ?? image {
                        Image(uiImage: shown).resizable().scaledToFit()
                    } else {
                        ProgressView().tint(.white)
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)

                HStack {
                    Text(item.name).font(.caption).foregroundColor(.secondary)
                    Spacer()
                    Text(byteString(item.size)).font(.caption).foregroundColor(.secondary)
                }
                .padding(.horizontal)

                Button(action: onSave) {
                    Label(saved ? "Saved to Photos" : "Save to Photos",
                          systemImage: saved ? "checkmark.circle.fill" : "arrow.down.circle")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(saved || saving)
                .padding(.horizontal)
                .padding(.bottom)
            }
            .navigationTitle(item.isVideo ? "Video" : "Photo")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
            .task {
                full = await loadFull()
            }
        }
    }
}

// MARK: - Details (everything the developer view used to show)

struct DetailsView: View {
    @EnvironmentObject private var model: CompanionModel

    @State private var email = ""
    @State private var ssid = ""
    @State private var password = ""
    @State private var encryption: WifiEncryption = .wpa
    @State private var enableAdb = false
    @State private var showQR = false
    @State private var qrImage: Image?

    var body: some View {
        Form {
            Section("Connection") {
                HStack {
                    Text("Status")
                    Spacer()
                    Text(model.status)
                        .foregroundColor(.secondary)
                        .multilineTextAlignment(.trailing)
                }
                Button(model.isAdvertising ? "Stop advertising" : "Start advertising") {
                    if model.isAdvertising {
                        model.stopAdvertising()
                    } else {
                        model.startAdvertising()
                    }
                }
                Button("Send current time now") {
                    model.pushTimeNow()
                }
                .disabled(!model.isAdvertising)
                if let last = model.lastPushed {
                    HStack {
                        Text("Last pushed")
                        Spacer()
                        Text(last, style: .time).foregroundColor(.secondary)
                    }
                }
            }

            Section("Notifications") {
                Button("Send test notification") {
                    model.sendTestNotification()
                }
                Text("Delivered to Glass over ANCS. Lock the iPhone right after tapping so iOS forwards it.")
                    .font(.footnote)
            }

            Section("Glass setup & Wi-Fi") {
                TextField("Google email", text: $email)
                    .textInputAutocapitalization(.never)
                    .keyboardType(.emailAddress)
                TextField("Wi-Fi name (SSID)", text: $ssid)
                    .textInputAutocapitalization(.never)
                SecureField("Wi-Fi password", text: $password)
                Picker("Encryption", selection: $encryption) {
                    ForEach(WifiEncryption.allCases) { enc in
                        Text(enc.name).tag(enc)
                    }
                }
                Toggle("Enable ADB debug", isOn: $enableAdb)
                Button("Set Wi-Fi only") {
                    model.setWifi(ssid: ssid, psk: password, encryption: encryption)
                }
                .disabled(ssid.isEmpty)
                Button("Run full setup") {
                    model.runSetup(email: email, ssid: ssid, psk: password,
                                   encryption: encryption, enableAdb: enableAdb)
                }
                .disabled(email.isEmpty || ssid.isEmpty)
                Button("Show setup QR code") {
                    qrImage = makeQR(model.setupString(
                        email: email, ssid: ssid, psk: password,
                        encryption: encryption, enableAdb: enableAdb))
                    showQR = true
                }
                .disabled(email.isEmpty || ssid.isEmpty)
            }

            Section("Location passthrough") {
                Toggle("Share my location with Glass", isOn: Binding(
                    get: { model.isSharingLocation },
                    set: { model.setLocationSharing($0) }))
                Text("Streams the phone's GPS to Glass over Bluetooth so Glass apps (compass, navigation) use the phone's position.")
                    .font(.footnote)
            }

            Section("Media (Apple Music)") {
                Toggle("Show & control Apple Music on Glass", isOn: Binding(
                    get: { model.isSharingMedia },
                    set: { model.setMediaSharing($0) }))
                HStack {
                    Text("Last command")
                    Spacer()
                    Text(model.lastUplink).foregroundColor(.secondary)
                }
                Text("Shows the current Apple Music track on Glass with play/pause, next and previous. iOS only allows third-party apps to control Apple Music this way.")
                    .font(.footnote)
            }

            Section("Diagnostics") {
                ForEach(model.history.prefix(6), id: \.self) { line in
                    Text(line).font(.caption).foregroundColor(.secondary)
                }
            }

            Section("From Glass (unhandled)") {
                if model.glassMessages.isEmpty {
                    Text("Nothing yet").font(.caption).foregroundColor(.secondary)
                } else {
                    ForEach(model.glassMessages, id: \.self) { line in
                        Text(line).font(.caption).foregroundColor(.secondary)
                    }
                }
            }
        }
        .navigationTitle("Details")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $showQR) {
            VStack(spacing: 16) {
                Text("Scan with Glass").font(.headline)
                if let qrImage = qrImage {
                    qrImage
                        .interpolation(.none)
                        .resizable()
                        .scaledToFit()
                        .frame(maxWidth: 300, maxHeight: 300)
                } else {
                    Text("Could not generate QR code").foregroundColor(.secondary)
                }
                Button("Done") { showQR = false }
            }
            .padding()
        }
    }

    private func makeQR(_ string: String) -> Image? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(string.utf8)
        guard let output = filter.outputImage else { return nil }
        let scaled = output.transformed(by: CGAffineTransform(scaleX: 12, y: 12))
        let context = CIContext()
        guard let cgImage = context.createCGImage(scaled, from: scaled.extent) else {
            return nil
        }
        return Image(uiImage: UIImage(cgImage: cgImage))
    }
}

#Preview {
    ContentView().environmentObject(CompanionModel())
}
