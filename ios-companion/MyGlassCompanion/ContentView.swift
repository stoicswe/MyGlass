import SwiftUI
import CoreImage
import CoreImage.CIFilterBuiltins

// MARK: - Root

/// The polished home screen: a hero image and what Glass is paired, with everything else
/// tucked behind a "Details" submenu.
struct ContentView: View {
    @EnvironmentObject private var model: CompanionModel

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 20) {
                    hero
                    PairedGlassCard(info: model.glassInfo, connected: model.glassConnected)

                    NavigationLink {
                        DetailsView()
                    } label: {
                        HStack {
                            Label("Details", systemImage: "slider.horizontal.3")
                                .font(.body.weight(.medium))
                            Spacer()
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
                .padding()
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle("MyGlass")
        }
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

    /// Loads the bundled hero image (added as a plain resource, not in an asset catalog).
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
