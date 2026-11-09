@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)

package com.example.wordoftheday

import android.Manifest
import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WordNotifier.schedule(this)
        if (Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestHighestRefreshRate()
        setContent { AppTheme { Surface(Modifier.fillMaxSize()) { App() } } }
    }
}

/** Material colours that follow the chosen theme and, on Android 12+, the wallpaper. */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = when (AppSettings.get(context).themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    // The system bar icons follow the phone's theme unless told otherwise.
    val view = LocalView.current
    SideEffect {
        val window = (context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    val colors = when {
        Build.VERSION.SDK_INT >= 31 ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

// Many phones run apps at 60 Hz unless asked; pick the fastest mode at the current resolution.
private fun ComponentActivity.requestHighestRefreshRate() {
    val currentDisplay = if (Build.VERSION.SDK_INT >= 30) {
        display
    } else {
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay
    } ?: return
    val current = currentDisplay.mode ?: return
    val fastest = currentDisplay.supportedModes
        .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
        .maxByOrNull { it.refreshRate } ?: return
    // Copy first: window.attributes returns the internal LayoutParams, so mutating it in place hides the change.
    window.attributes = WindowManager.LayoutParams().apply {
        copyFrom(window.attributes)
        preferredDisplayModeId = fastest.modeId
        preferredRefreshRate = fastest.refreshRate
    }
    if (Build.VERSION.SDK_INT >= 34) {
        window.decorView.requestedFrameRate = View.REQUESTED_FRAME_RATE_CATEGORY_HIGH
    }
    if (Build.VERSION.SDK_INT >= 35) {
        window.setFrameRatePowerSavingsBalanced(false)
    }
}

private sealed interface Load {
    data object Loading : Load
    data class Done(val words: List<Word>, val refreshing: Boolean) : Load
    data class Failed(val message: String) : Load
}

@Composable
private fun App() {
    val context = LocalContext.current
    val saved = remember { SavedWords.get(context) }
    val learning = remember { Learning.get(context) }
    val streak = remember { Streak.touch(context) }
    val snackbar = remember { SnackbarHostState() }
    val buzz = rememberBuzz()
    val speak = rememberSpeaker()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var detail by remember { mutableStateOf<Word?>(null) }
    val todayList = rememberListMemory()
    val ratingList = rememberListMemory()

    // Loaded here so switching tabs does not refetch the feed.
    var load by remember { mutableStateOf<Load>(Load.Loading) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        // Ensure any rated words restored from a backup are also present in the local archive.
        if (saved.items.isNotEmpty()) {
            val ratedWords = saved.items.map { it.word }
            withContext(Dispatchers.IO) { WordArchive.get(context).store(ratedWords) }
        }
        // Show what the archive already holds straight away; the fetch only tops it up.
        val shown = archivedWords(context).takeIf { it.isNotEmpty() } ?: (load as? Load.Done)?.words
        load = if (shown != null) Load.Done(shown, refreshing = attempt > 0) else Load.Loading
        val fetched = runCatching { fetchAndArchive(context) }
        // Offline or feed down: the archive still has every word seen so far.
        val words = archivedWords(context)
        load = if (fetched.isSuccess || words.isNotEmpty()) {
            fetched.getOrNull()?.let { fresh ->
                fresh.firstOrNull()?.let { WordNotifier.markSeen(context, it.word) }
                saved.refresh(fresh)
            }
            Load.Done(words, refreshing = false)
        } else {
            Load.Failed(fetched.exceptionOrNull()?.message ?: "Could not load the feed")
        }
    }

    fun select(index: Int) {
        if (tab != index) buzz(HapticFeedbackType.TextHandleMove)
        tab = index
    }

    SharedTransitionLayout {
        val sharedScope = this
        CompositionLocalProvider(LocalSharedScope provides sharedScope, LocalSpeak provides speak) {
            AnimatedContent(detail, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "detail") { open ->
                CompositionLocalProvider(LocalVisibilityScope provides this) {
                    if (open != null) {
                        DetailScreen(open, saved, learning) { detail = null }
                    } else Scaffold(
                        contentWindowInsets = WindowInsets(0, 0, 0, 0),
                        snackbarHost = { SnackbarHost(snackbar) },
                        bottomBar = {
                            NavigationBar {
                                NavigationBarItem(
                                    selected = tab == 0, onClick = { select(0) },
                                    icon = { NavIcon(Icons.Filled.Home, tab == 0, spin = false) }, label = { Text("Today") },
                                )
                                NavigationBarItem(
                                    selected = tab == 1, onClick = { select(1) },
                                    icon = { NavIcon(Icons.Filled.Star, tab == 1, spin = true) }, label = { Text("Rating") },
                                )
                                NavigationBarItem(
                                    selected = tab == 2, onClick = { select(2) },
                                    icon = { NavIcon(Icons.Filled.CheckCircle, tab == 2, spin = false) }, label = { Text("Quiz") },
                                )
                                NavigationBarItem(
                                    selected = tab == 3, onClick = { select(3) },
                                    icon = { NavIcon(Icons.Filled.Settings, tab == 3, spin = true) }, label = { Text("Settings") },
                                )
                            }
                        },
                    ) { padding ->
                        Box(Modifier.padding(padding)) {
                            AnimatedContent(
                                tab,
                                transitionSpec = {
                                    val dir = if (targetState > initialState) 1 else -1
                                    (slideInHorizontally { it / 4 * dir } + fadeIn()) togetherWith
                                        (slideOutHorizontally { -it / 4 * dir } + fadeOut())
                                },
                                label = "tab",
                            ) { current ->
                                // Disable shared-element bounds while sliding between tabs so words on both
                                // Today and Rating do not collide in SharedTransitionLayout's overlay.
                                val activeSharedScope = if (current == tab && !transition.isRunning) sharedScope else null
                                CompositionLocalProvider(LocalSharedScope provides activeSharedScope) {
                                    val open = { word: Word -> detail = word }
                                    when (current) {
                                        0 -> TodayScreen(load, saved, learning, streak, todayList, open) { attempt++ }
                                        1 -> RatingScreen(saved, learning, snackbar, ratingList, open)
                                        2 -> QuizScreen(saved, learning, (load as? Load.Done)?.words.orEmpty())
                                        else -> SettingsScreen(saved, learning, snackbar) { attempt++ }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Grows with a bounce when selected; some icons also turn. */
@Composable
private fun NavIcon(icon: ImageVector, selected: Boolean, spin: Boolean) {
    val t by animateFloatAsState(
        if (selected) 1f else 0f,
        spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow),
        label = "navIcon",
    )
    Icon(
        icon,
        contentDescription = null,
        Modifier.graphicsLayer {
            scaleX = 1f + 0.25f * t
            scaleY = 1f + 0.25f * t
            if (spin) rotationZ = 72f * t
        },
    )
}

@Composable
internal fun statusBarHeight(): Dp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

@Composable
private fun TodayScreen(
    load: Load,
    saved: SavedWords,
    learning: Learning,
    streak: Int,
    memory: ListMemory,
    onOpen: (Word) -> Unit,
    onRefresh: () -> Unit,
) {
    // Keyed by kind, so a refresh updates the list in place instead of fading it out and back in.
    AnimatedContent(
        load,
        contentKey = { it::class },
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "load",
    ) { state ->
        when (state) {
            Load.Loading -> SkeletonList()
            is Load.Failed -> Centered {
                Text(state.message)
                Button(onClick = onRefresh, Modifier.padding(top = 12.dp)) { Text("Retry") }
            }
            is Load.Done -> PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh) {
                val items = remember(state.words, saved.items) { state.words.map { it to saved.rating(it) } }
                WordList(
                    items,
                    saved,
                    learning,
                    hero = true,
                    topPadding = statusBarHeight() + 16.dp,
                    memory = memory,
                    onOpen = onOpen,
                    header = if (streak >= 2) {
                        { AssistChip(onClick = {}, label = { Text("🔥 $streak day streak") }) }
                    } else null,
                )
            }
        }
    }
}

/** Pulsing placeholder cards shown while the very first load has nothing to display. */
@Composable
private fun SkeletonList() {
    val pulse by rememberInfiniteTransition(label = "skeleton").animateFloat(
        0.25f, 0.6f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse",
    )
    Column(
        Modifier.padding(start = 16.dp, end = 16.dp, top = statusBarHeight() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(4) { index ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(if (index == 0) 170.dp else 110.dp)
                    .graphicsLayer { alpha = pulse }
                    .clip(CardDefaults.shape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
    }
}

@Composable
private fun RatingScreen(
    saved: SavedWords,
    learning: Learning,
    snackbar: SnackbarHostState,
    memory: ListMemory,
    onOpen: (Word) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var topRated by rememberSaveable { mutableStateOf(true) }
    val shown = remember(saved.items, query, topRated) {
        saved.items
            .filter { query.isBlank() || it.word.word.contains(query, true) || it.word.shortDefinition.contains(query, true) }
            .let { list -> if (topRated) list.sortedByDescending { it.rating } else list }
    }
    val currentShown by rememberUpdatedState(shown)

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = statusBarHeight() + 12.dp)) {
            OutlinedTextField(
                query,
                { query = it },
                Modifier.fillMaxWidth(),
                placeholder = { Text("Search rated words") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = if (query.isNotEmpty()) {
                    { IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "Clear search") } }
                } else null,
                singleLine = true,
            )
            androidx.compose.foundation.layout.Row(
                Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(topRated, { topRated = true }, { Text("Top rated") })
                FilterChip(!topRated, { topRated = false }, { Text("Newest") })
            }
        }
        when {
            saved.items.isEmpty() -> Centered { Text("Rate a word and it shows up here.") }
            shown.isEmpty() -> Centered { Text("No words match.") }
            else -> WordList(
                shown.map { it.word to it.rating },
                saved,
                learning,
                hero = false,
                topPadding = 12.dp,
                memory = memory,
                onOpen = onOpen,
                onDismiss = { word, rating ->
                    // Rating a word with its own rating removes it; rating it again puts it back.
                    saved.rate(word, rating)
                    scope.launch {
                        snackbar.currentSnackbarData?.dismiss()
                        val result = snackbar.showSnackbar("Removed ${word.word}", actionLabel = "Undo")
                        if (result == SnackbarResult.ActionPerformed) {
                            saved.rate(word, rating)
                            // The list keeps the top card in place, so a word put back above it would be out of sight.
                            withFrameNanos { }
                            val index = currentShown.indexOfFirst { it.word.key == word.key }
                            if (index in 0 until memory.state.firstVisibleItemIndex) memory.state.animateScrollToItem(index)
                        }
                    }
                },
            )
        }
    }
}

@Composable
internal fun Centered(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}
