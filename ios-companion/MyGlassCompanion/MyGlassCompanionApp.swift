import SwiftUI

@main
struct MyGlassCompanionApp: App {
    @StateObject private var model = CompanionModel()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(model)
        }
    }
}
