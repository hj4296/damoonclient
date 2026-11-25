package com.damoon.sseuregi

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.concurrent.CopyOnWriteArrayList

/** 휴대폰에 들어 있는 음성 인식으로 한국어 말을 글자로 바꾼다 (키 필요 없음) */
class VoiceInput(
    private val context: Context,
    private val onPartial: (String) -> Unit,
    private val onResult: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onReady: () -> Unit = {},
) {
    companion object {
        /** 버튼을 눌러서 듣는 중인 개수. 0이 아니면 "항상 듣기"는 잠깐 쉰다. */
        @Volatile
        var activeCount = 0
            private set
        private val micListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

        fun addMicListener(l: (Boolean) -> Unit) = micListeners.add(l)
        fun removeMicListener(l: (Boolean) -> Unit) = micListeners.remove(l)

        private fun changed(delta: Int) {
            activeCount = (activeCount + delta).coerceAtLeast(0)
            val busy = activeCount > 0
            micListeners.forEach { it(busy) }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var counted = false

    fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("이 폰에서는 음성 인식을 쓸 수 없어요. 'Google' 앱을 설치하거나 업데이트해 주세요.")
            return
        }
        if (!counted) {
            counted = true
            changed(+1) // 항상 듣기가 마이크를 내려놓게 한다
        }
        // 항상 듣기가 마이크를 놓을 시간을 조금 준다
        main.postDelayed({ begin() }, if (WakeService.running) 300L else 0L)
    }

    private fun begin() {
        if (!counted) return
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(listener)
        r.startListening(intent())
    }

    private fun intent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ko-KR")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
    }

    fun stop() {
        recognizer?.stopListening()
    }

    fun destroy() {
        main.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        release()
    }

    private fun release() {
        if (counted) {
            counted = false
            changed(-1)
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = onReady()
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!text.isNullOrBlank()) onPartial(text)
        }

        override fun onResults(results: Bundle?) {
            release()
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (text.isNullOrBlank()) onError("아무 말도 못 들었어요. 다시 말해 주세요.") else onResult(text)
        }

        override fun onError(error: Int) {
            release()
            onError(errorText(error))
        }
    }
}

fun errorText(error: Int): String = when (error) {
    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "아무 말도 못 들었어요. 다시 말해 주세요."
    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "인터넷 연결을 확인해 주세요."
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "마이크 권한이 필요해요. 앱에서 마이크를 허용해 주세요."
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "음성 인식이 바빠요. 잠깐 뒤에 다시 해 주세요."
    SpeechRecognizer.ERROR_AUDIO -> "마이크를 쓸 수 없어요."
    else -> "음성 인식이 잘 안 됐어요. (오류 $error)"
}
