@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)

package com.example.wordoftheday

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.VectorConverter
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.delay

// Set by the app root so any card can join the card-to-detail transition.
internal val LocalSharedScope = compositionLocalOf<SharedTransitionScope?> { null }
internal val LocalVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }
internal val LocalSpeak = staticCompositionLocalOf<(String) -> Unit> { {} }

/** Where a list was scrolled to and which cards already slid in, kept while a detail screen is open. */
@Stable
internal class ListMemory(val state: LazyListState, val entered: MutableSet<String>)

@Composable
internal fun rememberListMemory(): ListMemory {
    val state = rememberLazyListState()
    return remember { ListMemory(state, mutableSetOf()) }
}

/** Marks a layout as the same element on the list and the detail screen. */
@Composable
private fun Modifier.shared(key: String): Modifier {
    val sharedScope = LocalSharedScope.current ?: return this
    val visibility = LocalVisibilityScope.current ?: return this
    val shape = CardDefaults.shape
    return with(sharedScope) {
        val clip = remember(shape) { OverlayClip(shape) }
        this@shared.sharedBounds(
            rememberSharedContentState(key),
            visibility,
            clipInOverlayDuringTransition = clip,
        )
    }
}

/** Returns a function that plays a haptic, unless the user turned haptics off. */
@Composable
internal fun rememberBuzz(): (HapticFeedbackType) -> Unit {
    val haptic = LocalHapticFeedback.current
    val settings = AppSettings.get(LocalContext.current)
    return remember(haptic, settings) { { type -> if (settings.haptics) haptic.performHapticFeedback(type) } }
}

/** Returns a function that reads text aloud; the engine lives as long as the caller. */
@Composable
internal fun rememberSpeaker(): (String) -> Unit {
    val context = LocalContext.current
    var engine by remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(Unit) {
        var created: TextToSpeech? = null
        created = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) created?.language = Locale.US
        }
        engine = created
        onDispose { created.shutdown() }
    }
    return { text -> engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "word") }
}

@Composable
private fun cardContainer(rating: Int, hero: Boolean): Color {
    val colors = MaterialTheme.colorScheme
    fun tint(r: Int) =
        lerp(if (hero) colors.primaryContainer else colors.surfaceContainerHighest, Color(0xFFFFB300), r * 0.04f)
    // Higher ratings warm the card towards gold; only allocate an animation when a card's rating changes.
    val state = remember { intArrayOf(rating, 0) }
    if (rating != state[0]) state[1] = 1
    if (state[1] == 0) return tint(rating)
    val anim = remember { Animatable(tint(state[0]), Color.VectorConverter(colors.primaryContainer.colorSpace)) }
    val target = tint(rating)
    LaunchedEffect(target) {
        state[0] = rating
        anim.animateTo(target, spring())
    }
    return anim.value
}

@Composable
internal fun WordList(
    words: List<Pair<Word, Int>>,
    saved: SavedWords,
    learning: Learning,
    hero: Boolean,
    topPadding: Dp,
    memory: ListMemory,
    onOpen: (Word) -> Unit,
    header: (@Composable () -> Unit)? = null,
    onDismiss: ((Word, Int) -> Unit)? = null,
) {
    val buzz = rememberBuzz()
    val speak = LocalSpeak.current
    LazyColumn(
        state = memory.state,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = topPadding, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (header != null) item(key = "header", contentType = "header") { header() }
        itemsIndexed(
            words,
            key = { _, pair -> pair.first.key },
            contentType = { index, _ -> hero && index == 0 },
        ) { index, (word, rating) ->
            val card = @Composable {
                WordCard(
                    word, rating,
                    learned = learning.isLearned(word),
                    hero = hero && index == 0,
                    onOpen = { onOpen(word) },
                    onRate = {
                        buzz(HapticFeedbackType.TextHandleMove)
                        saved.rate(word, it)
                    },
                    onSpeak = { speak(word.word) },
                )
            }
            // Only cascade the initial screenful on the Today tab; switching tabs or scrolling shows cards in place.
            val atTop = hero &&
                !memory.state.isScrollInProgress &&
                memory.state.firstVisibleItemIndex == 0 &&
                memory.state.firstVisibleItemScrollOffset == 0
            val play = memory.entered.add(word.key) && atTop && index < 6
            val itemModifier = (if (onDismiss != null) Modifier.animateItem() else Modifier).slideIn(index, play)
            Box(itemModifier) {
                if (onDismiss == null) card() else SwipeAway({ onDismiss(word, rating) }, card)
            }
        }
    }
}

