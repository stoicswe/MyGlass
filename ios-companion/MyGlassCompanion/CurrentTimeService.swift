import Foundation
import CoreBluetooth
import UIKit

/// UUIDs used by the Glass-side MyGlass app.
///
/// `currentTimeService` / `currentTime` are the standard Bluetooth SIG Current Time
/// Service. `myGlassService` and friends are a small custom service that lets Glass
/// identify the phone and read the clock as Unix epoch milliseconds.
enum MyGlassUUIDs {
    static let currentTimeService = CBUUID(string: "1805")
    static let currentTime = CBUUID(string: "2A2B")
    static let localTimeInformation = CBUUID(string: "2A0F")

    static let myGlassService = CBUUID(string: "A100")
    static let deviceName = CBUUID(string: "A101")
    static let epochMillis = CBUUID(string: "A102")
    static let timeZone = CBUUID(string: "A103")
    static let command = CBUUID(string: "A104")
}

/// Wi-Fi encryption ordinals as understood by Glass's `WifiHelper.Encryption`.
enum WifiEncryption: Int, CaseIterable, Identifiable {
    case none = 0
    case wpa = 1
    case wep = 2

    var id: Int { rawValue }
    var name: String {
        switch self {
        case .none: return "NONE"
        case .wpa: return "WPA"
        case .wep: return "WEP"
        }
    }
}

/// Builders for the characteristic payloads.
enum MyGlassPayloads {

    static let utcCalendar: Calendar = {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        return calendar
    }()

    /// The 10-byte Current Time characteristic payload (year..adjust reason).
    static func currentTime(from date: Date = Date()) -> Data {
        let c = utcCalendar.dateComponents(
            [.year, .month, .day, .hour, .minute, .second, .weekday, .nanosecond],
            from: date)
        var bytes = [UInt8](repeating: 0, count: 10)
        let year = UInt16(c.year ?? 2000)
        bytes[0] = UInt8(year & 0xFF)
        bytes[1] = UInt8((year >> 8) & 0xFF)
        bytes[2] = UInt8(c.month ?? 1)
        bytes[3] = UInt8(c.day ?? 1)
        bytes[4] = UInt8(c.hour ?? 0)
        bytes[5] = UInt8(c.minute ?? 0)
        bytes[6] = UInt8(c.second ?? 0)
        // Gregorian weekday is 1=Sunday..7=Saturday; CTS wants 1=Monday..7=Sunday.
        let weekday = c.weekday ?? 1
        bytes[7] = UInt8(((weekday + 5) % 7) + 1)
        bytes[8] = UInt8((c.nanosecond ?? 0) / 3_906_250) // 1/256 of a second
        bytes[9] = 0x00 // adjust reason: manual time update
        return Data(bytes)
    }

    /// The 8-byte little-endian Unix epoch in milliseconds.
    static func epochMillis(from date: Date = Date()) -> Data {
        var value = Int64(date.timeIntervalSince1970 * 1000).littleEndian
        return Data(bytes: &value, count: 8)
    }

    static func deviceNameData() -> Data {
        #if canImport(UIKit)
        return Data(UIDevice.current.name.utf8)
        #else
        return Data("MyGlass Companion".utf8)
        #endif
    }

    /// The standard Local Time Information characteristic payload (0x2A0F): a signed
    /// time-zone offset in 15-minute units from UTC (excluding DST), followed by the
    /// DST offset in the same units. The effective local offset is the sum of the two.
    static func localTimeInformation(from date: Date = Date()) -> Data {
        let zone = TimeZone.current
        let standardUnits = zone.secondsFromGMT(for: january(of: date)) / 900
        let currentUnits = zone.secondsFromGMT(for: date) / 900
        let dstUnits = currentUnits - standardUnits
        var bytes = [UInt8](repeating: 0, count: 2)
        bytes[0] = UInt8(bitPattern: Int8(clamping: standardUnits))
        bytes[1] = UInt8(clamping: dstUnits)
        return Data(bytes)
    }

    /// The IANA time-zone identifier (e.g. "America/New_York").
    static func timeZoneIdentifier() -> Data {
        return Data(TimeZone.current.identifier.utf8)
    }

    /// The version-1 setup string understood by Glass's `SetupHelper.parseBarcodeValue`.
    static func setupString(email: String, encryption: WifiEncryption, ssid: String,
                            psk: String, enableAdb: Bool) -> String {
        var items = [
            "version=1",
            "email=\(encode(email))",
            "enc=\(encryption.rawValue)",
            "ssid=\(encode(ssid))",
            "psk=\(encode(psk))",
            "adb=\(enableAdb)",
        ]
        items.removeAll { $0.isEmpty }
        return items.joined(separator: "&")
    }

    private static func encode(_ value: String) -> String {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        return value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
    }

    private static func january(of date: Date) -> Date {
        var components = utcCalendar.dateComponents([.year], from: date)
        components.month = 1
        components.day = 15
        components.hour = 12
        return utcCalendar.date(from: components) ?? date
    }
}
