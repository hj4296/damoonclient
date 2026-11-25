package com.damoon.sseuregi.brain

/**
 * "야 이 쓰레기야" 같은 부르는 말을 찾는다.
 * "야 이 쓰레기야 유튜브 열어줘"처럼 한 번에 말하면 뒤쪽("유튜브 열어줘")을 명령으로 돌려준다.
 */
object WakeWord {
    /** 음성 인식이 조금 틀리게 받아 적는 경우까지 */
    private val WORDS = listOf("쓰레기", "스레기", "쓰래기", "스래기", "쓰레게", "쓰렉이")

    /** 부르는 말 바로 뒤에 붙어도 되는 말 ("쓰레기야", "쓰레기님") */
    private val CALL_ENDING = Regex("""^(?:야|아|님|씨|이야|야아|야야)?(?=$|[\s,.!?~…]|ㅋ|ㅎ)""")
    private val LEADING_JUNK = Regex("""^[\s,.!?~…ㅋㅎ]+""")

    data class Hit(val command: String)

    fun find(heard: String): Hit? {
        val text = TextUtil.normalize(heard)
        var i = -1
        var word = ""
        for (w in WORDS) {
            val at = text.lastIndexOf(w)
            if (at > i) {
                i = at
                word = w
            }
        }
        if (i < 0) {
            // "쓰 레기"처럼 띄어 적은 경우: 명령은 나눌 수 없으니 부르기만 한 걸로 본다
            val c = TextUtil.compact(text)
            return if (WORDS.any { c.contains(it) }) Hit("") else null
        }
        val after = text.substring(i + word.length)
        // "쓰레기통", "쓰레기봉투" 같은 말은 부른 게 아니다
        val ending = CALL_ENDING.find(after) ?: return null
        val rest = after.substring(ending.value.length).replace(LEADING_JUNK, "")
        return Hit(TextUtil.normalize(rest))
    }

    /** 불렀을 때 하는 대답 */
    val REPLIES = listOf(
        "네? 왜 불러요 ㅋㅋ",
        "아니 쓰레기라니… 그래도 말씀하세요 ㅋㅋ",
        "쓰레기 대기 중! 뭐 해 드릴까요?",
        "부르셨어요? 듣고 있어요.",
        "네 주인님, 말씀하세요.",
        "쓰레기 등장! 뭐든 말해 보세요.",
    )
}
