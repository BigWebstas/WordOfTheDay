package com.example.wordoftheday

import android.content.Context
import java.time.LocalDate

/** Counts consecutive days the app was opened. */
object Streak {
    /** The streak as of today without counting a visit; zero once a day has been missed. */
    fun current(context: Context): Int {
        val prefs = context.getSharedPreferences("streak", Context.MODE_PRIVATE)
        val last = prefs.getString("last", null)?.let(LocalDate::parse) ?: return 0
        val today = LocalDate.now()
        return if (last == today || last == today.minusDays(1)) prefs.getInt("count", 0) else 0
    }

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
