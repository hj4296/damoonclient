package com.damoon.sseuregi

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.widget.Toast
import com.damoon.sseuregi.brain.AnswerPicker
import com.damoon.sseuregi.brain.Command
import com.damoon.sseuregi.brain.Command.Engine
import com.damoon.sseuregi.brain.CommandParser
import com.damoon.sseuregi.brain.TextUtil
import com.damoon.sseuregi.brain.WakeWord
import com.damoon.sseuregi.control.Ocr
import com.damoon.sseuregi.control.ScreenService
import com.damoon.sseuregi.tools.AppLauncher
import com.damoon.sseuregi.tools.Device
import com.damoon.sseuregi.tools.FileTools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/** 알아들은 명령을 실제로 해 주는 곳 */
object Assistant {
    data class Message(val fromUser: Boolean, val text: String)

    private lateinit var ctx: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    val messages = ArrayList<Message>()
    private val messageListeners = CopyOnWriteArrayList<(Message) -> Unit>()
    private val busyListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    @Volatile
    var isBusy = false
        private set

    fun init(context: Context) {
        ctx = context.applicationContext
    }

    fun addMessageListener(l: (Message) -> Unit) = messageListeners.add(l)
    fun removeMessageListener(l: (Message) -> Unit) = messageListeners.remove(l)
    fun addBusyListener(l: (Boolean) -> Unit) = busyListeners.add(l)
    fun removeBusyListener(l: (Boolean) -> Unit) = busyListeners.remove(l)

    /** 들은 말(또는 쓴 말)을 처리한다. 메인 스레드에서 부른다. */
    fun handle(text: String, fromOverlay: Boolean = false) {
        val said = text.trim()
        if (said.isEmpty()) return
        post(Message(true, said))
        Speaker.stop()
        job?.cancel()
        val cmd = CommandParser.parse(said)
        job = scope.launch {
            val me = coroutineContext[Job]
            setBusy(true)
            try {
                // 떠 있는 버튼으로 불렀으면 듣기 창이 닫힐 때까지 잠깐 기다린다
                if (fromOverlay) delay(700)
                run(cmd)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say("앗, 뭔가 잘못됐어요. (${e.message ?: e.javaClass.simpleName})")
            } finally {
                if (job === me) setBusy(false)
            }
        }
    }

    /** "야 이 쓰레기야"라고 부르기만 했을 때 */
    fun wake(heard: String) {
        post(Message(true, heard.trim()))
        Speaker.stop()
        job?.cancel()
        say(WakeWord.REPLIES.random())
    }

    private fun setBusy(b: Boolean) {
        isBusy = b
        busyListeners.forEach { it(b) }
    }

    private fun post(m: Message) {
        messages += m
        if (messages.size > 200) messages.removeAt(0)
        messageListeners.forEach { it(m) }
    }

    /** 화면에 쓰고 소리 내어 읽기 */
    private fun say(text: String) {
        post(Message(false, text))
        Speaker.speak(text)
        if (!MainActivity.visible) Toast.makeText(ctx, text.take(120), Toast.LENGTH_LONG).show()
    }

    /** 화면에만 쓰기 (짧은 확인 메시지) */
    private fun note(text: String) {
        post(Message(false, text))
        if (!MainActivity.visible) Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()
    }

    private suspend fun withScreen(block: suspend (ScreenService) -> Unit) {
        val s = ScreenService.instance
        if (s == null) {
            say("화면을 보고 누르려면 '화면 조종' 권한을 먼저 켜야 해요. 쓰레기 앱을 열고 '켜기'를 눌러 주세요.")
            return
        }
        block(s)
    }

