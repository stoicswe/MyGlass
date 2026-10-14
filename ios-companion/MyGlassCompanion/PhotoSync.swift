import Foundation
import Photos
import UIKit

/// Browses the photos and videos on Glass (over Wi-Fi, from the Glass app's small HTTP
/// server) and saves the ones you choose into the iPhone Photos library.
///
/// Bluetooth LE is far too slow for this, so the phone talks to Glass over the local
/// network at the address Glass reports in its `glass_info` uplink message.
@MainActor
final class PhotoSync: ObservableObject {

    struct Item: Identifiable, Decodable, Equatable {
        var name: String
        var size: Int64
        var type: String
        var id: String { name }

        var isVideo: Bool { type == "video" }
    }

    @Published var items: [Item] = []
    @Published var thumbnails: [String: UIImage] = [:]
    @Published var savedNames: Set<String> = []
    @Published var isLoading = false
    @Published var savingName: String?
    @Published var status: String = ""
    @Published var lastError: String?

    private static let syncedKey = "syncedPhotoNames"

    init() {
        savedNames = Set(UserDefaults.standard.stringArray(forKey: Self.syncedKey) ?? [])
    }

    var unsavedCount: Int {
        items.filter { !savedNames.contains($0.name) }.count
    }

    func isSaved(_ item: Item) -> Bool { savedNames.contains(item.name) }

    // MARK: - Loading

    /// Loads the list of items on Glass and starts downloading their thumbnails.
    func refresh(host: String, port: Int) async {
        guard !isLoading else { return }
        isLoading = true
        lastError = nil
        status = "Loading list\u{2026}"
        defer { isLoading = false }

        guard let list = await fetchList(host: host, port: port) else {
            lastError = "Couldn't reach Glass at \(host):\(port). Make sure this iPhone is on the same Wi-Fi as Glass."
            status = ""
            return
        }
        items = list
        status = list.isEmpty ? "Nothing on Glass yet"
            : "\(list.count) item\(list.count == 1 ? "" : "s") on Glass"
        await loadThumbnails(host: host, port: port)
    }

    private func loadThumbnails(host: String, port: Int) async {
        for item in items where thumbnails[item.name] == nil {
            if let image = await fetchThumbnail(name: item.name, host: host, port: port) {
                thumbnails[item.name] = image
            }
        }
    }

    // MARK: - Saving individual items

    func save(_ item: Item, host: String, port: Int) async {
        guard !savedNames.contains(item.name), savingName == nil else { return }
        savingName = item.name
        defer { savingName = nil }
        lastError = nil

        guard await requestPermission() else {
            lastError = "Photos access was denied. Enable it in Settings \u{203A} MyGlass Companion."
            return
        }
        guard let data = await download(name: item.name, host: host, port: port) else {
            lastError = "Download failed for \(item.name)."
            return
        }
        if await saveToPhotos(data: data, isVideo: item.isVideo, name: item.name) {
            savedNames.insert(item.name)
            persistSaved()
            status = "Saved \(item.name) to Photos"
        } else {
            lastError = "Couldn't save \(item.name) to Photos."
        }
    }

    func saveAllNew(host: String, port: Int) async {
        for item in items where !savedNames.contains(item.name) {
            await save(item, host: host, port: port)
        }
    }

    /// Full-resolution image for the preview (videos return their thumbnail frame).
    func fullImage(name: String, host: String, port: Int) async -> UIImage? {
        if let data = await download(name: name, host: host, port: port),
           let image = UIImage(data: data) {
            return image
        }
        return thumbnails[name]
    }

    private func persistSaved() {
        UserDefaults.standard.set(Array(savedNames), forKey: Self.syncedKey)
    }

    // MARK: - Networking

    private func fetchList(host: String, port: Int) async -> [Item]? {
        guard let data = await get(path: "/list", host: host, port: port, timeout: 10) else {
            return nil
        }
        return try? JSONDecoder().decode([Item].self, from: data)
    }

    private func fetchThumbnail(name: String, host: String, port: Int) async -> UIImage? {
        let encoded = name.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? name
        guard let data = await get(path: "/thumb/\(encoded)", host: host, port: port, timeout: 20) else {
            return nil
        }
        return UIImage(data: data)
    }

    private func download(name: String, host: String, port: Int) async -> Data? {
        let encoded = name.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? name
        return await get(path: "/file/\(encoded)", host: host, port: port, timeout: 180)
    }

    private func get(path: String, host: String, port: Int, timeout: TimeInterval) async -> Data? {
        guard let url = URL(string: "http://\(host):\(port)\(path)") else { return nil }
        var request = URLRequest(url: url)
        request.timeoutInterval = timeout
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            if let http = response as? HTTPURLResponse, http.statusCode != 200 {
                return nil
            }
            return data
        } catch {
            NSLog("MyGlassCompanion: GET %@ failed %@", path, String(describing: error))
            return nil
        }
    }

    // MARK: - Photos

    private func requestPermission() async -> Bool {
        let status = PHPhotoLibrary.authorizationStatus(for: .addOnly)
        if status == .authorized || status == .limited { return true }
        return await withCheckedContinuation { continuation in
            PHPhotoLibrary.requestAuthorization(for: .addOnly) { newStatus in
                continuation.resume(returning: newStatus == .authorized || newStatus == .limited)
            }
        }
    }

    private func saveToPhotos(data: Data, isVideo: Bool, name: String) async -> Bool {
        // Photos wants a file URL for both photos and videos.
        let ext = (name as NSString).pathExtension.isEmpty
            ? (isVideo ? "mp4" : "jpg") : (name as NSString).pathExtension
        let tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
            .appendingPathExtension(ext)
        do {
            try data.write(to: tmp)
        } catch {
            return false
        }
        defer { try? FileManager.default.removeItem(at: tmp) }

        return await withCheckedContinuation { continuation in
            PHPhotoLibrary.shared().performChanges({
                let request = PHAssetCreationRequest.forAsset()
                let options = PHAssetResourceCreationOptions()
                options.originalFilename = name
                request.addResource(with: isVideo ? .video : .photo, fileURL: tmp, options: options)
            }, completionHandler: { success, error in
                if let error = error {
                    NSLog("MyGlassCompanion: save failed %@", String(describing: error))
                }
                continuation.resume(returning: success)
            })
        }
    }
}
