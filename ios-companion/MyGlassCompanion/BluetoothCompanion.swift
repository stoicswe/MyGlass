import Foundation
import CoreBluetooth

/// Advertises the Current Time Service and the MyGlass custom service so the Glass
/// app can read the phone clock.
///
/// Note on notifications: iOS does not let applications read other apps'
/// notifications. Real iPhone notifications reach Glass through Apple's Notification
/// Center Service (ANCS), which iOS exposes automatically to a bonded BLE accessory.
/// This app therefore does not forward notifications itself; it only provides the
/// clock channel and confirms that the phone is ready to act as the ANCS source.
final class BluetoothCompanion: NSObject, CBPeripheralManagerDelegate {

    /// Called with human-readable status updates (always delivered on the main queue).
    var onStatus: ((String) -> Void)?
    /// Called whenever advertising starts/stops (main queue).
    var onAdvertisingChanged: ((Bool) -> Void)?
    /// Called when the clock is pushed to subscribers (main queue).
    var onTimePushed: ((Date) -> Void)?
    /// Called when Glass first subscribes (main queue).
    var onCentralSubscribed: (() -> Void)?

    private var peripheralManager: CBPeripheralManager!
    private var wantsAdvertising = false

    private var currentTimeCharacteristic: CBMutableCharacteristic?
    private var epochCharacteristic: CBMutableCharacteristic?
    private var deviceNameCharacteristic: CBMutableCharacteristic?
    private var commandCharacteristic: CBMutableCharacteristic?

    override init() {
        super.init()
        NSLog("MyGlassCompanion: BluetoothCompanion init")
        peripheralManager = CBPeripheralManager(delegate: self, queue: nil)
    }

    // MARK: - Public API

    func start() {
        wantsAdvertising = true
        guard peripheralManager.state == .poweredOn else {
            report("Bluetooth not ready (\(stateDescription(peripheralManager.state)))")
            return
        }
        addServicesIfNeeded()
        startAdvertising()
    }

    func stop() {
        wantsAdvertising = false
        if peripheralManager.isAdvertising {
            peripheralManager.stopAdvertising()
        }
        report("Stopped advertising")
        notifyAdvertising(false)
    }

    /// Re-reads the system clock and pushes it to any subscribed Glass device.
    @discardableResult
    func pushTime() -> Date {
        let now = Date()
        let currentTime = MyGlassPayloads.currentTime(from: now)
        let epoch = MyGlassPayloads.epochMillis(from: now)

        if let characteristic = currentTimeCharacteristic {
            characteristic.value = currentTime
            peripheralManager.updateValue(currentTime, for: characteristic, onSubscribedCentrals: nil)
        }
        if let characteristic = epochCharacteristic {
            characteristic.value = epoch
            peripheralManager.updateValue(epoch, for: characteristic, onSubscribedCentrals: nil)
        }
        DispatchQueue.main.async { self.onTimePushed?(now) }
        return now
    }

    // MARK: - Command channel (phone -> Glass)

    /// Sends a JSON command to Glass over the notify command characteristic.
    func sendCommand(_ json: String) {
        guard let characteristic = commandCharacteristic else {
            NSLog("MyGlassCompanion: sendCommand skipped (not ready)")
            return
        }
        let sent = peripheralManager.updateValue(
            Data(json.utf8), for: characteristic, onSubscribedCentrals: nil)
        NSLog("MyGlassCompanion: sendCommand sent=%d %@", sent ? 1 : 0, json)
    }

    // MARK: - Service setup

