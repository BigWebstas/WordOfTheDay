package com.example.wordoftheday

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.LocalTime
import java.util.concurrent.TimeUnit

private const val CHANNEL_ID = "word_of_the_day"
private const val PREFS = "notifier"
private const val LAST_SEEN = "last_seen_word"

/** Checks the feed every hour; notifies once per new word, once the chosen reminder time has passed. */
object WordNotifier {
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<WordWorker>(1, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork("daily-word", ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** Called when the user has seen [word] in the app, so it is not notified afterwards. */
    fun markSeen(context: Context, word: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(LAST_SEEN, word).apply()
    }

    fun notifyIfNew(context: Context, word: Word) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(LAST_SEEN, null) == word.word) return
        // Too early for the chosen time: leave the word unseen so a later check notifies.
        val reminder = AppSettings.get(context).reminderMinutes
        if (reminder >= 0 && LocalTime.now().let { it.hour * 60 + it.minute } < reminder) return

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Word of the Day", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            1,
            android.app.Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Word of the Day: ${word.word}")
                .setContentText(word.shortDefinition)
                .setStyle(android.app.Notification.BigTextStyle().bigText(word.shortDefinition))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
        markSeen(context, word.word)
    }
}

class WordWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        fetchAndArchive(applicationContext).firstOrNull()?.let { WordNotifier.notifyIfNew(applicationContext, it) }
        Result.success()
    } catch (e: Exception) {
        Result.retry()
    }
}