    private suspend fun run(cmd: Command) {
        when (cmd) {
            Command.Nothing -> say("뭐라고 하셨는지 못 들었어요.")
            Command.Stop -> note("멈췄어요.")
            Command.Help -> {
                post(Message(false, HELP))
                Speaker.speak(HELP_SPOKEN)
            }
            Command.Hello -> say(
                listOf(
                    "안녕하세요! 저는 쓰레기예요. 뭐 도와드릴까요?",
                    "네, 쓰레기 여기 있어요! 뭐든 말해 보세요.",
                    "안녕하세요 주인님! 이름은 쓰레기지만 일은 열심히 해요 ㅋㅋ",
                ).random(),
            )
            Command.Name -> say("제 이름은 쓰레기예요. 이름은 쓰레기지만 일은 열심히 해요 ㅋㅋ")
            Command.Time -> say("지금은 ${SimpleDateFormat("a h시 m분", Locale.KOREAN).format(Date())}이에요.")
            Command.Date -> say("오늘은 ${SimpleDateFormat("M월 d일 EEEE", Locale.KOREAN).format(Date())}이에요.")
            Command.Battery -> {
                val p = Device.battery(ctx)
                say("배터리는 ${p}퍼센트 남았어요." + if (Device.isCharging(ctx)) " 지금 충전 중이에요." else "")
            }
            is Command.Flashlight ->
                if (Device.torch(ctx, cmd.on)) note(if (cmd.on) "손전등 켰어요." else "손전등 껐어요.")
                else say("손전등을 쓸 수 없어요.")
            Command.Camera -> if (Device.camera(ctx)) note("카메라 열었어요.") else say("카메라를 못 열었어요.")
            is Command.Volume -> {
                Device.volume(ctx, cmd.change)
                note(
                    when {
                        cmd.change > 0 -> "소리 키웠어요."
                        cmd.change < 0 -> "소리 줄였어요."
                        else -> "소리 껐어요."
                    },
                )
            }
            is Command.Timer -> {
                val sec = cmd.seconds
                when {
                    sec == null -> say("몇 분짜리 타이머인지 말해 주세요. 예를 들면 '5분 타이머'요.")
                    Device.timer(ctx, sec) -> say("${durationText(sec)} 타이머 맞췄어요.")
                    else -> say("타이머를 맞출 시계 앱을 못 찾았어요.")
                }
            }
            is Command.Alarm -> {
                val h = cmd.hour
                when {
                    h == null -> {
                        Device.showAlarms(ctx)
                        say("몇 시에 알람을 맞출지 말해 주세요. 예를 들면 '아침 7시 알람'이요.")
                    }
                    Device.alarm(ctx, h, cmd.minute) -> say("${clockText(h, cmd.minute)}에 알람 맞췄어요.")
                    else -> say("알람을 맞출 시계 앱을 못 찾았어요.")
                }
            }
            is Command.OpenSettings -> {
                Device.settings(ctx, cmd.setting)
                say("${Device.settingName(cmd.setting)} 설정을 열었어요. 여기서 켜고 끌 수 있어요.")
            }
            is Command.OpenApp -> openApp(cmd.name)
            is Command.WebSearch -> webSearch(cmd)
            Command.ReadScreen -> withScreen { readScreen(it) }
            is Command.Tap -> withScreen { tap(it, cmd.target) }
            is Command.TapNth -> withScreen { tapNth(it, cmd.index) }
            is Command.TypeText -> withScreen { s ->
                if (s.typeText(cmd.text)) note("'${cmd.text}' 입력했어요. 보내려면 '엔터'라고 말해 주세요.")
                else say("글자를 넣을 칸을 못 찾았어요. 먼저 입력 칸을 눌러 주세요.")
            }
            Command.Enter -> withScreen { s ->
                if (s.pressEnter()) note("엔터 눌렀어요.") else say("엔터를 못 눌렀어요. 검색 버튼을 눌러 달라고 해 보세요.")
            }
            is Command.Scroll -> withScreen { s ->
                s.scroll(cmd.down)
                note(if (cmd.down) "아래로 내렸어요." else "위로 올렸어요.")
            }
            Command.Back -> withScreen { s ->
                s.global(AccessibilityService.GLOBAL_ACTION_BACK)
                note("뒤로 갔어요.")
            }
            Command.Home -> {
                val s = ScreenService.instance
                if (s != null) s.global(AccessibilityService.GLOBAL_ACTION_HOME)
                else ctx.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                note("홈 화면으로 갔어요.")
            }
            Command.Recents -> withScreen { it.global(AccessibilityService.GLOBAL_ACTION_RECENTS) }
            Command.Notifications -> withScreen { it.global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS) }
            Command.Screenshot -> withScreen { s ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    delay(500)
                    s.global(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
                    note("화면을 캡처했어요.")
                } else {
                    say("이 폰에서는 캡처를 대신 못 해요.")
                }
            }
            is Command.FindFile -> findFile(cmd.name)
            is Command.OpenFile -> openFile(cmd.name)
            is Command.ReadFile -> readFile(cmd.name)
            is Command.ListFolder -> listFolder(cmd.folder)
        }
    }

    // ───────────── 앱 ─────────────

    private suspend fun openApp(name: String) {
        val found = withContext(Dispatchers.Default) { AppLauncher.find(ctx, name) }
        if (found != null && AppLauncher.launch(ctx, found)) note("${found.label} 열었어요.")
        else say("'${name}' 앱을 못 찾았어요. 앱 이름을 다시 말해 주세요.")
    }

    // ───────────── 검색 ─────────────

    private suspend fun webSearch(cmd: Command.WebSearch) {
        val q = cmd.query
        val enc = URLEncoder.encode(q, "UTF-8")
        val (url, where) = when (cmd.engine) {
            Engine.GOOGLE -> "https://www.google.com/search?hl=ko&q=$enc" to "구글"
            Engine.NAVER -> "https://m.search.naver.com/search.naver?query=$enc" to "네이버"
            Engine.YOUTUBE -> "https://www.youtube.com/results?search_query=$enc" to "유튜브"
        }
        val s = ScreenService.instance
        val before = s?.screenTexts() ?: emptyList()
        say("${where}에서 '${q}' 찾아볼게요.")
        if (!Device.openUrl(ctx, url)) {
            say("인터넷 앱을 못 열었어요.")
            return
        }
        if (cmd.engine == Engine.YOUTUBE) {
            note("보고 싶은 영상이 있으면 '첫 번째 거 눌러줘'라고 말해 보세요.")
            return
        }
        if (s == null) {
            note("'화면 조종' 권한을 켜 주면 검색 결과를 제가 읽어 드릴 수 있어요.")
            return
        }
        val texts = waitForPage(s, before, q)
        var answer = AnswerPicker.pick(texts, q)
        if (answer == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // 글자 칸을 못 읽는 화면이면 화면 사진을 찍어서 글자를 알아본다
            s.screenshot()?.let { answer = AnswerPicker.pick(Ocr.read(it), q) }
        }
        val a = answer
        if (a != null) {
            say(a)
            note("더 보려면 '첫 번째 거 눌러줘', '아래로', '화면 읽어줘'라고 말해 보세요.")
        } else {
            say("딱 맞는 답은 못 찾았어요. 화면을 직접 봐 주세요.")
        }
    }

    /** 검색 결과가 다 뜰 때까지 기다린다 (최대 약 11초) */
    private suspend fun waitForPage(s: ScreenService, before: List<String>, query: String): List<String> {
        delay(1500)
        val key = TextUtil.compact(query).take(6)
        var last = emptyList<String>()
        repeat(20) { i ->
            val now = s.screenTexts()
            val changed = now != before && now.size >= 5
            val showsQuery = now.any { TextUtil.compact(it).contains(key) }
            if (changed && (showsQuery || i >= 8) && now == last) return now
            last = now
            delay(500)
        }
        return last
    }

    // ───────────── 화면 ─────────────

    private suspend fun readScreen(s: ScreenService) {
        var lines = s.screenTexts().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (lines.joinToString("").length < 40 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            s.screenshot()?.let { bmp -> Ocr.read(bmp).takeIf { it.isNotEmpty() }?.let { lines = it } }
        }
        if (lines.isEmpty()) {
            say("화면에서 글자를 못 찾았어요.")
            return
        }
        val text = AnswerPicker.shorten(lines.joinToString(". "), 500)
        say("화면에 이렇게 나와 있어요. $text")
    }

    private suspend fun tap(s: ScreenService, target: String) {
        val item = s.findBest(target)
        if (item != null && s.click(item)) {
            note("'${item.text.take(25)}' 눌렀어요.")
            return
        }
        // 글자 칸으로 못 찾으면 화면 사진에서 글자를 찾아서 그 자리를 누른다
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bmp = s.screenshot()
            if (bmp != null) {
                val box = bestBox(Ocr.readLines(bmp), target)
                if (box != null && s.tap(box.second.exactCenterX(), box.second.exactCenterY())) {
                    note("'${box.first.take(25)}' 눌렀어요.")
                    return
                }
            }
        }
        say("화면에서 '${TextUtil.eul(target)}' 못 찾았어요.")
    }

    private fun bestBox(lines: List<Pair<String, Rect>>, target: String): Pair<String, Rect>? {
        val tg = TextUtil.compact(target)
        return lines
            .map { line ->
                val tc = TextUtil.compact(line.first)
                val score = when {
                    tc == tg -> 1.0
                    tc.contains(tg) -> 0.9
                    else -> TextUtil.similarity(tc, tg)
                }
                line to score
            }
            .filter { it.second >= 0.6 }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun tapNth(s: ScreenService, index: Int) {
        val links = s.resultLinks()
        val item = links.getOrNull(index)
        if (item != null && s.click(item)) note("${index + 1}번째 '${item.text.take(25)}' 눌렀어요.")
        else say("${index + 1}번째 항목을 못 찾았어요.")
    }

    // ───────────── 파일 ─────────────

    private fun canUseFiles(): Boolean {
        if (FileTools.hasAccess(ctx)) return true
        say("파일을 보려면 쓰레기 앱을 열고 '파일 접근'을 허용해 주세요.")
        return false
    }

    private suspend fun search(name: String): List<File> = withContext(Dispatchers.IO) { FileTools.find(name) }

    private suspend fun findFile(name: String) {
        if (!canUseFiles()) return
        val found = search(name)
        if (found.isEmpty()) {
            say("'${name}' 이름이 들어간 파일을 못 찾았어요.")
            return
        }
        say("${found.size}개 찾았어요. " + found.take(3).joinToString(", ") { it.name })
        note(found.joinToString("\n") { "• " + it.absolutePath.removePrefix(FileTools.root.absolutePath + "/") })
    }

    private suspend fun openFile(name: String) {
        if (!canUseFiles()) return
        val f = search(name).firstOrNull()
        when {
            f == null -> say("'${name}' 파일을 못 찾았어요.")
            FileTools.open(ctx, f) -> note("${f.name} 열었어요.")
            else -> say("${f.name} 파일을 열 수 있는 앱이 없어요.")
        }
    }

    private suspend fun readFile(name: String) {
        if (!canUseFiles()) return
        val f = search(name).firstOrNull()
        when {
            f == null -> say("'${name}' 파일을 못 찾았어요.")
            !FileTools.isText(f) -> say("${f.name} 파일은 글자 파일이 아니라서 못 읽어요. '열어줘'라고 하면 열어 드릴게요.")
            else -> {
                val content = withContext(Dispatchers.IO) { FileTools.readText(f) }
                say(if (content.isBlank()) "${f.name} 파일은 비어 있어요." else "${f.name} 내용이에요. $content")
            }
        }
    }

    private suspend fun listFolder(folder: Command.Folder) {
        if (!canUseFiles()) return
        val files = withContext(Dispatchers.IO) { FileTools.list(folder) }
        val name = FileTools.folderName(folder)
        if (files.isEmpty()) say("${name} 폴더가 비어 있어요.")
        else say("${name} 폴더의 최근 파일이에요. " + files.joinToString(", ") { it.name })
    }

    // ───────────── 말 다듬기 ─────────────

    private fun durationText(sec: Int): String {
        val h = sec / 3600
        val m = sec % 3600 / 60
        val s = sec % 60
        return listOfNotNull(
            if (h > 0) "${h}시간" else null,
            if (m > 0) "${m}분" else null,
            if (s > 0) "${s}초" else null,
        ).joinToString(" ")
    }

    private fun clockText(h: Int, m: Int): String {
        val ampm = if (h < 12) "오전" else "오후"
        val hh = if (h % 12 == 0) 12 else h % 12
        return if (m == 0) "$ampm ${hh}시" else "$ampm ${hh}시 ${m}분"
    }

    private const val HELP_SPOKEN =
        "이렇게 말해 보세요. 유튜브 열어줘, 고양이 수명 알려줘, 화면 읽어줘, 로그인 눌러줘, 첫 번째 거 눌러줘, 아래로, 뒤로 가, 5분 타이머, 손전등 켜줘."

    val HELP = """
        🗑️ 쓰레기한테 이렇게 말해 보세요
        • "야 이 쓰레기야" → 앱을 안 열어도 대답해요 ('항상 듣기'를 켜면)
        • "야 이 쓰레기야, 유튜브 열어줘"처럼 한 번에 말해도 돼요
        • 궁금한 거 아무거나 → 구글에서 찾아서 읽어 줘요 (예: 고양이 수명 알려줘)
        • 유튜브에서 고양이 영상 틀어줘 / 네이버에서 오늘 날씨 검색해줘
        • 유튜브 열어줘 / 카톡 켜줘 / 설정 열어줘
        • 화면 읽어줘
        • 로그인 눌러줘 / 첫 번째 거 눌러줘
        • 안녕하세요라고 입력해줘 / 엔터
        • 아래로 / 위로 / 뒤로 가 / 홈으로
        • 알림창 내려줘 / 화면 캡처해줘 / 최근 앱
        • 숙제 파일 찾아줘 / 숙제 파일 열어줘 / 메모 파일 읽어줘 / 다운로드 폴더 보여줘
        • 5분 타이머 / 아침 7시 알람 / 손전등 켜줘 / 소리 키워 / 와이파이 켜줘
        • 지금 몇 시야 / 오늘 며칠이야 / 배터리 얼마나 남았어
        • 그만 (말 멈추기)
    """.trimIndent()
}
