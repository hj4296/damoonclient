package com.damoon.sseuregi

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.damoon.sseuregi.control.ScreenService
import com.damoon.sseuregi.tools.FileTools

class MainActivity : Activity() {

    companion object {
        @Volatile
        var visible = false
            private set
        private const val REQ_MIC = 1
        private const val REQ_FILES = 2
        private const val REQ_WAKE = 3
    }

    private lateinit var wakeCheck: CheckBox
    private var ignoreWakeChange = false

    private lateinit var chat: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var input: EditText
    private lateinit var mic: Button
    private lateinit var listenState: TextView
    private lateinit var micStatus: TextView
    private lateinit var micButton: Button
    private lateinit var screenStatus: TextView
    private lateinit var screenButton: Button
    private lateinit var screenHint: TextView
    private lateinit var filesStatus: TextView
    private lateinit var filesButton: Button
    private lateinit var voice: VoiceInput

    private var listening = false
    private var listenAfterPermission = false
    private val messageListener: (Assistant.Message) -> Unit = { addBubble(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        chat = findViewById(R.id.chat)
        chatScroll = findViewById(R.id.chat_scroll)
        input = findViewById(R.id.input)
        mic = findViewById(R.id.mic)
        listenState = findViewById(R.id.listen_state)
        micStatus = findViewById(R.id.mic_status)
        micButton = findViewById(R.id.mic_button)
        screenStatus = findViewById(R.id.screen_status)
        screenButton = findViewById(R.id.screen_button)
        screenHint = findViewById(R.id.screen_hint)
        filesStatus = findViewById(R.id.files_status)
        filesButton = findViewById(R.id.files_button)

        voice = VoiceInput(
            this,
            onPartial = { listenState.text = "“$it”" },
            onResult = {
                setListening(false)
                listenState.text = ""
                Assistant.handle(it)
            },
            onError = {
                setListening(false)
                listenState.text = it
            },
            onReady = { listenState.text = "🎤 듣고 있어요… 말해 보세요" },
        )

        mic.setOnClickListener { if (listening) voice.stop() else startListening() }
        findViewById<Button>(R.id.send).setOnClickListener { sendTyped() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendTyped()
                true
            } else {
                false
            }
        }

        micButton.setOnClickListener { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC) }
        screenButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "목록에서 '쓰레기 화면 조종'을 찾아서 켜 주세요", Toast.LENGTH_LONG).show()
        }
        screenHint.setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        filesButton.setOnClickListener { askFileAccess() }

        val bubbleCheck = findViewById<CheckBox>(R.id.bubble_check)
        bubbleCheck.isChecked = Prefs.bubbleEnabled(this)
        bubbleCheck.setOnCheckedChangeListener { _, on ->
            Prefs.setBubbleEnabled(this, on)
            ScreenService.instance?.setBubbleVisible(on)
        }

        wakeCheck = findViewById(R.id.wake_check)
        wakeCheck.setOnCheckedChangeListener { _, on ->
            if (ignoreWakeChange) return@setOnCheckedChangeListener
            Prefs.setWakeEnabled(this, on)
            if (on) turnOnWake() else WakeService.stop(this)
        }

        if (Assistant.messages.isEmpty()) {
            addBubble(Assistant.Message(false, "안녕하세요! 저는 쓰레기예요 🗑️\n아래 🎤 버튼을 누르고 말해 보세요.\n\n" + Assistant.HELP))
        } else {
            Assistant.messages.forEach { addBubble(it) }
        }
        Assistant.addMessageListener(messageListener)
    }

    override fun onResume() {
        super.onResume()
        visible = true
        refreshPermissions()
        // 휴대폰을 다시 켰거나 꺼졌으면, 앱이 보이는 지금 다시 켠다
        if (Prefs.wakeEnabled(this) && !WakeService.running && hasMic()) WakeService.start(this)
        setWakeChecked(Prefs.wakeEnabled(this))
    }

    private fun setWakeChecked(on: Boolean) {
        ignoreWakeChange = true
        wakeCheck.isChecked = on
        ignoreWakeChange = false
    }

    private fun turnOnWake() {
        val need = mutableListOf<String>()
        if (!hasMic()) need += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) need += Manifest.permission.POST_NOTIFICATIONS
        if (need.isNotEmpty()) {
            requestPermissions(need.toTypedArray(), REQ_WAKE)
            return
        }
        if (WakeService.start(this)) {
            Toast.makeText(this, "이제 \"야 이 쓰레기야\"라고 불러 보세요!", Toast.LENGTH_LONG).show()
        } else {
            Prefs.setWakeEnabled(this, false)
            setWakeChecked(false)
            Toast.makeText(this, "항상 듣기를 켜지 못했어요. 마이크 권한을 확인해 주세요.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onPause() {
        visible = false
        super.onPause()
    }

    override fun onDestroy() {
        Assistant.removeMessageListener(messageListener)
        voice.destroy()
        super.onDestroy()
    }

    private fun sendTyped() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        input.setText("")
        Assistant.handle(t)
    }

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun isScreenOn(): Boolean {
        if (ScreenService.instance != null) return true
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.contains("$packageName/")
    }

    private fun startListening() {
        if (!hasMic()) {
            listenAfterPermission = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        Speaker.stop()
        setListening(true)
        listenState.text = "🎤 준비 중…"
        voice.start()
    }

    private fun setListening(on: Boolean) {
        listening = on
        mic.text = if (on) "⏹ 그만 듣기" else "🎤 말하기"
    }

    private fun askFileAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQ_FILES)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshPermissions()
        if (requestCode == REQ_MIC && listenAfterPermission) {
            listenAfterPermission = false
            if (hasMic()) startListening()
        }
        if (requestCode == REQ_WAKE) {
            if (hasMic()) {
                turnOnWake()
            } else {
                Prefs.setWakeEnabled(this, false)
                setWakeChecked(false)
                Toast.makeText(this, "마이크를 허용해야 부르는 소리를 들을 수 있어요.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun refreshPermissions() {
        val micOk = hasMic()
        micStatus.text = if (micOk) "✅ 마이크" else "❌ 마이크 (말 알아듣기)"
        micButton.visibility = if (micOk) View.GONE else View.VISIBLE

        val screenOk = isScreenOn()
        screenStatus.text = if (screenOk) "✅ 화면 조종" else "❌ 화면 조종 (화면 읽기 · 대신 누르기)"
        screenButton.visibility = if (screenOk) View.GONE else View.VISIBLE
        screenHint.visibility = if (!screenOk && Build.VERSION.SDK_INT >= 33) View.VISIBLE else View.GONE

        val filesOk = FileTools.hasAccess(this)
        filesStatus.text = if (filesOk) "✅ 파일 접근" else "❌ 파일 접근 (파일 찾기 · 열기)"
        filesButton.visibility = if (filesOk) View.GONE else View.VISIBLE
    }

    private fun addBubble(m: Assistant.Message) {
        val tv = TextView(this).apply {
            text = m.text
            textSize = 16f
            setTextColor(getColor(R.color.text))
            setTextIsSelectable(true)
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad * 3 / 4, pad, pad * 3 / 4)
            setBackgroundResource(if (m.fromUser) R.drawable.bg_bubble_user else R.drawable.bg_bubble_bot)
        }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = if (m.fromUser) Gravity.END else Gravity.START
            val margin = (6 * resources.displayMetrics.density).toInt()
            setMargins(if (m.fromUser) margin * 8 else 0, margin, if (m.fromUser) 0 else margin * 8, margin)
        }
        chat.addView(tv, lp)
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }
}
