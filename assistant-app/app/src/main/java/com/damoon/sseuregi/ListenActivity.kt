package com.damoon.sseuregi

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.TextView

/** 떠 있는 🗑️ 버튼을 누르면 잠깐 나타나서 말을 듣고 사라지는 창 */
class ListenActivity : Activity() {
    private lateinit var label: TextView
    private lateinit var voice: VoiceInput

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_listen)
        label = findViewById(R.id.listen_text)
        findViewById<TextView>(R.id.listen_cancel).setOnClickListener { close() }
        findViewById<android.view.View>(R.id.listen_root).setOnClickListener { close() }

        voice = VoiceInput(
            this,
            onPartial = { label.text = "“$it”" },
            onResult = { text ->
                label.text = "“$text”"
                Assistant.handle(text, fromOverlay = true)
                close()
            },
            onError = { msg ->
                label.text = msg
                label.postDelayed({ close() }, 1600)
            },
            onReady = { label.text = "말해 보세요…" },
        )

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            // 항상 듣기가 꺼져 있었으면 (휴대폰을 다시 켰을 때 등) 지금 다시 켠다
            if (Prefs.wakeEnabled(this) && !WakeService.running) WakeService.start(this)
            begin()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    private fun begin() {
        Speaker.stop()
        label.text = "준비 중…"
        voice.start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            begin()
        } else {
            label.text = "마이크 권한이 있어야 말을 알아들을 수 있어요."
            label.postDelayed({ close() }, 1800)
        }
    }

    private fun close() {
        if (isFinishing) return
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        voice.destroy()
        super.onDestroy()
    }
}
