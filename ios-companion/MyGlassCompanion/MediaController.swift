import Foundation
import MusicKit
import Combine

/// Observes and controls Apple Music playback via MusicKit so the Glass music card can
/// show the current track and send play/pause/next/previous.
///
/// Note: iOS does not expose a general "now playing" API for *other* apps, so this only
/// covers Apple Music (the system music player).
final class MediaController {

    struct State {
        var title: String
        var artist: String
        var isPlaying: Bool
    }

    /// Called whenever the track or playback state changes (main queue).
    var onStateChanged: ((State) -> Void)?

    private let player = SystemMusicPlayer.shared
    private var cancellables = Set<AnyCancellable>()
    private var started = false

    /// Requests Apple Music authorization and begins observing playback.
    func start() {
        guard !started else { return }
        started = true
        Task { @MainActor in
            let status = await MusicAuthorization.request()
            NSLog("MyGlassCompanion: MusicAuthorization = %@", String(describing: status))
            guard status == .authorized else {
                self.onStateChanged?(State(title: "", artist: "", isPlaying: false))
                return
            }
            self.observe()
            self.pushState()
        }
    }

    /// Handles a control command from Glass: "toggle", "next" or "prev".
    func handle(command: String) {
        Task { @MainActor in
            switch command {
            case "toggle":
                if self.player.state.playbackStatus == .playing {
                    self.player.pause()
                } else {
                    try? await self.player.play()
                }
            case "next":
                try? await self.player.skipToNextEntry()
            case "prev":
                try? await self.player.skipToPreviousEntry()
            default:
                break
            }
            self.pushState()
        }
    }

    @MainActor
    private func observe() {
        player.state.objectWillChange
            .receive(on: RunLoop.main)
            .sink { [weak self] _ in self?.pushState() }
            .store(in: &cancellables)
        player.queue.objectWillChange
            .receive(on: RunLoop.main)
            .sink { [weak self] _ in self?.pushState() }
            .store(in: &cancellables)
    }

    @MainActor
    private func pushState() {
        let playing = player.state.playbackStatus == .playing
        let entry = player.queue.currentEntry
        let title = entry?.title ?? ""
        let artist = entry?.subtitle ?? ""
        onStateChanged?(State(title: title, artist: artist, isPlaying: playing))
    }
}
