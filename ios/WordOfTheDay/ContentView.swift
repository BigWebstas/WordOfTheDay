import SwiftUI

struct ContentView: View {
    var body: some View {
        TabView {
            TodayView().tabItem { Label("Today", systemImage: "sun.max") }
            SavedView().tabItem { Label("Saved", systemImage: "star") }
        }
    }
}

private struct TodayView: View {
    private enum Load {
        case loading
        case done([Word])
        case failed(String)
    }

    @State private var load = Load.loading
    @State private var expanded: Word.ID?

    var body: some View {
        NavigationStack {
            Group {
                switch load {
                case .loading:
                    ProgressView()
                case .failed(let message):
                    VStack(spacing: 12) {
                        Text(message)
                        Button("Retry") { Task { await refresh() } }
                    }
                case .done(let words):
                    List(words) { word in
                        WordRow(word: word, expanded: expanded == word.id)
                            .onTapGesture { expanded = expanded == word.id ? nil : word.id }
                    }
                    .refreshable { await refresh() }
                }
            }
            .navigationTitle("Word of the Day")
        }
        .task { await refresh() }
    }

    private func refresh() async {
        let fetched: [Word]?
        var failure = ""
        do {
            fetched = try await fetchAndArchive()
        } catch {
            fetched = nil
            failure = error.localizedDescription
        }
        // Offline or feed down: the archive still has every word seen so far.
        let words = WordArchive.all()
        guard fetched != nil || !words.isEmpty else {
            load = .failed(failure)
            return
        }
        load = .done(words)
        if let latest = fetched?.first { WordNotifier.markSeen(latest.word) }
        if expanded == nil || !words.contains(where: { $0.id == expanded }) {
            expanded = words.first?.id
        }
    }
}