@Composable
private fun WordCard(
    word: Word,
    rating: Int,
    learned: Boolean,
    hero: Boolean,
    onOpen: () -> Unit,
    onRate: (Int) -> Unit,
    onSpeak: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Card(
        Modifier.fillMaxWidth().shared("card-${word.key}").clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(
            containerColor = cardContainer(rating, hero),
            contentColor = if (hero) colors.onPrimaryContainer else colors.onSurface,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (learned) "${word.date} · ✓ learned" else word.date, style = MaterialTheme.typography.labelMedium)
                    Text(
                        word.word,
                        Modifier.shared("word-${word.key}"),
                        style = if (hero) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineMedium,
                    )
                }
                SpeakButton(word, onSpeak)
            }
            Stars(rating, onRate)
            Text(word.shortDefinition, Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun SpeakButton(word: Word, onSpeak: () -> Unit) {
    IconButton(onClick = onSpeak) {
        Text("🔊", Modifier.semantics { contentDescription = "Pronounce ${word.word}" }, fontSize = 22.sp)
    }
}

/** The card grown to full screen, with every section and the share options. */
@Composable
internal fun DetailScreen(word: Word, saved: SavedWords, learning: Learning, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val buzz = rememberBuzz()
    val speak = LocalSpeak.current
    val rating = saved.rating(word)
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Surface(
        Modifier.fillMaxSize().shared("card-${word.key}"),
        color = cardContainer(rating, hero = false),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = statusBarHeight() + 8.dp, bottom = bottomInset + 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Spacer(Modifier.weight(1f))
                SpeakButton(word) { speak(word.word) }
            }
            Text(word.date, style = MaterialTheme.typography.labelMedium)
            Text(word.word, Modifier.shared("word-${word.key}"), style = MaterialTheme.typography.displayMedium)
            Stars(rating) {
                buzz(HapticFeedbackType.TextHandleMove)
                saved.rate(word, it)
            }
            Text(word.pronunciation, Modifier.padding(top = 8.dp))
            Text(word.shortDefinition, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium)
            Section("Meaning", word.meaning)
            Section("In context", word.context)
            Section("Example", word.example)
            Section("Did you know?", word.didYouKnow)

            FilterChip(
                learning.isLearned(word),
                { learning.toggleLearned(word) },
                { Text("✓ Learned") },
                Modifier.padding(top = 16.dp),
            )
            Text("Share as", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { buzz(HapticFeedbackType.LongPress); share(context, word) },
                    Modifier.weight(1f),
                ) { Text("Text") }
                OutlinedButton(
                    onClick = { buzz(HapticFeedbackType.LongPress); shareMarkdown(context, word, rating) },
                    Modifier.weight(1f),
                ) { Text("Markdown") }
                OutlinedButton(
                    onClick = { buzz(HapticFeedbackType.LongPress); ShareCard.share(context, word, rating) },
                    Modifier.weight(1f),
                ) { Text("Image") }
            }
        }
    }
}

/** Swipe either way to dismiss; the red backdrop shows the bin underneath. */
@Composable
private fun SwipeAway(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val current by rememberUpdatedState(onDismiss)
    var width = remember { 0f }
    lateinit var state: androidx.compose.material3.SwipeToDismissBoxState
    state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            // Require a deliberate drag across at least 40% of the card so a diagonal scroll flick never deletes.
            val farEnough = width > 0f && abs(runCatching { state.requireOffset() }.getOrDefault(0f)) >= width * 0.4f
            if (value == SwipeToDismissBoxValue.Settled || !farEnough) false else { current(); true }
        },
        positionalThreshold = { distance ->
            width = distance
            distance * 0.5f
        },
    )
    SwipeToDismissBox(
        state,
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(CardDefaults.shape)
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 24.dp),
                contentAlignment = if (state.dismissDirection == SwipeToDismissBoxValue.StartToEnd) {
                    Alignment.CenterStart
                } else Alignment.CenterEnd,
            ) {
                Icon(Icons.Filled.Delete, "Remove", tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        },
    ) { content() }
}

