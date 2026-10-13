package com.example.wordoftheday

import android.net.Uri
import android.text.Html
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL

private const val FEED_URL = "https://www.merriam-webster.com/wotd/feed/rss2"

data class Word(
    val word: String,
    val date: String,
    val shortDefinition: String,
    val pronunciation: String,
    val meaning: String,
    val context: String,
    val example: String,
    val didYouKnow: String,
)

/** Words are identified by date + word; the same word can come around again in a later year. */
val Word.key: String get() = "$date $word"

val Word.shareText: String
    get() = buildString {
        append("Word of the Day: ").append(word)
        if (pronunciation.isNotEmpty()) append("  ").append(pronunciation)
        append("\n").append(meaning)
        if (context.isNotEmpty()) append("\n\n\"").append(context).append("\"")
        append("\n\nhttps://www.merriam-webster.com/dictionary/").append(Uri.encode(word))
    }

/** The same markdown the iOS app exports; [rating] 0 leaves the rating line out. */
fun Word.markdown(rating: Int): String {
    val parts = mutableListOf("# $word")
    parts += listOfNotNull(pronunciation.takeIf { it.isNotEmpty() }?.let { "`$it`" }, date).joinToString(" · ")
    if (rating > 0) parts += "Rating: ${"★".repeat(rating)}${"☆".repeat(5 - rating)}"
    if (meaning.isNotEmpty()) parts += "## Meaning\n\n$meaning"
    if (context.isNotEmpty()) parts += "## In context\n\n> $context"
    if (example.isNotEmpty()) parts += "## Example\n\n> $example"
    if (didYouKnow.isNotEmpty()) parts += "## Did you know?\n\n$didYouKnow"
    parts += "[Merriam-Webster entry](https://www.merriam-webster.com/dictionary/${Uri.encode(word)})"
    return parts.joinToString("\n\n") + "\n"
}

suspend fun fetchWords(): List<Word> = withContext(Dispatchers.IO) {
    val conn = URL(FEED_URL).openConnection() as HttpURLConnection
    conn.connectTimeout = 10_000
    conn.readTimeout = 10_000
    try {
        check(conn.responseCode == 200) { "HTTP ${conn.responseCode}" }
        conn.inputStream.use { parseWords(it) }
    } finally {
        conn.disconnect()
    }
}

private fun parseWords(input: java.io.InputStream): List<Word> {
    val parser = Xml.newPullParser()
    parser.setInput(input, null)
    val words = mutableListOf<Word>()
    var fields = mutableMapOf<String, String>()
    var inItem = false
    while (parser.next() != XmlPullParser.END_DOCUMENT) {
        val name = parser.name
        when (parser.eventType) {
            XmlPullParser.START_TAG -> when {
                name == "item" -> { inItem = true; fields = mutableMapOf() }
                inItem -> fields[name.substringAfter(':')] = parser.nextText()
            }
            XmlPullParser.END_TAG -> if (name == "item") {
                inItem = false
                words += toWord(fields)
            }
        }
    }
    return words
}

private fun toWord(fields: Map<String, String>): Word {
    val shortDefinition = fields["shortdef"].orEmpty().trim()
    // The description is one text blob; split it on its fixed markers.
    val lines = htmlToText(fields["description"].orEmpty()).lines().map { it.trim() }.filter { it.isNotEmpty() }
    val iExamples = lines.indexOf("Examples:")
    val iKnow = lines.indexOf("Did you know?")
    val headEnd = listOf(iExamples, iKnow).filter { it >= 0 }.minOrNull() ?: lines.size
    val head = lines.take(headEnd).filterNot {
        it.startsWith("Merriam-Webster's Word of the Day") || it.startsWith("See the entry")
    }
    val pronunciation = head.firstOrNull { '\\' in it }.orEmpty()
    val context = head.firstOrNull { it.startsWith("//") }.orEmpty()
    return Word(
        word = fields["title"].orEmpty().trim(),
        date = fields["pubDate"].orEmpty().split(" ").take(4).joinToString(" "),
        shortDefinition = shortDefinition,
        pronunciation = pronunciation.substringAfter('\\', "").let { if (it.isEmpty()) "" else "\\$it" }
            .replace(Regex("\\s*[•*]\\s*"), " ").trim(),
        meaning = head.filter { it != pronunciation && it != context }.joinToString(" ").ifEmpty { shortDefinition },
        context = context.removePrefix("//").trim(),
        example = if (iExamples < 0) "" else lines.subList(iExamples + 1, if (iKnow > iExamples) iKnow else lines.size).joinToString(" "),
        didYouKnow = if (iKnow < 0) "" else lines.drop(iKnow + 1).joinToString(" "),
    )
}

private fun htmlToText(html: String): String =
    Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT).toString()
        .replace('￼', ' ')
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
