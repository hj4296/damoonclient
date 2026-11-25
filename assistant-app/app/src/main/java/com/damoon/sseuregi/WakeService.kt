package com.damoon.sseuregi

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.ServiceCompat
import com.damoon.sseuregi.brain.WakeWord

/**
 * "항상 듣기": 앱을 열지 않아도 "야 이 쓰레기야"라고 부르면 대답한다.
 * 안드로이드 규칙상 듣는 동안에는 알림이 계속 떠 있어야 한다.
 */
class WakeService : Service() {

    companion object {
        private const val CHANNEL = "wake"
        private const val NOTIFY_ID = 7
        private const val ACTION_STOP = "com.damoon.sseuregi.STOP_WAKE"

        @Volatile
        var running = false
            private set

        /** 앱이 화면에 떠 있을 때(마이크 쓰는 서비스를 시작해도 될 때)만 부른다 */
        fun start(context: Context): Boolean {
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
            return try {
                context.startForegroundService(Intent(context, WakeService::class.java))
                true
            } catch (e: Exception) {
                false
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeService::class.java))
        }
    }

    private enum class Mode { WAKE, COMMAND }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var onDevice = Build.VERSION.SDK_INT >= 31
    private var mode = Mode.WAKE
    private var listening = false
    private val mutedStreams = mutableListOf<Int>()
    private val audio by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }

    private val speakerListener: (Boolean) -> Unit = { speaking ->
        if (speaking) pause() else schedule(300)
    }
    private val micListener: (Boolean) -> Unit = { manualBusy ->
        if (manualBusy) pause() else schedule(600)
    }
    private val listenRunnable = Runnable { listen() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        try {
            ServiceCompat.startForeground(
                this, NOTIFY_ID, buildNotification(),
                if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
            )
        } catch (e: Exception) {
            // 백그라운드에서 다시 켜지는 등 마이크를 쓸 수 없는 때
            stopSelf()
            return
        }
        running = true
        Speaker.addStateListener(speakerListener)
        VoiceInput.addMicListener(micListener)
        schedule(800)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Prefs.setWakeEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        Speaker.removeStateListener(speakerListener)
        VoiceInput.removeMicListener(micListener)
        recognizer?.destroy()
        recognizer = null
        unmute()
        super.onDestroy()
    }

    // ───────────── 듣기 반복 ─────────────

    private fun canListen(): Boolean =
        running && !Speaker.isSpeaking && VoiceInput.activeCount == 0 &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun schedule(delayMs: Long) {
        if (!running) return
        handler.removeCallbacks(listenRunnable)
        handler.postDelayed(listenRunnable, delayMs)
    }

    private fun pause() {
        handler.removeCallbacks(listenRunnable)
        if (listening) {
            recognizer?.cancel()
            listening = false
            unmute()
        }
    }

    private fun recognizer(): SpeechRecognizer? {
        recognizer?.let { return it }
        val r = try {
            if (onDevice && Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            } else {
                onDevice = false
                if (!SpeechRecognizer.isRecognitionAvailable(this)) return null
                SpeechRecognizer.createSpeechRecognizer(this)
            }
        } catch (e: Exception) {
            onDevice = false
            if (!SpeechRecognizer.isRecognitionAvailable(this)) return null
            SpeechRecognizer.createSpeechRecognizer(this)
        }
        r.setRecognitionListener(listener)
        recognizer = r
        return r
    }

    private fun resetRecognizer() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun listen() {
        if (!canListen() || listening) return
        val r = recognizer() ?: run {
            schedule(30_000)
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        muteBeep()
        listening = true
        try {
            r.startListening(intent)
        } catch (e: Exception) {
            listening = false
            unmute()
            resetRecognizer()
            schedule(2000)
            return
        }
        updateNotification()
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            // 시작 "띵" 소리가 지나간 뒤 소리를 되돌린다
            handler.postDelayed({ unmute() }, 400)
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() = muteBeep()
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onPartialResults(partialResults: Bundle?) {}

        override fun onResults(results: Bundle?) {
            listening = false
            handler.postDelayed({ unmute() }, 300)
            val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().filter { it.isNotBlank() }
            heard(heard)
        }

        override fun onError(error: Int) {
            listening = false
            handler.postDelayed({ unmute() }, 300)
            if (mode == Mode.COMMAND && (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
                mode = Mode.WAKE
                updateNotification()
            }
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> schedule(150)
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> {
                    resetRecognizer()
                    schedule(1500)
                }
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, 11 /* 서버 연결 끊김 */ -> {
                    resetRecognizer()
                    schedule(5000)
                }
                10 /* 요청이 너무 많음 */ -> schedule(15_000)
                12, 13 /* 휴대폰 안 음성 인식에 한국어가 없음 */ -> {
                    onDevice = false
                    resetRecognizer()
                    schedule(300)
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    Prefs.setWakeEnabled(this@WakeService, false)
                    stopSelf()
                }
                else -> {
                    resetRecognizer()
                    schedule(1000)
                }
            }
        }
    }

    private fun heard(texts: List<String>) {
        if (texts.isEmpty()) {
            schedule(150)
            return
        }
        if (mode == Mode.COMMAND) {
            mode = Mode.WAKE
            updateNotification()
            Assistant.handle(texts.first())
            schedule(800)
            return
        }
        val hit = texts.firstNotNullOfOrNull { WakeWord.find(it) }
        when {
            hit == null -> schedule(150)
            hit.command.isNotBlank() -> {
                Assistant.handle(hit.command)
                schedule(800)
            }
            else -> {
                // 부르기만 했으면 대답하고, 다음 말을 명령으로 듣는다
                mode = Mode.COMMAND
                updateNotification()
                Assistant.wake(texts.first())
                schedule(300)
            }
        }
    }

    // ───────────── 시작 "띵" 소리 줄이기 ─────────────

    private fun muteBeep() {
        if (!Prefs.quietBeep(this) || mutedStreams.isNotEmpty()) return
        val streams = mutableListOf(AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM)
        // 노래나 영상이 나오고 있으면 그 소리는 건드리지 않는다
        if (!audio.isMusicActive) streams += AudioManager.STREAM_MUSIC
        for (s in streams) {
            try {
                if (!audio.isStreamMute(s)) {
                    audio.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, 0)
                    mutedStreams += s
                }
            } catch (_: Exception) {
                // 방해 금지 모드 등에서는 못 바꿀 수 있다
            }
        }
    }

    private fun unmute() {
        for (s in mutedStreams) {
            try {
                audio.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0)
            } catch (_: Exception) {
            }
        }
        mutedStreams.clear()
    }

    // ───────────── 알림 ─────────────

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "항상 듣기", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "\"야 이 쓰레기야\"를 기다리는 동안 보이는 알림"
                    setShowBadge(false)
                },
            )
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, WakeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = if (mode == Mode.COMMAND) "말씀하세요… 듣고 있어요" else "\"야 이 쓰레기야\"라고 불러 보세요"
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("🗑️ 쓰레기가 기다리는 중")
            .setContentText(text)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null as Icon?, "끄기", stop).build())
            .build()
    }

    private var lastShownMode: Mode? = null

    private fun updateNotification() {
        if (lastShownMode == mode) return
        lastShownMode = mode
        getSystemService(NotificationManager::class.java).notify(NOTIFY_ID, buildNotification())
    }
}
