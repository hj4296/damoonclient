package com.damoon.sseuregi

import android.content.Context

object Prefs {
    private fun prefs(c: Context) = c.getSharedPreferences("sseuregi", Context.MODE_PRIVATE)

    fun bubbleEnabled(c: Context): Boolean = prefs(c).getBoolean("bubble", true)

    fun setBubbleEnabled(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean("bubble", on).apply()
    }

    /** "야 이 쓰레기야"로 부르기 (항상 듣기) */
    fun wakeEnabled(c: Context): Boolean = prefs(c).getBoolean("wake", false)

    fun setWakeEnabled(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean("wake", on).apply()
    }

    /** 항상 듣기 중 음성 인식 시작 "띵" 소리 줄이기 */
    fun quietBeep(c: Context): Boolean = prefs(c).getBoolean("quiet_beep", true)
}
