package com.example.wordoftheday

import android.content.Context
import java.time.LocalDate

/** Counts consecutive days the app was opened. */
object Streak {
    /** Records today's visit and returns the current streak length. */
    fun touch(context: Context): Int {
        val prefs = context.getSharedPreferences("streak", Context.MODE_PRIVATE)
        val today = LocalDate.now()
        val last = prefs.getString("last", null)?.let(LocalDate::parse)
        val count = when (last) {
            today -> prefs.getInt("count", 1)
            today.minusDays(1) -> prefs.getInt("count", 0) + 1
            else -> 1
        }
        prefs.edit().putString("last", today.toString()).putInt("count", count).apply()
        return count
    }
}
