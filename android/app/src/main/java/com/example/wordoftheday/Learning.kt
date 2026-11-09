package com.example.wordoftheday

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.random.Random

data class Question(val answer: Word, val options: List<Word>)

/** Which words the user has learned, and how often each one was missed in the quiz. */
@Stable
class Learning private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("learning", Context.MODE_PRIVATE)

    var learned by mutableStateOf(prefs.getStringSet("learned", emptySet())!!.toSet())
        private set

    fun isLearned(word: Word) = word.key in learned

    fun toggleLearned(word: Word) {
        learned = if (isLearned(word)) learned - word.key else learned + word.key
        prefs.edit().putStringSet("learned", learned).apply()
    }

    private fun misses(word: Word) = prefs.getInt("miss_${word.key}", 0)

    /** A right answer eases a word's miss count down; a wrong one pushes it up. */
    fun record(word: Word, correct: Boolean) {
        val next = if (correct) maxOf(0, misses(word) - 1) else misses(word) + 1
        prefs.edit().putInt("miss_${word.key}", next).apply()
    }

    /** Odds of being asked: missed words come up more, learned words less. */
    private fun weight(word: Word) = (1.0 + 2 * misses(word)) * if (isLearned(word)) 0.3 else 1.0

    /**
     * A question about one of the [rated] words, with three wrong options from anything else known.
     * Null until there are four distinct words to choose from.
     */
    fun nextQuestion(rated: List<Word>, others: List<Word>, previous: Word?): Question? {
        val candidates = rated.filter { it.key != previous?.key }.ifEmpty { rated }
        if (candidates.isEmpty()) return null
        var roll = Random.nextDouble() * candidates.sumOf(::weight)
        val answer = candidates.firstOrNull { roll -= weight(it); roll <= 0 } ?: candidates.last()
        val wrong = (rated + others).distinctBy { it.key }.filter { it.key != answer.key }.shuffled().take(3)
        if (wrong.size < 3) return null
        return Question(answer, (wrong + answer).shuffled())
    }

    companion object {
        @Volatile
        private var instance: Learning? = null

        fun get(context: Context): Learning = instance ?: synchronized(this) {
            instance ?: Learning(context.applicationContext).also { instance = it }
        }
    }
}
