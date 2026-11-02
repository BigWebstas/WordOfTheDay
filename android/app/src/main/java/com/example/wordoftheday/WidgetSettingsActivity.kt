package com.example.wordoftheday

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Shown when a widget is added and from the widget's Settings entry on long-press. */
class WidgetSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        // Backing out of the picker while adding a widget must cancel the add.
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return finish()

        setContent {
            AppTheme {
                Surface(Modifier.fillMaxSize()) {
                    Settings(WidgetSettings.load(this, id)) { chosen ->
                        chosen.save(this, id)
                        WordWidget.refresh(this, id)
                        setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                        finish()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Settings(initial: WidgetSettings, onSave: (WidgetSettings) -> Unit) {
    var settings by remember { mutableStateOf(initial) }
    Column(
        Modifier.safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Widget settings", style = MaterialTheme.typography.headlineMedium)

        Text("Theme", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WidgetTheme.entries.forEach {
                FilterChip(settings.theme == it, { settings = settings.copy(theme = it) }, { Text(it.name) })
            }
        }

        Text("Word size", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WidgetTextSize.entries.forEach {
                FilterChip(settings.textSize == it, { settings = settings.copy(textSize = it) }, { Text(it.name) })
            }
        }

        Text("When tapped", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WidgetTap.entries.forEach {
                FilterChip(settings.tap == it, { settings = settings.copy(tap = it) }, { Text(it.label) })
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Show definition", Modifier.weight(1f))
            Switch(settings.showDefinition, { settings = settings.copy(showDefinition = it) })
        }

        Text("Background opacity: ${settings.opacity}%", style = MaterialTheme.typography.labelLarge)
        Slider(
            settings.opacity / 100f,
            { settings = settings.copy(opacity = Math.round(it * 20) * 5) },
            valueRange = 0f..1f,
            steps = 19,
        )

        Button(onClick = { onSave(settings) }, Modifier.fillMaxWidth()) { Text("Save") }
    }
}
