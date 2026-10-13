package com.example.wordoftheday

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WordNotifier.schedule(this)
        if (Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            val dark = isSystemInDarkTheme()
            val colors = when {
                Build.VERSION.SDK_INT >= 31 ->
                    if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = colors) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding()) { App() }
                }
            }
        }
    }
}

private sealed interface Load {
    data object Loading : Load
    data class Done(val words: List<Word>) : Load
    data class Failed(val message: String) : Load
}

@Composable
private fun App() {
    val context = LocalContext.current
    val saved = remember { SavedWords.get(context) }
    var tab by rememberSaveable { mutableIntStateOf(0) }

    // Loaded here so switching tabs does not refetch the feed.
    var load by remember { mutableStateOf<Load>(Load.Loading) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        load = Load.Loading
        val fetched = runCatching { fetchAndArchive(context) }
        // Offline or feed down: the archive still has every word seen so far.
        val words = archivedWords(context)
        load = if (fetched.isSuccess || words.isNotEmpty()) {
            fetched.getOrNull()?.let { fresh ->
                fresh.firstOrNull()?.let { WordNotifier.markSeen(context, it.word) }
                saved.refresh(fresh)
            }
            Load.Done(words)
        } else {
            Load.Failed(fetched.exceptionOrNull()?.message ?: "Could not load the feed")
        }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            if (tab == 0) TodayScreen(load, saved) { attempt++ } else SavedScreen(saved)
        }
        NavigationBar {
            NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Text("☀") }, label = { Text("Today") })
            NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Text("★") }, label = { Text("Saved") })
        }
    }
}

@Composable
private fun TodayScreen(load: Load, saved: SavedWords, onRetry: () -> Unit) {
    when (load) {
        Load.Loading -> Centered { CircularProgressIndicator() }
        is Load.Failed -> Centered {
            Text(load.message)
            Button(onClick = onRetry, Modifier.padding(top = 12.dp)) { Text("Retry") }
        }
        is Load.Done -> WordList(load.words.map { it to saved.rating(it) }, saved, expandFirst = true)
    }
}

@Composable
private fun SavedScreen(saved: SavedWords) {
    val context = LocalContext.current
    val export = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) toast(
            context,
            runCatching {
                context.contentResolver.openOutputStream(uri)!!.use { it.write(saved.backupJson().toByteArray()) }
            }.fold({ "Backup saved" }, { "Backup failed: ${it.message}" }),
        )
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) toast(
            context,
            runCatching {
                saved.merge(context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() })
            }.fold({ "Imported $it words" }, { "Import failed: ${it.message}" }),
        )
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { export.launch("word-of-the-day-backup.json") }) { Text("Back up") }
            OutlinedButton(onClick = { import.launch(arrayOf("*/*")) }) { Text("Restore") }
        }
        if (saved.items.isEmpty()) {
            Centered { Text("Rate a word to save it here.") }
        } else {
            WordList(saved.items.sortedByDescending { it.rating }.map { it.word to it.rating }, saved, expandFirst = false)
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

@Composable
private fun WordList(words: List<Pair<Word, Int>>, saved: SavedWords, expandFirst: Boolean) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(if (expandFirst) words.firstOrNull()?.first?.key else null) }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(words, key = { it.first.key }) { (word, rating) ->
            Card(Modifier.fillMaxWidth().clickable { expanded = if (expanded == word.key) null else word.key }) {
                Column(Modifier.padding(16.dp)) {
                    Text(word.date, style = MaterialTheme.typography.labelMedium)
                    Text(word.word, style = MaterialTheme.typography.headlineMedium)
                    Stars(rating) { saved.rate(word, it) }
                    Text(word.shortDefinition, Modifier.padding(top = 4.dp))
                    if (expanded == word.key) {
                        Text(word.pronunciation, Modifier.padding(top = 12.dp))
                        Section("Meaning", word.meaning)
                        Section("In context", word.context)
                        Section("Example", word.example)
                        Section("Did you know?", word.didYouKnow)
                        Row(Modifier.padding(top = 8.dp)) {
                            TextButton(onClick = { share(context, word) }) { Text("Share") }
                            TextButton(onClick = { shareMarkdown(context, word, rating) }) { Text("Share as Markdown") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Stars(rating: Int, onRate: (Int) -> Unit) {
    Row {
        for (star in 1..5) {
            Text(
                if (star <= rating) "★" else "☆",
                Modifier
                    .semantics { contentDescription = "Rate $star stars" }
                    .clickable { onRate(star) }
                    .padding(end = 8.dp, top = 4.dp, bottom = 4.dp),
                fontSize = 28.sp,
            )
        }
    }
}

@Composable
private fun Section(label: String, text: String) {
    if (text.isEmpty()) return
    Text(label, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelLarge)
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

private fun toast(context: Context, message: String) =
    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
