package com.damoon.sseuregi.brain

import com.damoon.sseuregi.brain.Command.Engine
import com.damoon.sseuregi.brain.Command.Folder
import com.damoon.sseuregi.brain.Command.Setting
import com.damoon.sseuregi.brain.TextUtil.compact

/**
 * 쓰레기의 "두뇌". 인터넷에 있는 인공지능 없이, 정해 둔 말투를 알아듣는다.
 * 모르는 말은 전부 구글 검색으로 넘긴다.
 */
object CommandParser {

    /** 말끝에 붙는 "해 줘", "주세요", "좀" 같은 꼬리 */
    private val TAILS = listOf(
        "해 주실래요", "해주실래요", "해 주세요", "해주세요", "해 줄래요", "해줄래요", "해 줄래", "해줄래",
        "해 줘요", "해줘요", "해 줘", "해줘", "해 봐요", "해봐요", "해 봐", "해봐", "해 주라", "해주라",
        "주실래요", "주세요", "줄래요", "줄래", "줘요", "줘", "주라", "봐요", "봐", "좀",
    ).sortedByDescending { it.length }

    private const val OBJ = """(?:을\s*좀|를\s*좀|을|를|좀)?"""
    private const val DO = """(?:해요|해|하기|하자|요)?"""
    private const val APP_WORD = """(?:\s*(?:앱|어플|애플리케이션|어플리케이션))?"""

    private val SITE_RE = Regex(
        """^(구글|google|네이버|naver|유튜브|유투브|youtube)\s*(?:에서|에|으로|로)\s*(?:좀\s*)?(.+?)\s*(?:을|를)?\s*(?:검색|찾아|찾아와|틀어|재생|보여|알려|알아봐|열어)?$DO$""",
        RegexOption.IGNORE_CASE,
    )
    private val OPEN_RE = Regex("""^(.+?)$APP_WORD\s*$OBJ\s*(?:열어|열기|실행|켜|틀어|들어가|띄워|시작)$DO$""")
    private val TAP_RE = Regex("""^(.+?)(?:\s*버튼)?\s*$OBJ\s*(?:눌러|누르기|클릭|터치|탭|선택|골라)$DO$""")
    private val SEARCH_RE = Regex("""^(.+?)\s*$OBJ\s*(?:검색|찾아|찾아와|알아봐|알아와|서치|조사)$DO$""")
    private val TELL_RE = Regex("""^(.+?)\s*(?:에\s*대해서|에\s*대해|좀)?\s*(?:알려|가르쳐|설명|말해)$DO$""")
    private val TYPE_SAID_RE = Regex("""^(.+?)\s*(?:이라고|라고|이라구|라구)\s*(?:입력|써|적어|쳐|타이핑|치기)$DO$""")
    private val TYPE_RE = Regex("""^(.+?)\s*$OBJ\s*(?:입력|타이핑)$DO$""")

    private val ORDINAL_RE = Regex(
        """^(첫|두|세|네|다섯|여섯|일곱|여덟|아홉|열|\d+)\s*(?:번째|번)\s*(?:거|것|꺼|결과|링크|항목|검색\s*결과|사이트|영상|동영상|글)?\s*(?:로|으로)?$""",
    )
    private val TOP_RE = Regex("""^맨\s*(?:위|위에|처음|앞)\s*(?:에\s*)?(?:있는\s*)?(?:거|것|결과|링크)?$""")

    private val FILE_OPEN_RE = Regex("""^(.+?)\s*(?:이라는|라는)?\s*파일\s*$OBJ\s*(?:열어|열기|실행|틀어|보여|재생)$DO$""")
    private val FILE_READ_RE = Regex("""^(.+?)\s*(?:이라는|라는)?\s*파일\s*(?:의|에)?\s*(?:내용\s*)?$OBJ\s*(?:읽어|읽기|내용)$DO$""")
    private val FILE_FIND_RE = Regex("""^(.+?)\s*(?:이라는|라는)?\s*파일\s*$OBJ\s*(?:찾아|찾기|검색|어디|있어|어딨).*$""")
    private val FILE_FIND2_RE = Regex("""^파일\s*(?:에서|중에서|중에)?\s*(.+?)\s*$OBJ\s*(?:찾아|찾기|검색)$DO$""")

    private val TIME_RE = Regex("""^(?:지금)?(?:몇시|시간|현재시간|지금시간)(?:야|이야|이지|이에요|예요|인가|인가요|임|알려|알려줘)?$""")
    private val DATE_RE = Regex("""^(?:오늘)?(?:며칠|몇일|날짜|무슨요일|몇요일|몇월며칠|몇월몇일|무슨날)(?:이야|야|이지|이에요|예요|인가|인가요|임|알려|알려줘)?$""")

