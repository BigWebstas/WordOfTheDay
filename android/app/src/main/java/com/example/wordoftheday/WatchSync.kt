package com.example.wordoftheday

import android.content.Context
import io.rebble.pebblekit2.client.BasePebbleListenerService
import io.rebble.pebblekit2.client.DefaultPebbleSender
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import io.rebble.pebblekit2.common.model.ReceiveResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

// Must match pebble/package.json (uuid and messageKeys).
private val WATCHAPP_UUID = UUID.fromString("5b369bd0-0487-4020-a10e-7034d3da8891")
private const val KEY_WORD = 1u
private const val KEY_DATE = 2u
private const val KEY_MEANING = 4u
private const val KEY_RATING = 6u
private const val KEY_SYNC = 7u
private const val KEY_STREAK = 8u

private const val WATCH_MAX_SAVED = 24
private const val WATCH_MEANING_CHARS = 90

/**
 * Keeps rated words in step with the Pebble watchapp over PebbleKit 2.
 * Each rating is sent as one event; when the watchapp opens, both sides send their whole list
 * (the phone's version wins when the same word is rated differently).
 */
object WatchSync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun pushRating(context: Context, word: Word, rating: Int) {
        scope.launch { send(context, ratingEvent(word, rating)) }
    }

    /** Asks the watch for its list, then sends ours. */
    fun resync(context: Context) {
        scope.launch {
            send(context, mapOf(KEY_STREAK to PebbleDictionaryItem.UInt8(Streak.current(context).coerceAtMost(255).toUByte())))
            send(context, mapOf(KEY_SYNC to PebbleDictionaryItem.UInt8(1u)))
            SavedWords.get(context).items
                .sortedByDescending { it.rating }
                .take(WATCH_MAX_SAVED)
                .forEach { send(context, ratingEvent(it.word, it.rating)) }
        }
    }

    private suspend fun send(context: Context, data: PebbleDictionary) {
        runCatching { DefaultPebbleSender(context.applicationContext).use { it.sendDataToPebble(WATCHAPP_UUID, data) } }
    }

    private fun ratingEvent(word: Word, rating: Int): PebbleDictionary = mapOf(
        KEY_WORD to PebbleDictionaryItem.Text(word.word.toWatchText()),
        KEY_DATE to PebbleDictionaryItem.Text(word.date),
        KEY_MEANING to PebbleDictionaryItem.Text(word.shortDefinition.toWatchText().take(WATCH_MEANING_CHARS)),
        KEY_RATING to PebbleDictionaryItem.UInt8(rating.toUByte()),
    )

    /** The watch fonts cannot draw curly quotes or dashes. */
    private fun String.toWatchText() = this
        .replace('‘', '\'').replace('’', '\'')
        .replace('“', '"').replace('”', '"')
        .replace('–', '-').replace('—', '-')
}

/** Receives ratings made on the watch and resyncs whenever the watchapp opens. */
class WatchListenerService : BasePebbleListenerService() {
    override suspend fun onMessageReceived(
        watchappUUID: UUID,
        data: PebbleDictionary,
        watch: WatchIdentifier,
    ): ReceiveResult {
        if (watchappUUID != WATCHAPP_UUID) return ReceiveResult.Ack
        val word = (data[KEY_WORD] as? PebbleDictionaryItem.Text)?.value
        val date = (data[KEY_DATE] as? PebbleDictionaryItem.Text)?.value
        val meaning = (data[KEY_MEANING] as? PebbleDictionaryItem.Text)?.value
        // The watch's uint8 can arrive as any integer item type, so read it by its text.
        val rating = data[KEY_RATING]?.value?.toString()?.toIntOrNull()
        if (word == null || date == null || meaning == null || rating == null) return ReceiveResult.Ack

        val saved = SavedWords.get(this)
        val known = saved.items.firstOrNull { it.word.word == word && it.word.date == date }?.word
        when {
            known != null -> saved.set(known, rating)
            rating > 0 -> saved.set(lookUp(word, date, meaning), rating)
        }
        return ReceiveResult.Ack
    }

    override fun onAppOpened(watchappUUID: UUID, watch: WatchIdentifier) {
        if (watchappUUID == WATCHAPP_UUID) WatchSync.resync(applicationContext)
    }

    /** The watch only knows a word's headline; take the full text from the feed when it is still there. */
    private suspend fun lookUp(word: String, date: String, meaning: String): Word =
        archivedWords(this).firstOrNull { it.word == word && it.date == date }
            ?: Word(word, date, meaning, "", meaning, "", "", "")
}
