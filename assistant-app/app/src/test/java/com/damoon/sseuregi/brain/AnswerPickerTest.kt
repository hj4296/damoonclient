package com.damoon.sseuregi.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerPickerTest {

    @Test fun picksSnippetOverUiWords() {
        val screen = listOf(
            "google.com/search?q=고양이+수명",
            "고양이 수명",
            "전체", "이미지", "동영상", "뉴스", "더보기",
            "AI 개요",
            "집고양이의 평균 수명은 보통 12년에서 18년 정도이며, 실내에서 키우면 20년 넘게 사는 경우도 있습니다.",
            "고양이 수명 - 나무위키",
            "namu.wiki › w › 고양이",
            "다른 사용자가 함께 찾은 질문",
            "고양이 나이 계산법은?",
        )
        val a = AnswerPicker.pick(screen, "고양이 수명")
        assertTrue(a!!.startsWith("집고양이의 평균 수명은"))
    }

    @Test fun skipsQueryEchoAndAds() {
        val screen = listOf(
            "마인크래프트 최신 버전",
            "스폰서 · 마인크래프트 공식 스토어에서 지금 구매하세요 할인 중",
            "마인크래프트의 최신 정식 버전은 자바 에디션 기준으로 공식 사이트에서 확인할 수 있습니다.",
        )
        val a = AnswerPicker.pick(screen, "마인크래프트 최신 버전")
        assertTrue(a!!.startsWith("마인크래프트의 최신 정식 버전은"))
    }

    @Test fun weather() {
        val screen = listOf("서울특별시 날씨", "8°C", "흐림", "강수확률: 10%", "습도: 60%")
        assertEquals("지금 기온은 8도, 날씨는 흐림이에요.", AnswerPicker.pick(screen, "서울 날씨"))
        assertEquals("지금 기온은 3도, 날씨는 비예요.", AnswerPicker.pick(listOf("3°", "비"), "오늘 날씨"))
    }

    @Test fun nothingUseful() {
        assertNull(AnswerPicker.pick(listOf("전체", "이미지", "로그인"), "아무거나"))
        assertNull(AnswerPicker.pick(emptyList(), "아무거나"))
    }

    @Test fun shortensLongText() {
        val long = "가나다라마바사. ".repeat(10) + "이것은 아주 긴 문장입니다. ".repeat(20)
        val s = AnswerPicker.shorten(long)
        assertTrue(s.length <= 282)
    }

    @Test fun speechCleanup() {
        assertEquals("이름은 쓰레기지만 일은 열심히 해요", TextUtil.forSpeech("이름은 쓰레기지만 일은 열심히 해요 ㅋㅋ"))
        assertEquals("안녕하세요! 저는 쓰레기예요", TextUtil.forSpeech("안녕하세요! 저는 쓰레기예요 🗑️"))
        assertEquals("로그인을", TextUtil.eul("로그인"))
        assertEquals("메뉴를", TextUtil.eul("메뉴"))
    }
}
