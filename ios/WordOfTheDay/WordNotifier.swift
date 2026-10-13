import BackgroundTasks
import UserNotifications

/// Checks the feed in the background; notifies once per new word.
/// iOS decides when refresh tasks actually run, so delivery time is best-effort.
enum WordNotifier {
    static let refreshTaskID = "com.example.wordoftheday.refresh"
    private static let lastSeenKey = "lastSeenWord"

    static func requestAuthorization() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    static func scheduleRefresh() {
        let request = BGAppRefreshTaskRequest(identifier: refreshTaskID)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 6 * 3600)
        try? BGTaskScheduler.shared.submit(request)
    }

    /// Called when the user has seen [word] in the app, so it is not notified afterwards.
    static func markSeen(_ word: String) {
        UserDefaults.standard.set(word, forKey: lastSeenKey)
    }

    static func notifyIfNew() async {
        guard let word = try? await fetchWords().first,
              UserDefaults.standard.string(forKey: lastSeenKey) != word.word else { return }
        let content = UNMutableNotificationContent()
        content.title = "Word of the Day: \(word.word)"
        content.body = word.shortDefinition
        content.sound = .default
        let request = UNNotificationRequest(identifier: "word-of-the-day", content: content, trigger: nil)
        try? await UNUserNotificationCenter.current().add(request)
        markSeen(word.word)
    }
}
