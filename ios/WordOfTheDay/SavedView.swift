import SwiftUI

struct SavedView: View {
    @EnvironmentObject private var saved: SavedWords
    @State private var expanded: Word.ID?
    @State private var exporting = false
    @State private var importing = false
    @State private var message: String?

    var body: some View {
        NavigationStack {
            Group {
                if saved.items.isEmpty {
                    Text("Rate a word to save it here.").foregroundStyle(.secondary)
                } else {
                    List(saved.ranked) { item in
                        WordRow(word: item.word, expanded: expanded == item.id)
                            .onTapGesture { expanded = expanded == item.id ? nil : item.id }
                    }
                }
            }
            .navigationTitle("Saved")
            .toolbar {
                Menu {
                    Button("Back up") { exporting = true }
                    Button("Restore") { importing = true }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
            }
        }
        .fileExporter(isPresented: $exporting,
                      document: BackupDocument(data: (try? saved.backupData()) ?? Data()),
                      contentType: .json,
                      defaultFilename: "word-of-the-day-backup") { result in
            switch result {
            case .success: message = "Backup saved"
            case .failure(let error): message = "Backup failed: \(error.localizedDescription)"
            }
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.json]) { result in
            do {
                let url = try result.get()
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                message = "Imported \(try saved.merge(Data(contentsOf: url))) words"
            } catch {
                message = "Import failed: \(error.localizedDescription)"
            }
        }
        .alert(message ?? "", isPresented: Binding(get: { message != nil },
                                                  set: { if !$0 { message = nil } })) {}
    }
}
