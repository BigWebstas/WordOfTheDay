import SwiftUI

@main
struct WordOfTheDayApp: App {
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var saved = SavedWords()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(saved)
                .onAppear { WordNotifier.requestAuthorization() }
        }
        .onChange(of: scenePhase) { phase in
            if phase == .background { WordNotifier.scheduleRefresh() }
        }
        .backgroundTask(.appRefresh(WordNotifier.refreshTaskID)) {
            WordNotifier.scheduleRefresh()
            await WordNotifier.notifyIfNew()
        }
    }
}
