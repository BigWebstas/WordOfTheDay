package com.example.wordoftheday

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    saved: SavedWords,
    learning: Learning,
    snackbar: SnackbarHostState,
    onRestored: () -> Unit = {},
) {
    val context = LocalContext.current
    val settings = AppSettings.get(context)
    val scope = rememberCoroutineScope()
    fun say(message: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(message)
        }
    }

    val export = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) say(
            runCatching {
                context.contentResolver.openOutputStream(uri)!!.use { it.write(saved.backupJson().toByteArray()) }
            }.fold({ "Backup saved" }, { "Backup failed: ${it.message}" }),
        )
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) say(
            runCatching {
                val count = saved.merge(context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() })
                onRestored()
                count
            }.fold({ "Imported $it words" }, { "Import failed: ${it.message}" }),
        )
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
            settings.updateBackupFolder(uri.toString())
            scope.launch {
                say(
                    runCatching { saved.writeBackupToFolder(uri) }
                        .fold({ "Auto-backup enabled" }, { "Auto-backup failed: ${it.message}" }),
                )
            }
        }
    }

    var picking by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }
    val reminder = settings.reminderMinutes
    val folderUri = settings.backupFolderUri
    val folderName = remember(folderUri) { folderUri?.let { folderLabel(context, Uri.parse(it)) } }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = statusBarHeight() + 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        Text("Appearance", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach {
                FilterChip(settings.themeMode == it, { settings.updateThemeMode(it) }, { Text(it.name) })
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Haptic feedback", Modifier.weight(1f))
            Switch(settings.haptics, settings::updateHaptics)
        }

        Text("Daily reminder", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge)
        Text(
            if (reminder < 0) "Notify as soon as a new word is out."
            else "Notify after %02d:%02d, once the new word is out.".format(reminder / 60, reminder % 60),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { picking = true }) { Text("Set time") }
            if (reminder >= 0) OutlinedButton(onClick = { settings.updateReminder(-1) }) { Text("Any time") }
        }

        Text("Learning", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge)
        Text("${learning.learned.size} words marked as learned.")

        Text("Backup", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge)
        Text(
            if (folderName == null) "Save your rated words to a file, or merge a backup back in."
            else "Auto-backing up to $folderName/$BACKUP_FILENAME on every change.",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { export.launch(BACKUP_FILENAME) }) { Text("Back up") }
            OutlinedButton(onClick = { import.launch(arrayOf("*/*")) }) { Text("Restore") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickFolder.launch(folderUri?.let(Uri::parse)) }) {
                Text(if (folderName == null) "Auto-backup folder" else "Change folder")
            }
            if (folderUri != null) {
                OutlinedButton(onClick = {
                    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(folderUri), flags) }
                    settings.updateBackupFolder(null)
                }) { Text("Turn off") }
            }
        }

        Text("Pebble", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge)
        Text("Rate words from your watch. This downloads the watchapp and opens it in your Pebble app.")
        OutlinedButton(
            enabled = !installing,
            onClick = {
                installing = true
                scope.launch {
                    runCatching { installWatchapp(context) }
                        .onFailure { say("Couldn't install the watchapp: ${it.message}") }
                    installing = false
                }
            },
        ) { Text(if (installing) "Downloading…" else "Install on Pebble") }
    }

    if (picking) {
        val state = rememberTimePickerState(
            initialHour = if (reminder >= 0) reminder / 60 else 8,
            initialMinute = if (reminder >= 0) reminder % 60 else 0,
            is24Hour = DateFormat.is24HourFormat(context),
        )
        AlertDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    settings.updateReminder(state.hour * 60 + state.minute)
                    picking = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
            text = { TimePicker(state) },
        )
    }
}

private fun folderLabel(context: Context, treeUri: Uri): String = runCatching {
    val docId = DocumentsContract.getTreeDocumentId(treeUri)
    if (docId.startsWith("primary:") && docId.length > "primary:".length) {
        return docId.removePrefix("primary:")
    }
    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
    context.contentResolver.query(
        docUri,
        arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
        null, null, null,
    )?.use { if (it.moveToFirst()) it.getString(0) else null }
        ?: docId.substringAfterLast(':', docId).ifEmpty { "Selected folder" }
}.getOrDefault("Selected folder")
