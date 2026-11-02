package com.example.wordoftheday

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Every word the feed has ever shown us, so words survive after the feed drops them.
 * Rows are inserted oldest first, so the rowid order is the order the words ran in.
 */
class WordArchive private constructor(context: Context) :
    SQLiteOpenHelper(context, "word_archive.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE words (
                key TEXT PRIMARY KEY, word TEXT NOT NULL, date TEXT NOT NULL,
                shortDefinition TEXT NOT NULL, pronunciation TEXT NOT NULL, meaning TEXT NOT NULL,
                context TEXT NOT NULL, example TEXT NOT NULL, didYouKnow TEXT NOT NULL)""",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /** Adds new words and refreshes the text of known ones; [words] is newest first, as the feed sends it. */
    fun store(words: List<Word>) {
        val db = writableDatabase
        db.transaction {
            for (w in words.asReversed()) {
                val values = ContentValues().apply {
                    put("key", w.key); put("word", w.word); put("date", w.date)
                    put("shortDefinition", w.shortDefinition); put("pronunciation", w.pronunciation)
                    put("meaning", w.meaning); put("context", w.context)
                    put("example", w.example); put("didYouKnow", w.didYouKnow)
                }
                if (insertWithOnConflict("words", null, values, SQLiteDatabase.CONFLICT_IGNORE) == -1L) {
                    update("words", values, "key = ?", arrayOf(w.key))
                }
            }
        }
    }

    /** All archived words, newest first. */
    fun all(): List<Word> = readableDatabase.query(
        "words", null, null, null, null, null, "rowid DESC",
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(
                    Word(
                        word = c.getString(c.getColumnIndexOrThrow("word")),
                        date = c.getString(c.getColumnIndexOrThrow("date")),
                        shortDefinition = c.getString(c.getColumnIndexOrThrow("shortDefinition")),
                        pronunciation = c.getString(c.getColumnIndexOrThrow("pronunciation")),
                        meaning = c.getString(c.getColumnIndexOrThrow("meaning")),
                        context = c.getString(c.getColumnIndexOrThrow("context")),
                        example = c.getString(c.getColumnIndexOrThrow("example")),
                        didYouKnow = c.getString(c.getColumnIndexOrThrow("didYouKnow")),
                    ),
                )
            }
        }
    }

    private inline fun SQLiteDatabase.transaction(block: SQLiteDatabase.() -> Unit) {
        beginTransaction()
        try {
            block()
            setTransactionSuccessful()
        } finally {
            endTransaction()
        }
    }

    companion object {
        @Volatile
        private var instance: WordArchive? = null

        fun get(context: Context): WordArchive = instance ?: synchronized(this) {
            instance ?: WordArchive(context.applicationContext).also { instance = it }
        }
    }
}

/** Fetches the feed and archives it. Returns the feed's words, newest first. */
suspend fun fetchAndArchive(context: Context): List<Word> {
    val fresh = fetchWords()
    withContext(Dispatchers.IO) {
        WordArchive.get(context).store(fresh)
        WordWidget.refresh(context)
    }
    return fresh
}

/** Every archived word, newest first. */
suspend fun archivedWords(context: Context): List<Word> =
    withContext(Dispatchers.IO) { WordArchive.get(context).all() }