    private val SETTINGS = listOf(
        "와이파이" to Setting.WIFI, "wifi" to Setting.WIFI, "블루투스" to Setting.BLUETOOTH,
        "비행기모드" to Setting.AIRPLANE, "비행모드" to Setting.AIRPLANE, "화면밝기" to Setting.BRIGHTNESS,
        "밝기" to Setting.BRIGHTNESS, "모바일데이터" to Setting.DATA, "데이터" to Setting.DATA,
        "핫스팟" to Setting.HOTSPOT, "테더링" to Setting.HOTSPOT, "위치" to Setting.LOCATION, "gps" to Setting.LOCATION,
        "소리설정" to Setting.SOUND,
    )
    private val SETTING_REST = Regex("""^(?:좀)?(?:켜|꺼|켜기|끄기|열어|열기|설정|설정열어|on|off|바꿔|조절)?$DO$""")

    private val NATIVE_TENS = mapOf("열" to 10, "스물" to 20, "스무" to 20, "서른" to 30, "마흔" to 40, "쉰" to 50, "예순" to 60)
    private val NATIVE_UNITS = mapOf(
        "한" to 1, "하나" to 1, "두" to 2, "둘" to 2, "세" to 3, "셋" to 3, "석" to 3, "네" to 4, "넷" to 4, "넉" to 4,
        "다섯" to 5, "여섯" to 6, "일곱" to 7, "여덟" to 8, "아홉" to 9,
    )
    private val SINO = mapOf('영' to 0, '일' to 1, '이' to 2, '삼' to 3, '사' to 4, '오' to 5, '육' to 6, '칠' to 7, '팔' to 8, '구' to 9)

