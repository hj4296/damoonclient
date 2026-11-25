package com.damoon.sseuregi.brain

import com.damoon.sseuregi.brain.TextUtil.compact
import kotlin.math.min

/**
 * 검색 결과 화면에서 읽어 낸 글자들 중 "답"으로 보이는 한 덩어리를 고른다.
 * (화면에 보이는 순서대로 들어온 글자 목록 → 가장 답 같은 문장)
 */
object AnswerPicker {

    private val UI_WORDS = setOf(
        "전체", "이미지", "동영상", "뉴스", "쇼핑", "지도", "도서", "더보기", "더 보기", "도구", "로그인", "설정",
        "google", "google 검색", "검색", "관련 검색어", "다른 사용자가 함께 찾은 질문", "의견 보내기", "개인정보처리방침",
        "약관", "ai 개요", "ai 모드", "자세히 알아보기", "웹", "짧은 동영상", "포럼", "검색 결과", "모두 지우기",
        "음성 검색", "렌즈로 검색", "이미지로 검색", "홈", "탭 전환", "옵션 더보기", "새 탭", "주소창", "search",
        "images", "videos", "news", "sign in", "검색어 지우기", "앱 열기", "앱에서 보기", "다음", "이전", "결과 더보기",
        "모든 필터", "필터", "통합", "블로그", "카페", "지식in", "어학사전", "인플루언서", "네이버", "naver",
    )
    private val ANSWER_LABELS = setOf("ai 개요", "강조 스니펫", "추천 스니펫", "featured snippet", "ai 브리핑")
    private val URL_RE = Regex("""(https?://|www\.)|\b[a-z0-9-]+\.(com|net|org|kr|io|me|tv|wiki|info|co)\b""", RegexOption.IGNORE_CASE)
    private val SENTENCE_END = Regex("""(다|요|음|함|니다)[.!]?$|다\. """)
    private val STOP_WORDS = setOf("뭐야", "누구야", "알려줘", "어때", "언제", "어디", "얼마", "얼마야", "무엇", "무엇인가요", "뭔가요", "해줘", "좀", "그리고")
    private val JOSA = Regex("""(은|는|이|가|을|를|의|에|에서|으로|로|와|과|도|만|이야|야|란|이란|인가요|가요)$""")

    fun keywords(query: String): List<String> =
        query.split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it !in STOP_WORDS }
            .map { w -> if (w.length > 2) w.replace(JOSA, "") else w }
            .filter { it.length >= 2 }

    fun pick(texts: List<String>, query: String): String? {
        if (texts.isEmpty()) return null
        if (query.contains("날씨")) pickWeather(texts)?.let { return it }

        val qc = compact(query)
        val kws = keywords(query)
        val seen = HashSet<String>()
        var best: String? = null
        var bestScore = Double.NEGATIVE_INFINITY

        texts.forEachIndexed { i, raw ->
            val s = raw.replace(Regex("\\s+"), " ").trim()
            if (!seen.add(s) || !isCandidate(s, qc)) return@forEachIndexed
            var score = min(s.length, 220) / 40.0
            score += kws.count { s.contains(it) } * 1.5
            if (SENTENCE_END.containsMatchIn(s)) score += 1.5
            val prev = texts.getOrNull(i - 1)?.trim()?.lowercase()
            if (prev != null && prev in ANSWER_LABELS) score += 4.0
            if (s.length > 400) score -= 1.0
            score -= i * 0.03
            if (score > bestScore) {
                bestScore = score
                best = s
            }
        }
        return best?.let { shorten(it) }
    }

    fun isCandidate(s: String, queryCompact: String): Boolean {
        if (s.length < 12) return false
        val lower = s.lowercase()
        if (lower in UI_WORDS) return false
        val sc = compact(s)
        if (queryCompact.isNotEmpty()) {
            if (sc == queryCompact) return false
            if (sc.startsWith(queryCompact) && sc.length < queryCompact.length + 8) return false
        }
        if (URL_RE.containsMatchIn(s) && s.length < 70) return false
        if (s.count { it.isLetter() } < s.length * 0.4) return false
        if (listOf("광고", "스폰서", "sponsored", "검색결과", "검색 결과", "로그인하", "쿠키").any { lower.contains(it) }) return false
        return true
    }

    /** 너무 길면 문장이 끝나는 곳에서 자른다 */
    fun shorten(s: String, max: Int = 280): String {
        if (s.length <= max) return s
        val head = s.substring(0, max)
        val cut = maxOf(head.lastIndexOf("다. "), head.lastIndexOf("요. "), head.lastIndexOf(". "))
        return if (cut > max / 3) head.substring(0, cut + 2).trim() else "$head…"
    }

    private val TEMP_RE = Regex("""(-?\d{1,2})\s*°""")
    private val CONDITIONS = listOf(
        "대체로 맑음", "구름 조금", "구름 많음", "부분적으로 흐림", "대체로 흐림", "흐리고 비", "맑음", "흐림",
        "소나기", "진눈깨비", "뇌우", "안개", "비", "눈", "황사",
    )

    fun pickWeather(texts: List<String>): String? {
        val temp = texts.firstNotNullOfOrNull { TEMP_RE.find(it)?.groupValues?.get(1) } ?: return null
        val cond = texts.firstNotNullOfOrNull { t ->
            val x = t.trim()
            CONDITIONS.firstOrNull { x == it || (x.startsWith(it) && x.length <= it.length + 6) }
        }
        return if (cond != null) "지금 기온은 ${temp}도, 날씨는 ${TextUtil.ieyo(cond)}." else "지금 기온은 ${temp}도예요."
    }
}
