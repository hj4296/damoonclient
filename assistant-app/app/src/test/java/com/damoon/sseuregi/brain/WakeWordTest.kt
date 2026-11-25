package com.damoon.sseuregi.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakeWordTest {

    private fun cmd(s: String) = WakeWord.find(s)?.command

    @Test fun wakeOnly() {
        assertEquals("", cmd("야 이 쓰레기야"))
        assertEquals("", cmd("야 이 쓰레기야!"))
        assertEquals("", cmd("쓰레기야"))
        assertEquals("", cmd("야 쓰레기"))
        assertEquals("", cmd("야 이 쓰레기야 ㅋㅋㅋ"))
        assertEquals("", cmd("야 이 스레기야"))
        assertEquals("", cmd("야 이 쓰 레기야"))
    }

    @Test fun wakeWithCommand() {
        assertEquals("유튜브 열어줘", cmd("야 이 쓰레기야 유튜브 열어줘"))
        assertEquals("고양이 수명 알려줘", cmd("쓰레기야, 고양이 수명 알려줘"))
        assertEquals("지금 몇 시야", cmd("쓰레기 지금 몇 시야"))
    }

    @Test fun notACall() {
        assertNull(WakeWord.find("유튜브 열어줘"))
        assertNull(WakeWord.find("쓰레기통 비워야 해"))
        assertNull(WakeWord.find("쓰레기봉투 사러 가자"))
        assertNull(WakeWord.find(""))
    }
}