    fun parse(input: String): Command {
        val t = stripTails(TextUtil.normalize(input))
        if (t.isBlank()) return Command.Nothing
        val c = compact(t)

        // 1. 멈춰, 도움말, 인사
        if (c in setOf("그만", "멈춰", "스톱", "stop", "중지", "조용", "조용히", "조용히해", "닥쳐", "취소", "그만해", "됐어", "됐어요", "그만읽어") ||
            c.startsWith("그만")
        ) return Command.Stop
        if (listOf("도움말", "뭐할수있", "뭘할수있", "할수있는거", "할수있는게", "사용법", "명령어", "어떻게써").any { c.contains(it) }) return Command.Help
        if (c.length <= 10 && c.contains("이름") && (c.contains("뭐") || c.contains("누구") || c.contains("알려"))) return Command.Name
        if (c in setOf("너누구", "너누구야", "누구야", "넌누구", "넌누구야", "너는누구야")) return Command.Name
        if (listOf("안녕", "하이", "헬로", "hello").any { c.startsWith(it) } && c.length <= 8) return Command.Hello

        // 2. 시간, 날짜, 배터리
        if (TIME_RE.matches(c)) return Command.Time
        if (DATE_RE.matches(c)) return Command.Date
        if (c.contains("배터리") && c.length <= 14) return Command.Battery

        // 3. 손전등, 카메라, 소리, 타이머, 알람
        if (listOf("손전등", "플래시", "플래쉬", "후레쉬", "후레시", "랜턴").any { c.contains(it) } ||
            Regex("^불(?:좀)?(?:켜|꺼|켜기|끄기)$").matches(c)
        ) return Command.Flashlight(on = !(c.contains("꺼") || c.contains("끄") || c.contains("off")))
        if (Regex("^(?:사진|셀카)(?:좀|한장|하나)?(?:찍어|찍기|찍자|찍을래)$").matches(c) || Regex("^카메라(?:좀)?(?:켜|열어|실행|켜기)?$").matches(c)) return Command.Camera
        parseVolume(c)?.let { return it }
        if (c.contains("타이머")) return Command.Timer(parseDuration(t))
        if (c.contains("알람") || c.contains("깨워")) return parseAlarm(t)

        // 4. 기기 설정 (와이파이, 블루투스 …)
        for ((key, setting) in SETTINGS) {
            val i = c.indexOf(key)
            if (i < 0) continue
            val rest = c.removeRange(i, i + key.length)
            if (SETTING_REST.matches(rest)) return Command.OpenSettings(setting)
        }

        // 5. 화면 이동
        if (c.startsWith("뒤로") && c.length <= 7 || c.startsWith("돌아가") || c in setOf("이전", "이전화면", "이전으로", "백", "이전화면으로", "뒤로가기눌러")) return Command.Back
        if (Regex("^(?:홈|홈화면|처음화면|메인화면|바탕화면|홈버튼)(?:으로|에)?(?:가|가기|이동|나가|눌러|누르기)?$").matches(c)) return Command.Home
        if (listOf("최근앱", "최근에사용한앱", "멀티태스킹", "열린앱", "최근실행").any { c.contains(it) }) return Command.Recents
        if (c.contains("알림") && listOf("내려", "열어", "보여", "창", "센터").any { c.contains(it) }) return Command.Notifications
        if (listOf("스크린샷", "캡처", "캡쳐", "스샷", "화면찍어", "화면저장").any { c.contains(it) } &&
            !c.contains("폴더") && !c.contains("파일")
        ) return Command.Screenshot
        if (Regex("^(?:엔터|엔터키|검색버튼|돋보기)(?:쳐|눌러|누르기|키)?$").matches(c)) return Command.Enter
        if (c.length <= 10) {
            if (listOf("아래로", "밑으로", "내려", "스크롤다운", "다음페이지").any { c.contains(it) } || c == "스크롤") return Command.Scroll(down = true)
            if (listOf("위로", "올려", "스크롤업", "맨위로").any { c.contains(it) }) return Command.Scroll(down = false)
        }

        // 6. 화면 읽기
        if (c.contains("화면") && listOf("읽어", "뭐있", "뭐라고", "뭐야", "알려", "설명", "보여", "봐").any { c.contains(it) }) return Command.ReadScreen
        if (listOf("더읽어", "계속읽어", "이거읽어", "여기읽어", "뭐라고써", "뭐라고적혀", "뭐라고나와").any { c.startsWith(it) } || c == "읽어") return Command.ReadScreen

        // 7. 파일
        if (c.contains("파일") || c.contains("폴더")) parseFile(t, c)?.let { return it }

        // 8. 사이트를 콕 집은 검색 ("유튜브에서 고양이 틀어줘")
        SITE_RE.find(t)?.let { m ->
            val engine = when (m.groupValues[1].lowercase()) {
                "네이버", "naver" -> Engine.NAVER
                "유튜브", "유투브", "youtube" -> Engine.YOUTUBE
                else -> Engine.GOOGLE
            }
            val q = m.groupValues[2].trim()
            if (q.isNotEmpty()) return Command.WebSearch(q, engine)
        }

        // 9. 몇 번째 결과
        ordinal(t)?.let { return Command.TapNth(it) }

        // 10. 글자 입력, 누르기, 앱 열기, 검색
        TYPE_SAID_RE.find(t)?.let { return Command.TypeText(cleanTypeText(it.groupValues[1])) }
        TYPE_RE.find(t)?.let { return Command.TypeText(cleanTypeText(it.groupValues[1])) }
        TAP_RE.find(t)?.let { m ->
            val target = m.groupValues[1].trim()
            ordinal(target)?.let { return Command.TapNth(it) }
            return Command.Tap(target)
        }
        OPEN_RE.find(t)?.let { m ->
            val name = m.groupValues[1].trim()
            ordinal(name)?.let { return Command.TapNth(it) }
            if (!name.contains("검색")) return Command.OpenApp(name)
        }
        SEARCH_RE.find(t)?.let { m -> return Command.WebSearch(cleanQuery(m.groupValues[1])) }
        TELL_RE.find(t)?.let { m -> return Command.WebSearch(cleanQuery(m.groupValues[1])) }

        // 11. 나머지는 전부 구글에 물어본다
        return Command.WebSearch(cleanQuery(t))
    }

    fun stripTails(s: String): String {
        var t = s.trim()
        repeat(3) {
            val tail = TAILS.firstOrNull { t.endsWith(it) && t.length > it.length } ?: return t
            t = t.dropLast(tail.length).trim()
        }
        return t
    }

    private fun cleanQuery(q: String): String {
        var s = q.trim()
        for (suffix in listOf("에 대해서", "에 대해", "에대해서", "에대해", "에 관해서", "에 관해")) {
            if (s.endsWith(suffix) && s.length > suffix.length) s = s.dropLast(suffix.length).trim()
        }
        return s
    }

    private fun cleanTypeText(s: String): String =
        s.trim().removePrefix("검색창에").removePrefix("여기에").removePrefix("입력창에").trim()

    fun ordinal(s: String): Int? {
        val t = s.trim()
        if (TOP_RE.matches(t)) return 0
        val m = ORDINAL_RE.find(t) ?: return null
        val n = when (val w = m.groupValues[1]) {
            "첫" -> 1
            else -> parseNumber(w) ?: return null
        }
        return if (n >= 1) n - 1 else null
    }

