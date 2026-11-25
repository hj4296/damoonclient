package com.damoon.sseuregi.brain

object TextUtil {
    private val SPACES = Regex("\\s+")
    private val TRAILING_MARKS = Regex("[.!?~,。…]+$")

    fun normalize(s: String): String =
        s.replace(SPACES, " ").trim().replace(TRAILING_MARKS, "").trim()

    /** 띄어쓰기를 모두 없애고 소문자로 바꾼다. 음성 인식은 띄어쓰기가 제멋대로라서 비교할 때 쓴다. */
    fun compact(s: String): String = s.replace(SPACES, "").lowercase()

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** 0.0(완전 다름) ~ 1.0(같음) */
    fun similarity(a: String, b: String): Double {
        val x = compact(a)
        val y = compact(b)
        val max = maxOf(x.length, y.length)
        if (max == 0) return 1.0
        return 1.0 - levenshtein(x, y).toDouble() / max
    }

    /** 마지막 글자에 받침이 있는지. 한글이 아니면 false. */
    fun hasBatchim(word: String): Boolean {
        val c = word.trim().lastOrNull() ?: return false
        if (c !in '가'..'힣') return false
        return (c - '가') % 28 != 0
    }

    /** "로그인을" / "확인을" / "메뉴를" */
    fun eul(word: String): String = word + if (hasBatchim(word)) "을" else "를"

    /** "흐림이에요" / "비예요" */
    fun ieyo(word: String): String = word + if (hasBatchim(word)) "이에요" else "예요"

    /** 소리 내어 읽을 때 "ㅋㅋ", 이모지 같은 건 빼고 읽는다 */
    fun forSpeech(s: String): String =
        s.replace(Regex("[ㄱ-ㅎㅏ-ㅣ]+"), " ")
            .replace(Regex("[\\p{So}\\p{Cn}\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}]"), " ")
            .replace(SPACES, " ")
            .trim()
}
