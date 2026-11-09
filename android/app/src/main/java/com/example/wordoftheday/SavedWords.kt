package com.example.wordoftheday

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal const val BACKUP_FILENAME = "word-of-the-day-backup.json"

data class SavedWord(val word: Word, val rating: Int)

/**
 * Rated words, newest first. A word is saved by rating it and removed by tapping its rating again.
 * Backups use the same flat JSON as the iOS app: {"version":1,"words":[{...word fields, "rating":4}]}.
 */
@Stable
class SavedWords private constructor(private val appContext: Context) {
    private val prefs = appContext.getSharedPreferences("saved_words", Context.MODE_PRIVATE)
    private val backupDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val backupScope = CoroutineScope(SupervisorJob() + backupDispatcher)

    var items by mutableStateOf(parse(prefs.getString(KEY, null) ?: "[]"))
        private set

    // Looked up once per card per redraw, so keep it a map rather than scanning the list.
    private val ratings by derivedStateOf { items.associate { it.word.key to it.rating } }

    fun rating(word: Word): Int = ratings[word.key] ?: 0

    /** A rating from the user: saved, then sent to the watch. */
    fun rate(word: Word, stars: Int) {
        val next = if (stars == rating(word)) 0 else stars
        set(word, next)
        WatchSync.pushRating(appContext, word, next)
    }

    /** Replaces stored copies of words with the feed's current text, keeping their ratings. */
    fun refresh(words: List<Word>) {
        val fresh = words.associateBy { it.key }
        val updated = items.map { saved -> fresh[saved.word.key]?.let { saved.copy(word = it) } ?: saved }
        if (updated == items) return
        save(updated)
    }

    /** Sets a rating without telling the watch (used for ratings that came from the watch). */
    fun set(word: Word, rating: Int) {
        save(
            listOfNotNull(if (rating > 0) SavedWord(word, rating) else null) +
                items.filterNot { it.word.key == word.key },
        )
    }

    fun backupJson(): String {
        val archived = WordArchive.get(appContext).all()
        val archivedKeys = archived.mapTo(HashSet()) { it.key }
        val allWords = archived.map { SavedWord(it, rating(it)) } + items.filterNot { it.word.key in archivedKeys }
        return JSONObject()
            .put("version", 1)
            .put("words", toJson(items))
            .put("archive", toJson(allWords))
            .toString(2)
    }

    /** Merges a backup into the rated list and the Today archive; returns how many words were restored. */
    fun merge(json: String): Int {
        val root = JSONObject(json)
        val wordsArray = root.getJSONArray("words")
        val importedRated = parseArray(wordsArray, ratedOnly = true)
        val archiveArray = root.optJSONArray("archive")
        val importedArchive = (
            if (archiveArray != null) parseArray(archiveArray, ratedOnly = false) else emptyList()
            ) + parseArray(wordsArray, ratedOnly = false)
        val allToArchive = importedArchive.map { it.word }.distinctBy { it.key }
        if (allToArchive.isNotEmpty()) {
            WordArchive.get(appContext).store(allToArchive)
            WordWidget.refresh(appContext)
        }
        val keys = importedRated.map { it.word.key }.toSet()
        save(importedRated + items.filterNot { it.word.key in keys })
        return allToArchive.size.coerceAtLeast(importedRated.size)
    }

    /** Writes [backupJson] to [BACKUP_FILENAME] inside [treeUri], overwriting it if it is already there. */
    suspend fun writeBackupToFolder(treeUri: Uri) {
        val json = backupJson()
        withContext(backupDispatcher) { writeBackup(appContext, treeUri, json) }
    }

    private fun save(updated: List<SavedWord>) {
        items = updated
        prefs.edit().putString(KEY, toJson(updated).toString()).apply()
        val folder = AppSettings.get(appContext).backupFolderUri ?: return
        val json = backupJson()
        backupScope.launch { runCatching { writeBackup(appContext, Uri.parse(folder), json) } }
    }

    companion object {
        private const val KEY = "items"

        @Volatile
        private var instance: SavedWords? = null

        /** One shared list, so the UI and the watch listener service see the same state. */
        fun get(context: Context): SavedWords = instance ?: synchronized(this) {
            instance ?: SavedWords(context.applicationContext).also { instance = it }
        }

        private fun writeBackup(context: Context, treeUri: Uri, json: String) {
            val resolver = context.contentResolver
            val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
            val existingUri = resolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null,
            )?.use { c ->
                var found: Uri? = null
                while (c.moveToNext()) {
                    if (c.getString(1) == BACKUP_FILENAME) {
                        found = DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0))
                        break
                    }
                }
                found
            }
            val targetUri = existingUri ?: DocumentsContract.createDocument(
                resolver,
                DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId),
                "application/json",
                BACKUP_FILENAME,
            ) ?: error("Could not create $BACKUP_FILENAME")
            (resolver.openOutputStream(targetUri, "wt") ?: resolver.openOutputStream(targetUri))!!.use {
                it.write(json.toByteArray())
            }
        }

        private fun parse(json: String): List<SavedWord> = parseArray(JSONArray(json), ratedOnly = true)

        private fun parseArray(array: JSONArray, ratedOnly: Boolean): List<SavedWord> =
            (0 until array.length()).map { array.getJSONObject(it) }
                .filter { (!ratedOnly || it.optInt("rating") in 1..5) && it.has("word") && it.has("date") }
                .map {
                    SavedWord(
                        Word(
                            word = it.getString("word"),
                            date = it.getString("date"),
                            shortDefinition = it.optString("shortDefinition"),
                            pronunciation = it.optString("pronunciation"),
                            meaning = it.optString("meaning"),
                            context = it.optString("context"),
                            example = it.optString("example"),
                            didYouKnow = it.optString("didYouKnow"),
                        ),
                        it.optInt("rating").coerceIn(0, 5),
                    )
                }

        private fun toJson(items: List<SavedWord>): JSONArray = JSONArray().apply {
            items.forEach { (w, rating) ->
                put(
                    JSONObject()
                        .put("word", w.word).put("date", w.date)
                        .put("shortDefinition", w.shortDefinition).put("pronunciation", w.pronunciation)
                        .put("meaning", w.meaning).put("context", w.context)
                        .put("example", w.example).put("didYouKnow", w.didYouKnow)
                        .put("rating", rating),
                )
            }
        }
    }
}
