package com.damoon.sseuregi

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.damoon.sseuregi.brain.TextUtil
import java.util.Collections
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/** 휴대폰에 들어 있는 "글 읽어 주기" 기능으로 대답을 소리 내어 읽는다 */
object Speaker : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var ready = false
    private val pending = mutableListOf<String>()
    private val talking = Collections.synchronizedSet(HashSet<String>())
    private val main = Handler(Looper.getMainLooper())
    private val stateListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    /** 말하는 중이면 true (항상 듣기가 자기 목소리를 듣지 않게 할 때 쓴다) */
    val isSpeaking: Boolean get() = talking.isNotEmpty() || pending.isNotEmpty()

    fun addStateListener(l: (Boolean) -> Unit) = stateListeners.add(l)
    fun removeStateListener(l: (Boolean) -> Unit) = stateListeners.remove(l)

    fun init(context: Context) {
        if (tts == null) tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            pending.clear()
            notifyState()
            return
        }
        engine.language = Locale.KOREAN
        engine.setSpeechRate(1.05f)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = finished(utteranceId)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finished(utteranceId)
            override fun onError(utteranceId: String?, errorCode: Int) = finished(utteranceId)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finished(utteranceId)
        })
        ready = true
        val queued = pending.toList()
        pending.clear()
        queued.forEach { speak(it) }
        notifyState()
    }

    private fun finished(id: String?) {
        if (id != null) talking.remove(id)
        if (talking.isEmpty()) main.post { notifyState() }
    }

    private fun notifyState() {
        val speaking = isSpeaking
        stateListeners.forEach { it(speaking) }
    }

    fun speak(text: String) {
        val spoken = TextUtil.forSpeech(text)
        if (spoken.isBlank()) return
        val engine = tts
        if (engine == null || !ready) {
            pending += spoken
            notifyState()
            return
        }
        val id = "sseuregi-${System.nanoTime()}"
        talking += id
        notifyState() // 소리가 나기 전에 항상 듣기를 멈추게 한다
        val max = TextToSpeech.getMaxSpeechInputLength() - 10
        if (engine.speak(spoken.take(max), TextToSpeech.QUEUE_ADD, null, id) != TextToSpeech.SUCCESS) finished(id)
    }

    fun stop() {
        pending.clear()
        talking.clear()
        tts?.stop()
        notifyState()
    }
}
