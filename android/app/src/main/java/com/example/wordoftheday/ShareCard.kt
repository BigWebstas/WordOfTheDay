package com.example.wordoftheday

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import java.io.File

/** Renders a word as a picture to post in chats, where plain text looks bare. */
object ShareCard {
    private const val WIDTH = 1080
    private const val HEIGHT = 1350
    private const val MARGIN = 90f

    fun share(context: Context, word: Word, rating: Int) {
        val file = File(context.cacheDir, "shared").apply { mkdirs() }.resolve("${word.key}.png")
        file.outputStream().use { render(word, rating).compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, null))
    }

    private fun render(word: Word, rating: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawRect(
            0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(),
            Paint().apply { shader = LinearGradient(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), 0xFF1B4D89.toInt(), 0xFF0E2747.toInt(), Shader.TileMode.CLAMP) },
        )

        fun paint(size: Float, color: Int, style: Int = Typeface.NORMAL) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = Typeface.create(Typeface.DEFAULT, style)
        }

        var y = 150f
        y = canvas.text(word.date, paint(40f, 0xB3FFFFFF.toInt()), y) + 30f
        y = canvas.text(word.word, paint(150f, Color.WHITE, Typeface.BOLD), y) + 20f
        if (rating > 0) y = canvas.text("★".repeat(rating) + "☆".repeat(5 - rating), paint(70f, 0xFFFFB300.toInt()), y) + 30f
        y = canvas.text(word.pronunciation, paint(44f, 0xCCFFFFFF.toInt(), Typeface.ITALIC), y) + 50f
        canvas.text(word.shortDefinition, paint(64f, Color.WHITE), y)
        canvas.text("Word of the Day", paint(36f, 0x99FFFFFF.toInt()), HEIGHT - 120f)
        return bitmap
    }

    /** Draws [text] wrapped to the card's width with its top at [y]; returns where it ends. */
    private fun Canvas.text(text: String, paint: TextPaint, y: Float): Float {
        val width = (WIDTH - 2 * MARGIN).toInt()
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .build()
        save()
        translate(MARGIN, y)
        layout.draw(this)
        restore()
        return y + layout.height
    }
}
