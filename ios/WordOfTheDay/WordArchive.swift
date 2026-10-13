import Foundation

/// Every word the feed has ever shown us, kept on disk (newest first) so words survive
/// after the feed drops them.
enum WordArchive {
    private static let url = URL.applicationSupportDirectory.appending(path: "word-archive.json")

    static func all() -> [Word] {
        guard let data = try? Data(contentsOf: url) else { return [] }
        return (try? JSONDecoder().decode([Word].self, from: data)) ?? []
    }

    /// Adds new words and refreshes the text of known ones; `words` is newest first, as the feed sends it.
    static func store(_ words: [Word]) {
        var archive = all()
        let fresh = Dictionary(words.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        let known = Set(archive.map(\.id))
        archive = archive.map { fresh[$0.id] ?? $0 }
        archive.insert(contentsOf: words.filter { !known.contains($0.id) }, at: 0)
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        if let data = try? JSONEncoder().encode(archive) {
            try? data.write(to: url, options: .atomic)
        }
    }
}

/// Fetches the feed and archives it. Returns the feed's words, newest first.
func fetchAndArchive() async throws -> [Word] {
    let words = try await fetchWords()
    WordArchive.store(words)
    return words
}
