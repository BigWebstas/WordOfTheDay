import SwiftUI
import UniformTypeIdentifiers

/// A rated word. Encodes flat (word fields + "rating") so backups match the Android app's.
struct SavedWord: Identifiable, Codable {
    let word: Word
    let rating: Int

    var id: String { word.id }

    private enum CodingKeys: String, CodingKey { case rating }

    init(word: Word, rating: Int) {
        self.word = word
        self.rating = rating
    }

    init(from decoder: Decoder) throws {
        word = try Word(from: decoder)
        rating = try decoder.container(keyedBy: CodingKeys.self).decode(Int.self, forKey: .rating)
    }

    func encode(to encoder: Encoder) throws {
        try word.encode(to: encoder)
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(rating, forKey: .rating)
    }
}

private struct Backup: Codable {
    let version: Int
    let words: [SavedWord]
}

/// Rated words, newest first. A word is saved by rating it and removed by tapping its rating again.
final class SavedWords: ObservableObject {
    private static let key = "savedWords"

    @Published private(set) var items: [SavedWord]

    init() {
        items = UserDefaults.standard.data(forKey: Self.key)
            .flatMap { try? JSONDecoder().decode([SavedWord].self, from: $0) } ?? []
    }

    /// Best rated first; newest first among equal ratings.
    var ranked: [SavedWord] {
        items.enumerated()
            .sorted { $0.element.rating != $1.element.rating
                ? $0.element.rating > $1.element.rating : $0.offset < $1.offset }
            .map(\.element)
    }

    func rating(_ word: Word) -> Int {
        items.first { $0.id == word.id }?.rating ?? 0
    }

    func rate(_ word: Word, _ stars: Int) {
        let next = stars == rating(word) ? 0 : stars
        items.removeAll { $0.id == word.id }
        if next > 0 { items.insert(SavedWord(word: word, rating: next), at: 0) }
        save()
    }

    func backupData() throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        return try encoder.encode(Backup(version: 1, words: items))
    }

    /// Merges a backup into the list (backup wins on conflicts); returns how many words it held.
    func merge(_ data: Data) throws -> Int {
        let imported = try JSONDecoder().decode(Backup.self, from: data).words
            .filter { (1...5).contains($0.rating) }
        let ids = Set(imported.map(\.id))
        items = imported + items.filter { !ids.contains($0.id) }
        save()
        return imported.count
    }

    private func save() {
        UserDefaults.standard.set(try? JSONEncoder().encode(items), forKey: Self.key)
    }
}

struct BackupDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.json] }

    let data: Data

    init(data: Data) { self.data = data }

    init(configuration: ReadConfiguration) throws {
        data = configuration.file.regularFileContents ?? Data()
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}
