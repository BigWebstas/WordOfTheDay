import SwiftUI

/// One word: header with rating, plus the full text and a share button when expanded.
struct WordRow: View {
    let word: Word
    let expanded: Bool
    @EnvironmentObject private var saved: SavedWords

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(word.date).font(.caption).foregroundStyle(.secondary)
            Text(word.word).font(.title.bold())
            stars
            Text(word.shortDefinition)
            if expanded {
                Text(word.pronunciation).padding(.top, 8)
                section("Meaning", word.meaning)
                section("In context", word.context)
                section("Example", word.example)
                section("Did you know?", word.didYouKnow)
                HStack(spacing: 16) {
                    ShareLink(item: word.shareText)
                    ShareLink(item: word.markdown(rating: saved.rating(word))) {
                        Label("Share as Markdown", systemImage: "doc.text")
                    }
                }
                .buttonStyle(.borderless).padding(.top, 8)
            }
        }
        .contentShape(Rectangle())
    }

    private var stars: some View {
        HStack {
            ForEach(1...5, id: \.self) { star in
                Image(systemName: star <= saved.rating(word) ? "star.fill" : "star")
                    .font(.title2)
                    .foregroundStyle(.yellow)
                    .onTapGesture { saved.rate(word, star) }
                    .accessibilityLabel("Rate \(star) stars")
                    .accessibilityAddTraits(.isButton)
            }
        }
    }

    @ViewBuilder
    private func section(_ label: String, _ text: String) -> some View {
        if !text.isEmpty {
            Text(label).font(.subheadline.bold()).padding(.top, 8)
            Text(text)
        }
    }
}