    private func addServicesIfNeeded() {
        guard currentTimeCharacteristic == nil else { return }

        let currentTime = CBMutableCharacteristic(
            type: MyGlassUUIDs.currentTime,
            properties: [.read, .notify],
            // A characteristic with .notify must not have a cached value; it is
            // served dynamically in peripheralManager(_:didReceiveRead:).
            value: nil,
            permissions: [.readable])
        let localTime = CBMutableCharacteristic(
            type: MyGlassUUIDs.localTimeInformation,
            properties: [.read],
            value: MyGlassPayloads.localTimeInformation(),
            permissions: [.readable])
        let currentTimeService = CBMutableService(type: MyGlassUUIDs.currentTimeService, primary: true)
        currentTimeService.characteristics = [currentTime, localTime]

        let epoch = CBMutableCharacteristic(
            type: MyGlassUUIDs.epochMillis,
            properties: [.read, .notify],
            value: nil,
            permissions: [.readable])
        let deviceName = CBMutableCharacteristic(
            type: MyGlassUUIDs.deviceName,
            properties: [.read],
            value: MyGlassPayloads.deviceNameData(),
            permissions: [.readable])
        let timeZone = CBMutableCharacteristic(
            type: MyGlassUUIDs.timeZone,
            properties: [.read],
            value: MyGlassPayloads.timeZoneIdentifier(),
            permissions: [.readable])
        let command = CBMutableCharacteristic(
            type: MyGlassUUIDs.command,
            properties: [.read, .notify],
            value: nil,
            permissions: [.readable])
        let myGlassService = CBMutableService(type: MyGlassUUIDs.myGlassService, primary: true)
        myGlassService.characteristics = [epoch, deviceName, timeZone, command]

        currentTimeCharacteristic = currentTime
        epochCharacteristic = epoch
        deviceNameCharacteristic = deviceName
        commandCharacteristic = command

        peripheralManager.add(currentTimeService)
        peripheralManager.add(myGlassService)
    }

    private func startAdvertising() {
        NSLog("MyGlassCompanion: startAdvertising")
        peripheralManager.startAdvertising([
            CBAdvertisementDataServiceUUIDsKey: [
                MyGlassUUIDs.currentTimeService,
                MyGlassUUIDs.myGlassService,
            ],
            CBAdvertisementDataLocalNameKey: "MyGlass Companion",
        ])
    }

    // MARK: - CBPeripheralManagerDelegate

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        report("Bluetooth state: \(stateDescription(peripheral.state))")
        if peripheral.state == .poweredOn && wantsAdvertising {
            addServicesIfNeeded()
            startAdvertising()
        } else if peripheral.state != .poweredOn {
            notifyAdvertising(false)
        }
    }

    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        if let error = error {
            report("Advertising failed: \(error.localizedDescription)")
            notifyAdvertising(false)
        } else {
            report("Advertising: waiting for Glass to connect")
            notifyAdvertising(true)
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager,
                           central: CBCentral,
                           didSubscribeTo characteristic: CBCharacteristic) {
        report("Glass connected")
        DispatchQueue.main.async { self.onCentralSubscribed?() }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager,
                           central: CBCentral,
                           didUnsubscribeFrom characteristic: CBCharacteristic) {
        report("Glass disconnected")
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        let uuid = request.characteristic.uuid
        let value: Data?
        if uuid == MyGlassUUIDs.currentTime {
            value = MyGlassPayloads.currentTime()
        } else if uuid == MyGlassUUIDs.localTimeInformation {
            value = MyGlassPayloads.localTimeInformation()
        } else if uuid == MyGlassUUIDs.epochMillis {
            value = MyGlassPayloads.epochMillis()
        } else if uuid == MyGlassUUIDs.deviceName {
            value = MyGlassPayloads.deviceNameData()
        } else if uuid == MyGlassUUIDs.timeZone {
            value = MyGlassPayloads.timeZoneIdentifier()
        } else if uuid == MyGlassUUIDs.command {
            value = Data()
        } else {
            value = nil
        }

        if let value = value, request.offset <= value.count {
            request.value = value.subdata(in: request.offset ..< value.count)
            peripheral.respond(to: request, withResult: .success)
        } else {
            peripheral.respond(to: request, withResult: .readNotPermitted)
        }
    }

    // MARK: - Helpers

    private func report(_ message: String) {
        NSLog("MyGlassCompanion: %@", message)
        DispatchQueue.main.async { self.onStatus?(message) }
    }

    private func notifyAdvertising(_ value: Bool) {
        DispatchQueue.main.async { self.onAdvertisingChanged?(value) }
    }

    private func stateDescription(_ state: CBManagerState) -> String {
        switch state {
        case .poweredOn: return "on"
        case .poweredOff: return "off"
        case .unauthorized: return "unauthorized"
        case .unsupported: return "unsupported"
        case .resetting: return "resetting"
        case .unknown: return "unknown"
        @unknown default: return "unknown"
        }
    }
}
