package com.damoon.sseuregi

import android.content.Context
import android.speech.tts.TextToSpeech
import com.damoon.sseuregi.brain.TextUtil
import java.util.Locale

/** 휴대폰에 들어 있는 "글 읽어 주기" 기능으로 대답을 소리 내어 읽는다 */
object Speaker : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var ready = false
    private val pending = mutableListOf<String>()

    fun init(context: Context) {
        if (tts == null) tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) return
        engine.language = Locale.KOREAN
        engine.setSpeechRate(1.05f)
        ready = true
        pending.forEach { speak(it) }
        pending.clear()
    }

    fun speak(text: String) {
        val spoken = TextUtil.forSpeech(text)
        if (spoken.isBlank()) return
        val engine = tts
        if (engine == null || !ready) {
            pending += spoken
            return
        }
        val max = TextToSpeech.getMaxSpeechInputLength() - 10
        engine.speak(spoken.take(max), TextToSpeech.QUEUE_ADD, null, "sseuregi-${System.nanoTime()}")
    }

    fun stop() {
        pending.clear()
        tts?.stop()
    }
}