@Composable
private fun Stars(rating: Int, onRate: (Int) -> Unit) {
    // Only spin up animations and confetti on cards whose rating actually changes while on screen.
    val state = remember { intArrayOf(rating, 0, 0) } // [previousRating, active, burst]
    if (rating != state[0]) {
        state[1] = 1
        if (rating == 5 && state[0] != 5) state[2]++
    }
    val active = state[1] != 0
    val previousRating = state[0]
    val burst = state[2]
    if (active) SideEffect { state[0] = rating }

    Box {
        if (burst > 0) Confetti(burst, Modifier.matchParentSize())
        Row {
            for (star in 1..5) {
                val filled = star <= rating
                val targetColor = if (filled) Color(0xFFFFB300) else MaterialTheme.colorScheme.onSurfaceVariant
                if (!active) {
                    Text(
                        if (filled) "★" else "☆",
                        Modifier
                            .semantics { contentDescription = "Rate $star stars" }
                            .clickable { onRate(star) }
                            .padding(end = 8.dp, top = 4.dp, bottom = 4.dp),
                        color = targetColor,
                        fontSize = 28.sp,
                    )
                } else {
                    val color by animateColorAsState(targetColor, label = "star")
                    // Newly filled stars pop, staggered left to right; stars already filled stay still.
                    val pop = remember { Animatable(1f) }
                    val wasFilled = remember { booleanArrayOf(star <= previousRating) }
                    LaunchedEffect(filled) {
                        if (filled && !wasFilled[0]) {
                            delay(star * 30L)
                            pop.snapTo(0.4f)
                            pop.animateTo(1f, spring(Spring.DampingRatioHighBouncy, Spring.StiffnessMedium))
                        }
                        wasFilled[0] = filled
                    }
                    Text(
                        if (filled) "★" else "☆",
                        Modifier
                            .semantics { contentDescription = "Rate $star stars" }
                            .clickable { onRate(star) }
                            .padding(end = 8.dp, top = 4.dp, bottom = 4.dp)
                            .graphicsLayer { scaleX = pop.value; scaleY = pop.value },
                        color = color,
                        fontSize = 28.sp,
                    )
                }
            }
        }
    }
}

private class Particle(val angle: Float, val speed: Float, val radius: Float, val color: Color)

private val confettiColors = listOf(Color(0xFFFFB300), Color(0xFFE53935), Color(0xFF43A047), Color(0xFF1E88E5), Color(0xFF8E24AA))

/** Draws a short burst of falling confetti from the centre of its box each time [trigger] changes. */
@Composable
private fun Confetti(trigger: Int, modifier: Modifier) {
    val progress = remember { Animatable(1f) }
    val particles = remember {
        List(32) {
            Particle(
                angle = Random.nextFloat() * 2 * Math.PI.toFloat(),
                speed = 80f + Random.nextFloat() * 200f,
                radius = 2f + Random.nextFloat() * 3f,
                color = confettiColors.random(),
            )
        }
    }
    LaunchedEffect(trigger) {
        if (trigger > 0) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(1100, easing = LinearOutSlowInEasing))
        }
    }
    Box(
        modifier.drawBehind {
            val t = progress.value
            if (t >= 1f) return@drawBehind
            for (p in particles) {
                val reach = p.speed * t * density
                val x = center.x + cos(p.angle) * reach
                val y = center.y + sin(p.angle) * reach + 260f * t * t * density
                drawCircle(p.color.copy(alpha = 1f - t), p.radius * density, Offset(x, y))
            }
        },
    )
}

/** Fades and slides a card up from below, staggered by position; [play] false skips it. */
@Composable
private fun Modifier.slideIn(index: Int, play: Boolean): Modifier {
    val shouldPlay = remember { play }
    if (!shouldPlay) return this
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index.coerceAtMost(5) * 60L)
        progress.animateTo(1f, tween(450, easing = FastOutSlowInEasing))
    }
    return if (progress.value >= 1f) this else graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 48.dp.toPx()
    }
}

@Composable
private fun Section(label: String, text: String) {
    if (text.isEmpty()) return
    Text(label, Modifier.padding(top = 16.dp), style = MaterialTheme.typography.labelLarge)
    Text(text)
}

private fun share(context: Context, word: Word) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, word.shareText)
    context.startActivity(Intent.createChooser(send, null))
}

private fun shareMarkdown(context: Context, word: Word, rating: Int) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, word.markdown(rating))
    context.startActivity(Intent.createChooser(send, null))
}