    fun parseNumber(word: String): Int? {
        val w = word.trim()
        w.toIntOrNull()?.let { return it }
        for ((tw, tv) in NATIVE_TENS) {
            if (w.startsWith(tw)) {
                val rest = w.removePrefix(tw)
                if (rest.isEmpty()) return tv
                NATIVE_UNITS[rest]?.let { return tv + it }
            }
        }
        NATIVE_UNITS[w]?.let { return it }
        if (w.isNotEmpty() && w.all { it in SINO || it == '십' || it == '백' }) {
            var total = 0
            var cur = 0
            for (ch in w) {
                when (ch) {
                    '백' -> { total += (if (cur == 0) 1 else cur) * 100; cur = 0 }
                    '십' -> { total += (if (cur == 0) 1 else cur) * 10; cur = 0 }
                    else -> cur = SINO.getValue(ch)
                }
            }
            return total + cur
        }
        return null
    }

    fun parseDuration(t: String): Int? {
        var total = 0
        var found = false
        for (m in Regex("""(\d+|[가-힣]+?)\s*(시간|분|초)""").findAll(t)) {
            val n = parseNumber(m.groupValues[1]) ?: continue
            found = true
            total += n * when (m.groupValues[2]) {
                "시간" -> 3600
                "분" -> 60
                else -> 1
            }
        }
        if (found && Regex("""시간\s*반""").containsMatchIn(t)) total += 1800
        else if (found && Regex("""분\s*반""").containsMatchIn(t)) total += 30
        return if (found && total > 0) total else null
    }

    private fun parseAlarm(t: String): Command {
        val m = Regex("""(오전|오후|아침|저녁|밤|새벽|낮)?\s*(\d+|[가-힣]+?)\s*시(?!간)\s*(?:(\d+|[가-힣]+?)\s*분|(반))?""").find(t)
            ?: return Command.Alarm(null, 0)
        var hour = parseNumber(m.groupValues[2]) ?: return Command.Alarm(null, 0)
        val minute = when {
            m.groupValues[4] == "반" -> 30
            m.groupValues[3].isNotEmpty() -> parseNumber(m.groupValues[3]) ?: 0
            else -> 0
        }
        when (m.groupValues[1]) {
            "오후", "저녁", "밤", "낮" -> if (hour < 12) hour += 12
            "오전", "아침", "새벽" -> if (hour == 12) hour = 0
        }
        if (hour !in 0..23 || minute !in 0..59) return Command.Alarm(null, 0)
        return Command.Alarm(hour, minute)
    }

    private fun parseVolume(c: String): Command? {
        if (c.length > 10 || listOf("볼륨", "소리", "음량").none { c.contains(it) }) return null
        return when {
            listOf("올려", "키워", "크게", "높여", "업").any { c.contains(it) } -> Command.Volume(+1)
            listOf("내려", "줄여", "작게", "낮춰", "다운").any { c.contains(it) } -> Command.Volume(-1)
            listOf("꺼", "음소거", "무음", "진동").any { c.contains(it) } -> Command.Volume(0)
            else -> null
        }
    }

    private fun folderOf(c: String): Folder? = when {
        c.contains("다운로드") || c.contains("다운받은") || c.contains("download") || c.contains("받은파일") -> Folder.DOWNLOAD
        c.contains("스크린샷") -> Folder.SCREENSHOTS
        c.contains("사진") || c.contains("카메라") || c.contains("갤러리") -> Folder.CAMERA
        c.contains("문서") -> Folder.DOCUMENTS
        c.contains("음악") || c.contains("노래") -> Folder.MUSIC
        c.contains("동영상") || c.contains("영상") -> Folder.MOVIES
        else -> null
    }

    private fun parseFile(t: String, c: String): Command? {
        val folder = folderOf(c)
        if (folder != null && (c.contains("폴더") || c.contains("다운받은") || c.contains("다운로드한") || c.contains("받은파일"))) {
            return Command.ListFolder(folder)
        }
        FILE_OPEN_RE.find(t)?.let { return Command.OpenFile(it.groupValues[1].trim()) }
        FILE_READ_RE.find(t)?.let { return Command.ReadFile(it.groupValues[1].trim()) }
        FILE_FIND_RE.find(t)?.let { return Command.FindFile(it.groupValues[1].trim()) }
        FILE_FIND2_RE.find(t)?.let { return Command.FindFile(it.groupValues[1].trim()) }
        if (!c.contains("파일")) return null
        val rest = t.replace("파일", " ").let { stripTails(it) }
            .replace(Regex("""\s*(?:찾아|검색|보여|목록)\s*$"""), "").trim()
        return if (rest.isNotBlank()) Command.FindFile(rest) else Command.ListFolder(Folder.DOWNLOAD)
    }
}
