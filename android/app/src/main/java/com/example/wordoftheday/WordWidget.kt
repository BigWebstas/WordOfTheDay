package com.example.wordoftheday

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.util.TypedValue
import android.view.View
import android.content.BroadcastReceiver
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.os.Handler
import android.os.Looper
import java.util.Locale
import android.widget.RemoteViews
import kotlin.concurrent.thread

enum class WidgetTheme { System, Light, Dark }

enum class WidgetTap(val label: String) { OpenApp("Open app"), ReadAloud("Read aloud") }

enum class WidgetTextSize(val wordSp: Float) { Small(22f), Medium(28f), Large(36f) }

/** What one placed widget shows and how; chosen in [WidgetSettingsActivity]. */
data class WidgetSettings(
    val theme: WidgetTheme = WidgetTheme.System,
    val textSize: WidgetTextSize = WidgetTextSize.Medium,
    val showDefinition: Boolean = true,
    val tap: WidgetTap = WidgetTap.OpenApp,
    /** Background opacity, 0 (see-through) to 100 (solid); the text always stays solid. */
    val opacity: Int = 100,
) {
    fun save(context: Context, id: Int) {
        prefs(context).edit()
            .putString("theme_$id", theme.name)
            .putString("size_$id", textSize.name)
            .putBoolean("definition_$id", showDefinition)
            .putString("tap_$id", tap.name)
            .putInt("opacity_$id", opacity)
            .apply()
    }

    companion object {
        private fun prefs(context: Context) = context.getSharedPreferences("widget_settings", Context.MODE_PRIVATE)

        fun load(context: Context, id: Int): WidgetSettings {
            val prefs = prefs(context)
            return WidgetSettings(
                theme = prefs.getString("theme_$id", null)?.let { runCatching { WidgetTheme.valueOf(it) }.getOrNull() }
                    ?: WidgetTheme.System,
                textSize = prefs.getString("size_$id", null)?.let { runCatching { WidgetTextSize.valueOf(it) }.getOrNull() }
                    ?: WidgetTextSize.Medium,
                showDefinition = prefs.getBoolean("definition_$id", true),
                tap = prefs.getString("tap_$id", null)?.let { runCatching { WidgetTap.valueOf(it) }.getOrNull() }
                    ?: WidgetTap.OpenApp,
                opacity = prefs.getInt("opacity_$id", 100),
            )
        }

        fun delete(context: Context, ids: IntArray) {
            prefs(context).edit().apply {
                ids.forEach { remove("theme_$it"); remove("size_$it"); remove("definition_$it"); remove("opacity_$it"); remove("tap_$it") }
            }.apply()
        }
    }
}

/** Home-screen widget showing the newest archived word; tapping it opens the app. */
class WordWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        thread {
            try {
                render(context, manager, ids, WordArchive.get(context).all().firstOrNull())
            } finally {
                pending.finish()
            }
        }
    }

    override fun onDeleted(context: Context, ids: IntArray) = WidgetSettings.delete(context, ids)

    companion object {
        /** Redraws every placed widget; call from a background thread, after the archive changes. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, WordWidget::class.java))
            if (ids.isNotEmpty()) render(context, manager, ids, WordArchive.get(context).all().firstOrNull())
        }

        /** Redraws one widget, e.g. after its settings changed. */
        fun refresh(context: Context, id: Int) {
            thread { render(context, AppWidgetManager.getInstance(context), intArrayOf(id), WordArchive.get(context).all().firstOrNull()) }
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray, word: Word?) {
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
            )
            for (id in ids) {
                val settings = WidgetSettings.load(context, id)
                val views = RemoteViews(context.packageName, R.layout.widget_word).apply {
                    if (word != null) {
                        setTextViewText(R.id.widget_date, word.date)
                        setTextViewText(R.id.widget_word, word.word)
                        setTextViewText(R.id.widget_definition, word.shortDefinition)
                    }
                    setTextViewTextSize(R.id.widget_word, TypedValue.COMPLEX_UNIT_SP, settings.textSize.wordSp)
                    setViewVisibility(R.id.widget_definition, if (settings.showDefinition) View.VISIBLE else View.GONE)
                    // System keeps the layout's own night-aware colours; the others override them.
                    when (settings.theme) {
                        WidgetTheme.System -> Unit
                        WidgetTheme.Light -> paint(R.drawable.widget_background_light, "#10233A", "#4B6482")
                        WidgetTheme.Dark -> paint(R.drawable.widget_background_dark, "#E6EEF8", "#9DB3CC")
                    }
                    setInt(R.id.widget_bg, "setImageAlpha", settings.opacity * 255 / 100)
                    val speak = word?.takeIf { settings.tap == WidgetTap.ReadAloud }?.let {
                        PendingIntent.getBroadcast(
                            context, id, Intent(context, SpeakReceiver::class.java).putExtra(SpeakReceiver.TEXT, it.word),
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                        )
                    }
                    setOnClickPendingIntent(R.id.widget_root, speak ?: open)
                }
                manager.updateAppWidget(id, views)
            }
        }

        private fun RemoteViews.paint(background: Int, text: String, secondary: String) {
            setImageViewResource(R.id.widget_bg, background)
            setTextColor(R.id.widget_word, Color.parseColor(text))
            setTextColor(R.id.widget_definition, Color.parseColor(text))
            setTextColor(R.id.widget_date, Color.parseColor(secondary))
        }
    }
}

/** Reads the widget's word aloud without opening the app. */
class SpeakReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra(TEXT) ?: return
        val pending = goAsync()
        var tts: TextToSpeech? = null
        var finished = false
        fun finish() {
            if (finished) return
            finished = true
            tts?.shutdown()
            pending.finish()
        }
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status != TextToSpeech.SUCCESS) return@TextToSpeech finish()
            tts?.language = Locale.US
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) = finish()
                @Deprecated("Required override") override fun onError(utteranceId: String?) = finish()
            })
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "widget")
        }
        // A broadcast may only run for about ten seconds.
        Handler(Looper.getMainLooper()).postDelayed(::finish, 8000)
    }

    companion object {
        const val TEXT = "text"
    }
}
