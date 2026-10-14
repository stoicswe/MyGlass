import SwiftUI
import CoreImage
import CoreImage.CIFilterBuiltins

struct ContentView: View {
    @EnvironmentObject private var model: CompanionModel

    @State private var email = ""
    @State private var ssid = ""
    @State private var password = ""
    @State private var encryption: WifiEncryption = .wpa
    @State private var enableAdb = false
    @State private var showQR = false
    @State private var qrImage: Image?

    var body: some View {
        NavigationStack {
            Form {
                Section("Clock sharing") {
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

                Section("How it works") {
                    Text("""
                    Without Google services, Glass is set up by pointing it at the QR \
                    code (Glass scanner) or by sending the setup string over Bluetooth. \
                    This configures Wi-Fi and marks Glass as set up. Notifications use \
                    ANCS; time and timezone are pushed on connect.
                    """)
                    .font(.footnote)
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
            }
            .navigationTitle("MyGlass Companion")
        }
        .onAppear {
            NSLog("MyGlassCompanion: ContentView appeared")
            model.startAdvertising()
        }
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
