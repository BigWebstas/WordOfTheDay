import Foundation

private let feedURL = URL(string: "https://www.merriam-webster.com/wotd/feed/rss2")!

struct Word: Identifiable, Codable {
    let word: String
    let date: String
    let shortDefinition: String
    let pronunciation: String
    let meaning: String
    let context: String
    let example: String
    let didYouKnow: String

    /// Date + word: the same word can come around again in a later year.
    var id: String { "\(date) \(word)" }

    var shareText: String {
        var text = "Word of the Day: \(word)"
        if !pronunciation.isEmpty { text += "  \(pronunciation)" }
        text += "\n\(meaning)"
        if !context.isEmpty { text += "\n\n\"\(context)\"" }
        let slug = word.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? word
        return text + "\n\nhttps://www.merriam-webster.com/dictionary/\(slug)"
    }

    /// The same markdown the Android app exports; rating 0 leaves the rating line out.
    func markdown(rating: Int) -> String {
        var parts = ["# \(word)"]
        parts.append([pronunciation.isEmpty ? nil : "`\(pronunciation)`", date].compactMap { $0 }.joined(separator: " · "))
        if rating > 0 { parts.append("Rating: " + String(repeating: "★", count: rating) + String(repeating: "☆", count: 5 - rating)) }
        if !meaning.isEmpty { parts.append("## Meaning\n\n\(meaning)") }
        if !context.isEmpty { parts.append("## In context\n\n> \(context)") }
        if !example.isEmpty { parts.append("## Example\n\n> \(example)") }
        if !didYouKnow.isEmpty { parts.append("## Did you know?\n\n\(didYouKnow)") }
        let slug = word.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? word
        parts.append("[Merriam-Webster entry](https://www.merriam-webster.com/dictionary/\(slug))")
        return parts.joined(separator: "\n\n") + "\n"
    }

}


func fetchWords() async throws -> [Word] {
    let (data, response) = try await URLSession.shared.data(from: feedURL)
    if let http = response as? HTTPURLResponse, http.statusCode != 200 {
        throw URLError(.badServerResponse)
    }
    let parser = FeedParser()
    let xml = XMLParser(data: data)
    xml.delegate = parser
    guard xml.parse() else { throw xml.parserError ?? URLError(.cannotParseResponse) }
    return parser.words
}

private final class FeedParser: NSObject, XMLParserDelegate {
    var words: [Word] = []
    private var fields: [String: String] = [:]
    private var element = ""
    private var text = ""
    private var inItem = false

    func parser(_ parser: XMLParser, didStartElement name: String, namespaceURI: String?,
                qualifiedName: String?, attributes: [String: String] = [:]) {
        if name == "item" { inItem = true; fields = [:] }
        element = name
        text = ""
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) { text += string }

    func parser(_ parser: XMLParser, foundCDATA data: Data) {
        text += String(decoding: data, as: UTF8.self)
    }

    func parser(_ parser: XMLParser, didEndElement name: String, namespaceURI: String?,
                qualifiedName: String?) {
        guard inItem else { return }
        if name == "item" {
            inItem = false
            words.append(makeWord(fields))
        } else if name == element {
            fields[name] = text.trimmingCharacters(in: .whitespacesAndNewlines)
        }
    }
}

private func makeWord(_ fields: [String: String]) -> Word {
    let shortDefinition = fields["merriam:shortdef"] ?? ""
    // The description is one text blob; split it on its fixed markers.
    let lines = htmlToText(fields["description"] ?? "").split(separator: "\n")
        .map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
    let iExamples = lines.firstIndex(of: "Examples:")
    let iKnow = lines.firstIndex(of: "Did you know?")
    let headEnd = [iExamples, iKnow].compactMap { $0 }.min() ?? lines.count
    let head = lines[..<headEnd].filter {
        !$0.hasPrefix("Merriam-Webster's Word of the Day") && !$0.hasPrefix("See the entry")
    }
    let pronunciation = head.first { $0.contains("\\") } ?? ""
    let context = head.first { $0.hasPrefix("//") } ?? ""
    let meaning = head.filter { $0 != pronunciation && $0 != context }.joined(separator: " ")
    let exampleEnd = iKnow.flatMap { k in iExamples.map { k > $0 ? k : lines.count } } ?? lines.count
    return Word(
        word: fields["title"] ?? "",
        date: (fields["pubDate"] ?? "").split(separator: " ").prefix(4).joined(separator: " "),
        shortDefinition: shortDefinition,
        pronunciation: pronunciation.firstIndex(of: "\\")
            .map { String(pronunciation[$0...]).replacingOccurrences(of: "\\s*[•*]\\s*", with: " ", options: .regularExpression) }
            .map { $0.trimmingCharacters(in: .whitespaces) } ?? "",
        meaning: meaning.isEmpty ? shortDefinition : meaning,
        context: context.drop { $0 == "/" }.trimmingCharacters(in: .whitespaces),
        example: iExamples.map { lines[($0 + 1)..<max($0 + 1, exampleEnd)].joined(separator: " ") } ?? "",
        didYouKnow: iKnow.map { lines[($0 + 1)...].joined(separator: " ") } ?? "")
}

private func htmlToText(_ html: String) -> String {
    var s = html
        .replacingOccurrences(of: "<br\\s*/?>|</p>|</div>", with: "\n", options: .regularExpression)
        .replacingOccurrences(of: "<[^>]+>", with: "", options: .regularExpression)
    for (entity, char) in [("&nbsp;", " "), ("&#149;", "•"), ("&gt;", ">"), ("&lt;", "<"),
                           ("&quot;", "\""), ("&#39;", "'"), ("&amp;", "&")] {
        s = s.replacingOccurrences(of: entity, with: char)
    }
    return s
        .split(separator: "\n", omittingEmptySubsequences: false)
        .map { $0.trimmingCharacters(in: .whitespaces) }
        .joined(separator: "\n")
        .replacingOccurrences(of: "\n{3,}", with: "\n\n", options: .regularExpression)
        .trimmingCharacters(in: .whitespacesAndNewlines)
}
