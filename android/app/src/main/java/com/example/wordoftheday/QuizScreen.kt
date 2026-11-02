package com.example.wordoftheday

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp

/** Pick the word that matches a definition; words you miss come back more often. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuizScreen(saved: SavedWords, learning: Learning, feedWords: List<Word>) {
    val buzz = rememberBuzz()
    val rated = remember(saved.items) { saved.items.map { it.word } }
    var asked by rememberSaveable { mutableIntStateOf(0) }
    var correct by rememberSaveable { mutableIntStateOf(0) }
    // Rebuilt when the pool grows, e.g. once the feed finishes loading.
    var question by remember(rated.size, feedWords.size) {
        mutableStateOf(learning.nextQuestion(rated, feedWords, null))
    }
    var picked by remember(question) { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = statusBarHeight() + 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Quiz", style = MaterialTheme.typography.headlineMedium)
        val current = question
        if (current == null) {
            Text("Rate a word to start quizzing yourself. The quiz needs four different words to choose from.")
            return@Column
        }
        Text("Score: $correct of $asked", style = MaterialTheme.typography.labelLarge)
        AnimatedContent(
            current,
            transitionSpec = {
                (slideInHorizontally { it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 3 } + fadeOut())
            },
            label = "question",
        ) { q ->
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Which word means…", style = MaterialTheme.typography.labelMedium)
                        Text(q.answer.shortDefinition, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.titleLarge)
                    }
                }
                q.options.forEach { option ->
                    val chosen = picked
                    val isAnswer = option.key == q.answer.key
                    val container by animateColorAsState(
                        when {
                            chosen == null -> MaterialTheme.colorScheme.surfaceContainerHighest
                            isAnswer -> Color(0xFF2E7D32)
                            option.key == chosen -> Color(0xFFC62828)
                            else -> MaterialTheme.colorScheme.surfaceContainerLow
                        },
                        label = "option",
                    )
                    val content = if (chosen != null && (isAnswer || option.key == chosen)) Color.White else MaterialTheme.colorScheme.onSurface
                    Card(
                        onClick = {
                            picked = option.key
                            asked++
                            if (isAnswer) correct++
                            learning.record(q.answer, isAnswer)
                            buzz(if (isAnswer) HapticFeedbackType.TextHandleMove else HapticFeedbackType.LongPress)
                        },
                        Modifier.fillMaxWidth(),
                        enabled = chosen == null,
                        colors = CardDefaults.cardColors(
                            containerColor = container, contentColor = content,
                            disabledContainerColor = container, disabledContentColor = content,
                        ),
                    ) {
                        Text(option.word, Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
        if (picked != null) {
            Text(current.answer.pronunciation)
            FilterChip(
                learning.isLearned(current.answer),
                { learning.toggleLearned(current.answer) },
                { Text("✓ Learned") },
            )
            Button(
                onClick = { question = learning.nextQuestion(rated, feedWords, current.answer) },
                Modifier.fillMaxWidth(),
            ) { Text("Next question") }
        }
    }
}
