package com.damoon.sseuregi

import android.content.Context

object Prefs {
    private fun prefs(c: Context) = c.getSharedPreferences("sseuregi", Context.MODE_PRIVATE)

    fun bubbleEnabled(c: Context): Boolean = prefs(c).getBoolean("bubble", true)

    fun setBubbleEnabled(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean("bubble", on).apply()
    }
}
