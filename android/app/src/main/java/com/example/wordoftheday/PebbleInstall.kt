package com.example.wordoftheday

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// The watchapp is built with the Pebble SDK, so it is attached to each GitHub release rather than bundled.
private const val WATCHAPP_URL = "https://github.com/BigWebstas/WordOfTheDay/releases/latest/download/WordOfTheDay.pbw"

// Core Devices' Pebble app, then Rebble's.
private val PEBBLE_APPS = listOf("coredevices.coreapp", "io.rebble.cobble")

/** Downloads the watchapp to the cache and offers it to whichever Pebble app is installed. */
suspend fun installWatchapp(context: Context) {
    val file = withContext(Dispatchers.IO) {
        val target = File(context.cacheDir, "shared").apply { mkdirs() }.resolve("WordOfTheDay.pbw")
        val conn = URL(WATCHAPP_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        try {
            check(conn.responseCode == 200) { "HTTP ${conn.responseCode}" }
            conn.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
        } finally {
            conn.disconnect()
        }
        target
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val open = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "application/octet-stream")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // Go straight to a Pebble app when there is one; otherwise the chooser lists every app that opens files.
    val opened = PEBBLE_APPS.any { pkg -> runCatching { context.startActivity(Intent(open).setPackage(pkg)) }.isSuccess }
    if (!opened) context.startActivity(Intent.createChooser(open, null))
}
