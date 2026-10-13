package com.example.wordoftheday

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

data class SavedWord(val word: Word, val rating: Int)

/**
 * Rated words, newest first. A word is saved by rating it and removed by tapping its rating again.
 * Backups use the same flat JSON as the iOS app: {"version":1,"words":[{...word fields, "rating":4}]}.
 */
class SavedWords private constructor(private val appContext: Context) {
    private val prefs = appContext.getSharedPreferences("saved_words", Context.MODE_PRIVATE)

    var items by mutableStateOf(parse(prefs.getString(KEY, null) ?: "[]"))
        private set

    fun rating(word: Word): Int = items.firstOrNull { it.word.key == word.key }?.rating ?: 0

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
        items = updated
        prefs.edit().putString(KEY, toJson(items).toString()).apply()
    }

    /** Sets a rating without telling the watch (used for ratings that came from the watch). */
    fun set(word: Word, rating: Int) {
        items = listOfNotNull(if (rating > 0) SavedWord(word, rating) else null) +
            items.filterNot { it.word.key == word.key }
        prefs.edit().putString(KEY, toJson(items).toString()).apply()
    }

    fun backupJson(): String =
        JSONObject().put("version", 1).put("words", toJson(items)).toString(2)

    /** Merges a backup into the list (backup wins on conflicts); returns how many words it held. */
    fun merge(json: String): Int {
        val imported = parse(JSONObject(json).getJSONArray("words").toString())
        val keys = imported.map { it.word.key }.toSet()
        items = imported + items.filterNot { it.word.key in keys }
        prefs.edit().putString(KEY, toJson(items).toString()).apply()
        return imported.size
    }

    companion object {
        private const val KEY = "items"

        @Volatile
        private var instance: SavedWords? = null

        /** One shared list, so the UI and the watch listener service see the same state. */
        fun get(context: Context): SavedWords = instance ?: synchronized(this) {
            instance ?: SavedWords(context.applicationContext).also { instance = it }
        }

        private fun parse(json: String): List<SavedWord> {
            val array = JSONArray(json)
            return (0 until array.length()).map { array.getJSONObject(it) }
                .filter { it.optInt("rating") in 1..5 && it.has("word") && it.has("date") }
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
                        it.getInt("rating"),
                    )
                }
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
