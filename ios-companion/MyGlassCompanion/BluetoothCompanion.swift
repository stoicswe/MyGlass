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
    /// Called when Glass writes a JSON command to the uplink characteristic (main queue).
    var onUplinkCommand: ((String) -> Void)?

    private var peripheralManager: CBPeripheralManager!
    private var wantsAdvertising = false

    private var currentTimeCharacteristic: CBMutableCharacteristic?
    private var epochCharacteristic: CBMutableCharacteristic?
    private var deviceNameCharacteristic: CBMutableCharacteristic?
    private var commandCharacteristic: CBMutableCharacteristic?
    private var uplinkCharacteristic: CBMutableCharacteristic?
    private var uplinkBuffer = Data()

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
    ///
    /// A BLE notification carries at most 20 payload bytes (ATT MTU 23 on Glass's
    /// Android 4.4 stack), so the command is framed with a trailing newline and split
    /// into 20-byte chunks that Glass reassembles.
    func sendCommand(_ json: String) {
        guard commandCharacteristic != nil else {
            NSLog("MyGlassCompanion: sendCommand skipped (not ready)")
            return
        }
        let framed = Array((json + "\n").utf8)
        var index = 0
        while index < framed.count {
            let end = min(index + 20, framed.count)
            commandQueue.append(Data(framed[index..<end]))
            index = end
        }
        NSLog("MyGlassCompanion: sendCommand queued %d chunk(s): %@",
              commandQueue.count, json)
        drainCommandQueue()
    }

    private var commandQueue: [Data] = []
    private var draining = false

    private func drainCommandQueue() {
        guard !draining, let characteristic = commandCharacteristic else { return }
        draining = true
        sendNextChunk(characteristic)
    }

    private func sendNextChunk(_ characteristic: CBMutableCharacteristic) {
        guard !commandQueue.isEmpty else {
            draining = false
            return
        }
        let chunk = commandQueue[0]
        if peripheralManager.updateValue(chunk, for: characteristic,
                                         onSubscribedCentrals: nil) {
            commandQueue.removeFirst()
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) {
                self.sendNextChunk(characteristic)
            }
        }
        // If updateValue returned false the transmit queue is full; we resume from
        // peripheralManagerIsReady(toUpdateSubscribers:).
    }

    func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
        if draining, let characteristic = commandCharacteristic {
            sendNextChunk(characteristic)
        } else {
            drainCommandQueue()
        }
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
        let uplink = CBMutableCharacteristic(
            type: MyGlassUUIDs.uplink,
            properties: [.write, .writeWithoutResponse],
            value: nil,
            permissions: [.writeable])
        let myGlassService = CBMutableService(type: MyGlassUUIDs.myGlassService, primary: true)
        myGlassService.characteristics = [epoch, deviceName, timeZone, command, uplink]

        currentTimeCharacteristic = currentTime
        epochCharacteristic = epoch
        deviceNameCharacteristic = deviceName
        commandCharacteristic = command
        uplinkCharacteristic = uplink

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

    func peripheralManager(_ peripheral: CBPeripheralManager,
                           didReceiveWrite requests: [CBATTRequest]) {
        for request in requests {
            if request.characteristic.uuid == MyGlassUUIDs.uplink, let data = request.value {
                appendUplink(data)
            }
            if request.characteristic.properties.contains(.write) {
                peripheral.respond(to: request, withResult: .success)
            }
        }
    }

    /// Reassembles newline-framed uplink writes (Glass chunks them to fit a 20-byte write).
    private func appendUplink(_ data: Data) {
        uplinkBuffer.append(data)
        while let newline = uplinkBuffer.firstIndex(of: 0x0A) {
            let frame = uplinkBuffer.subdata(in: uplinkBuffer.startIndex ..< newline)
            uplinkBuffer.removeSubrange(uplinkBuffer.startIndex ... newline)
            if let text = String(data: frame, encoding: .utf8), !text.isEmpty {
                NSLog("MyGlassCompanion: uplink %@", text)
                DispatchQueue.main.async { self.onUplinkCommand?(text) }
            }
        }
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
