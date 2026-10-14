import Foundation
import Combine
import UserNotifications
import CoreLocation
import UIKit

/// View-model that owns the Bluetooth companion and surfaces UI state.
final class CompanionModel: NSObject, ObservableObject,
                            UNUserNotificationCenterDelegate, CLLocationManagerDelegate {

    @Published var status: String = "Bluetooth idle"
    @Published var isAdvertising: Bool = false
    @Published var isSharingLocation: Bool = false
    @Published var isSharingMedia: Bool = false
    @Published var lastUplink: String = "\u{2014}"
    @Published var lastPushed: Date?
    @Published var history: [String] = []

    private static let mediaKey = "mediaSharingEnabled"
    private let bluetooth = BluetoothCompanion()
    private let locationManager = CLLocationManager()
    private let media = MediaController()
    private var mediaEnabled = false

    override init() {
        super.init()
        NSLog("MyGlassCompanion: CompanionModel init")
        let tz = TimeZone.current
        NSLog("MyGlassCompanion: tz=%@ offset=%ds (%d min)",
              tz.identifier, tz.secondsFromGMT(), tz.secondsFromGMT() / 60)
        UNUserNotificationCenter.current().delegate = self
        bluetooth.onStatus = { [weak self] message in
            guard let self = self else { return }
            self.status = message
            self.history.insert("\(Self.formatter.string(from: Date()))  \(message)", at: 0)
            if self.history.count > 40 {
                self.history.removeLast(self.history.count - 40)
            }
        }
        bluetooth.onAdvertisingChanged = { [weak self] advertising in
            self?.isAdvertising = advertising
        }
        bluetooth.onTimePushed = { [weak self] date in
            self?.lastPushed = date
        }
        bluetooth.onCentralSubscribed = { [weak self] in
            self?.sendPhoneInfo()
        }
        bluetooth.onUplinkCommand = { [weak self] text in
            self?.handleUplink(text)
        }
        media.onStateChanged = { [weak self] state in
            guard let self = self, self.mediaEnabled else { return }
            self.pushMedia(state)
        }
        // Begin advertising immediately at launch; if Bluetooth is not powered on yet,
        // BluetoothCompanion will start as soon as it becomes available.
        bluetooth.start()

        if UserDefaults.standard.object(forKey: Self.mediaKey) as? Bool ?? true {
            setMediaSharing(true)
        }

        // Periodic heartbeat so Glass can detect a stale link (e.g. after this app
        // restarts) even though Android serves cached values for GATT reads.
        Timer.scheduledTimer(withTimeInterval: 20, repeats: true) { [weak self] _ in
            guard let self = self else { return }
            self.bluetooth.sendCommand(self.json(["cmd": "ping"]))
        }

        locationManager.delegate = self
        locationManager.desiredAccuracy = kCLLocationAccuracyHundredMeters
        locationManager.distanceFilter = 10
    }

    func startAdvertising() {
        bluetooth.start()
    }

    func stopAdvertising() {
        bluetooth.stop()
    }

    func pushTimeNow() {
        bluetooth.pushTime()
    }

    /// Pushes Wi-Fi credentials to Glass via the setup_hook (`SETUP_WIFI`).
    func setWifi(ssid: String, psk: String, encryption: WifiEncryption) {
        bluetooth.sendCommand(json([
            "cmd": "wifi", "ssid": ssid, "psk": psk, "enc": encryption.rawValue,
        ]))
    }

    /// Runs Glass's companion setup flow (Wi-Fi + clock + setup completion).
    func runSetup(email: String, ssid: String, psk: String,
                  encryption: WifiEncryption, enableAdb: Bool) {
        bluetooth.sendCommand(json([
            "cmd": "setup", "email": email, "ssid": ssid, "psk": psk,
            "enc": encryption.rawValue, "adb": enableAdb,
        ]))
    }

    func setupString(email: String, ssid: String, psk: String,
                     encryption: WifiEncryption, enableAdb: Bool) -> String {
        MyGlassPayloads.setupString(email: email, encryption: encryption, ssid: ssid,
                                    psk: psk, enableAdb: enableAdb)
    }

    private func json(_ dictionary: [String: Any]) -> String {
        if let data = try? JSONSerialization.data(withJSONObject: dictionary),
           let string = String(data: data, encoding: .utf8) {
            return string
        }
        return "{}"
    }

    // MARK: - Location passthrough

    func sendPhoneInfo() {
        let device = UIDevice.current
        device.isBatteryMonitoringEnabled = true
        let battery = device.batteryLevel >= 0 ? Int(device.batteryLevel * 100) : -1
        let charging = device.batteryState == .charging || device.batteryState == .full
        bluetooth.sendCommand(json([
            "cmd": "phone",
            "name": device.name,
            "model": device.model,
            "battery": battery,
            "charging": charging,
        ]))
    }
    // MARK: - Media (Apple Music via MusicKit)

    func setMediaSharing(_ enabled: Bool) {
        mediaEnabled = enabled
        isSharingMedia = enabled
        UserDefaults.standard.set(enabled, forKey: Self.mediaKey)
        if enabled {
            media.start()
        } else {
            bluetooth.sendCommand(json(["cmd": "media_stop"]))
        }
    }

    private func pushMedia(_ state: MediaController.State) {
        if state.title.isEmpty && !state.isPlaying {
            bluetooth.sendCommand(json(["cmd": "media_stop"]))
            return
        }
        bluetooth.sendCommand(json([
            "cmd": "media_state",
            "title": state.title,
            "artist": state.artist,
            "playing": state.isPlaying,
        ]))
    }

    private func handleUplink(_ text: String) {
        lastUplink = text
        NSLog("MyGlassCompanion: handleUplink %@", text)
        guard let data = text.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return
        }
        if let command = object["m"] as? String {
            // Acknowledge back to Glass so the round-trip is observable in its logs.
            bluetooth.sendCommand(json(["cmd": "media_ack", "m": command]))
            media.handle(command: command)
        }
    }

    func setLocationSharing(_ enabled: Bool) {
        isSharingLocation = enabled
        if enabled {
            requestLocationAuthorization()
        } else {
            locationManager.stopUpdatingLocation()
        }
    }

    private func requestLocationAuthorization() {
        let authStatus = locationManager.authorizationStatus
        switch authStatus {
        case .notDetermined:
            locationManager.requestWhenInUseAuthorization()
        case .authorizedWhenInUse, .authorizedAlways:
            locationManager.startUpdatingLocation()
        default:
            status = "Location permission denied"
            isSharingLocation = false
        }
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let authStatus = manager.authorizationStatus
        if isSharingLocation,
           authStatus == .authorizedWhenInUse || authStatus == .authorizedAlways {
            manager.startUpdatingLocation()
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last, isAdvertising else { return }
        bluetooth.sendCommand(json([
            "cmd": "location",
            "lat": location.coordinate.latitude,
            "lon": location.coordinate.longitude,
            "acc": location.horizontalAccuracy,
            "t": Int(location.timestamp.timeIntervalSince1970 * 1000),
        ]))
        DispatchQueue.main.async { self.lastPushed = Date() }
    }

    /// Schedules a local notification so we can verify the iPhone -> Glass ANCS path.
    func sendTestNotification() {
        let center = UNUserNotificationCenter.current()
        center.requestAuthorization(options: [.alert, .sound]) { [weak self] granted, _ in
            guard granted else {
                DispatchQueue.main.async { self?.status = "Notifications not authorized" }
                return
            }
            let content = UNMutableNotificationContent()
            content.title = "MyGlass test"
            content.body = "This should appear on Glass."
            content.sound = .default
            let trigger = UNTimeIntervalNotificationTrigger(timeInterval: 3, repeats: false)
            let request = UNNotificationRequest(
                identifier: UUID().uuidString, content: content, trigger: trigger)
            center.add(request)
        }
    }

    // Show the banner even while the app is in the foreground so iOS delivers it.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler:
            @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .list, .sound])
    }

    private static let formatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss"
        return formatter
    }()
}
