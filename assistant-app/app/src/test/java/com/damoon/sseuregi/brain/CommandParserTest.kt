package com.damoon.sseuregi.brain

import com.damoon.sseuregi.brain.Command.Engine
import com.damoon.sseuregi.brain.Command.Folder
import com.damoon.sseuregi.brain.Command.Setting
import org.junit.Assert.assertEquals
import org.junit.Test

class CommandParserTest {

    private fun p(s: String) = CommandParser.parse(s)

    @Test fun openApps() {
        assertEquals(Command.OpenApp("유튜브"), p("유튜브 열어줘"))
        assertEquals(Command.OpenApp("유튜브"), p("유튜브 열어 줘"))
        assertEquals(Command.OpenApp("카톡"), p("카톡 켜줘"))
        assertEquals(Command.OpenApp("카카오톡"), p("카카오톡 앱 실행해 줘"))
        assertEquals(Command.OpenApp("인스타"), p("인스타 들어가줘"))
        assertEquals(Command.OpenApp("설정"), p("설정 열어"))
    }

    @Test fun searches() {
        assertEquals(Command.WebSearch("고양이 수명"), p("고양이 수명 검색해줘"))
        assertEquals(Command.WebSearch("고양이 수명"), p("고양이 수명 알려줘"))
        assertEquals(Command.WebSearch("마인크래프트 신규 업데이트"), p("마인크래프트 신규 업데이트 찾아 봐 줘"))
        assertEquals(Command.WebSearch("세종대왕"), p("세종대왕에 대해 알려줘"))
        assertEquals(Command.WebSearch("오늘 날씨 어때"), p("오늘 날씨 어때?"))
        assertEquals(Command.WebSearch("대한민국 수도는 어디야"), p("대한민국 수도는 어디야"))
    }

    @Test fun siteSearches() {
        assertEquals(Command.WebSearch("고양이 영상", Engine.YOUTUBE), p("유튜브에서 고양이 영상 틀어줘"))
        assertEquals(Command.WebSearch("오늘 날씨", Engine.NAVER), p("네이버에서 오늘 날씨 검색해줘"))
        assertEquals(Command.WebSearch("떡볶이 레시피", Engine.GOOGLE), p("구글에서 떡볶이 레시피 찾아줘"))
    }

    @Test fun tapping() {
        assertEquals(Command.Tap("로그인"), p("로그인 눌러줘"))
        assertEquals(Command.Tap("확인"), p("확인 버튼 눌러 줘"))
        assertEquals(Command.Tap("다음"), p("다음을 클릭해줘"))
        assertEquals(Command.TapNth(0), p("첫 번째 거 눌러줘"))
        assertEquals(Command.TapNth(1), p("두번째 결과 눌러"))
        assertEquals(Command.TapNth(2), p("3번째 링크 클릭"))
        assertEquals(Command.TapNth(0), p("첫 번째 거 열어줘"))
        assertEquals(Command.TapNth(0), p("맨 위에 거 눌러"))
    }

    @Test fun typing() {
        assertEquals(Command.TypeText("안녕하세요"), p("안녕하세요라고 입력해줘"))
        assertEquals(Command.TypeText("마인크래프트"), p("검색창에 마인크래프트 입력해 줘"))
        assertEquals(Command.Enter, p("엔터"))
    }

    @Test fun navigation() {
        assertEquals(Command.Back, p("뒤로 가줘"))
        assertEquals(Command.Back, p("뒤로"))
        assertEquals(Command.Home, p("홈 화면으로 가"))
        assertEquals(Command.Scroll(true), p("아래로"))
        assertEquals(Command.Scroll(true), p("좀 내려줘"))
        assertEquals(Command.Scroll(false), p("위로 올려 줘"))
        assertEquals(Command.Notifications, p("알림창 내려줘"))
        assertEquals(Command.Screenshot, p("화면 캡처해줘"))
        assertEquals(Command.Recents, p("최근 앱 보여줘"))
        assertEquals(Command.ReadScreen, p("화면 읽어줘"))
        assertEquals(Command.ReadScreen, p("화면에 뭐라고 써 있어"))
    }

    @Test fun device() {
        assertEquals(Command.Flashlight(true), p("손전등 켜줘"))
        assertEquals(Command.Flashlight(false), p("플래시 꺼"))
        assertEquals(Command.Flashlight(true), p("불 켜줘"))
        assertEquals(Command.Volume(1), p("소리 키워줘"))
        assertEquals(Command.Volume(-1), p("볼륨 줄여"))
        assertEquals(Command.Camera, p("사진 찍어줘"))
        assertEquals(Command.OpenSettings(Setting.WIFI), p("와이파이 켜줘"))
        assertEquals(Command.OpenSettings(Setting.BLUETOOTH), p("블루투스 꺼 줘"))
        assertEquals(Command.WebSearch("서울역 위치"), p("서울역 위치"))
    }

    @Test fun timersAndAlarms() {
        assertEquals(Command.Timer(300), p("5분 타이머 맞춰줘"))
        assertEquals(Command.Timer(90), p("타이머 1분 30초"))
        assertEquals(Command.Timer(5400), p("한 시간 반 타이머"))
        assertEquals(Command.Timer(180), p("삼 분 타이머"))
        assertEquals(Command.Timer(null), p("타이머 맞춰줘"))
        assertEquals(Command.Alarm(7, 0), p("아침 7시 알람 맞춰줘"))
        assertEquals(Command.Alarm(19, 30), p("저녁 7시 반에 알람"))
        assertEquals(Command.Alarm(6, 15), p("6시 15분에 깨워줘"))
        assertEquals(Command.Alarm(12, 0), p("열두 시 알람"))
    }

    @Test fun files() {
        assertEquals(Command.FindFile("숙제"), p("숙제 파일 찾아줘"))
        assertEquals(Command.FindFile("숙제"), p("숙제 파일 어디 있어"))
        assertEquals(Command.OpenFile("발표"), p("발표 파일 열어줘"))
        assertEquals(Command.ReadFile("메모"), p("메모 파일 읽어줘"))
        assertEquals(Command.ListFolder(Folder.DOWNLOAD), p("다운로드 폴더 보여줘"))
        assertEquals(Command.ListFolder(Folder.DOWNLOAD), p("다운받은 파일 보여줘"))
        assertEquals(Command.ListFolder(Folder.SCREENSHOTS), p("스크린샷 폴더 열어줘"))
    }

    @Test fun smallTalk() {
        assertEquals(Command.Hello, p("안녕"))
        assertEquals(Command.Name, p("너 이름이 뭐야"))
        assertEquals(Command.Time, p("지금 몇 시야"))
        assertEquals(Command.Date, p("오늘 며칠이야"))
        assertEquals(Command.Battery, p("배터리 얼마나 남았어"))
        assertEquals(Command.Help, p("너 뭐 할 수 있어"))
        assertEquals(Command.Stop, p("그만"))
        assertEquals(Command.Nothing, p("   "))
        // "몇 시간"은 시간 묻기가 아니라 검색
        assertEquals(Command.WebSearch("서울에서 부산까지 몇 시간 걸려"), p("서울에서 부산까지 몇 시간 걸려"))
    }

    @Test fun numbers() {
        assertEquals(15, CommandParser.parseNumber("열다섯"))
        assertEquals(25, CommandParser.parseNumber("이십오"))
        assertEquals(30, CommandParser.parseNumber("서른"))
        assertEquals(7, CommandParser.parseNumber("일곱"))
    }
}
