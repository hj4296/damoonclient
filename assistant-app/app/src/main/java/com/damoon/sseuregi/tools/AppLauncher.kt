package com.damoon.sseuregi.tools

import android.content.Context
import android.content.Intent
import com.damoon.sseuregi.brain.TextUtil

/** 말한 이름으로 휴대폰에 깔린 앱을 찾아서 연다 */
object AppLauncher {
    data class AppInfo(val label: String, val packageName: String)

    /** 사람들이 부르는 이름 → 실제 앱 이름에 들어 있을 만한 말 */
    private val ALIASES = mapOf(
        "유튜브" to listOf("youtube", "유튜브"),
        "유투브" to listOf("youtube", "유튜브"),
        "유튜브뮤직" to listOf("youtube music", "유튜브 뮤직"),
        "카톡" to listOf("카카오톡", "kakaotalk"),
        "카카오톡" to listOf("카카오톡", "kakaotalk"),
        "크롬" to listOf("chrome", "크롬"),
        "인스타" to listOf("instagram", "인스타그램"),
        "인스타그램" to listOf("instagram", "인스타그램"),
        "구글" to listOf("google"),
        "지메일" to listOf("gmail"),
        "플레이스토어" to listOf("play 스토어", "play store", "플레이 스토어"),
        "앱스토어" to listOf("play 스토어", "play store", "galaxy store"),
        "갤러리" to listOf("갤러리", "사진", "photos", "gallery"),
        "사진" to listOf("갤러리", "사진", "photos"),
        "카메라" to listOf("카메라", "camera"),
        "시계" to listOf("시계", "clock"),
        "계산기" to listOf("계산기", "calculator"),
        "지도" to listOf("지도", "maps", "네이버 지도", "카카오맵"),
        "구글지도" to listOf("maps", "지도"),
        "넷플릭스" to listOf("netflix"),
        "틱톡" to listOf("tiktok", "틱톡"),
        "디스코드" to listOf("discord"),
        "마크" to listOf("minecraft", "마인크래프트"),
        "마인크래프트" to listOf("minecraft", "마인크래프트"),
        "로블록스" to listOf("roblox"),
        "메시지" to listOf("메시지", "messages"),
        "문자" to listOf("메시지", "messages"),
        "전화" to listOf("전화", "phone"),
        "연락처" to listOf("연락처", "contacts"),
        "설정" to listOf("설정", "settings"),
        "파일" to listOf("내 파일", "파일", "files"),
        "내파일" to listOf("내 파일", "files"),
        "캘린더" to listOf("캘린더", "calendar"),
        "달력" to listOf("캘린더", "calendar"),
        "메모" to listOf("메모", "notes", "keep"),
        "네이버" to listOf("네이버", "naver"),
        "트위터" to listOf("x", "twitter"),
        "스포티파이" to listOf("spotify"),
        "멜론" to listOf("멜론", "melon"),
        "쓰레기" to listOf("쓰레기"),
    )

    fun installed(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        return pm.queryIntentActivities(intent, 0)
            .map { AppInfo(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.packageName }
    }

    fun find(context: Context, spoken: String): AppInfo? {
        val name = TextUtil.compact(spoken).removeSuffix("앱").removeSuffix("어플")
        if (name.isEmpty()) return null
        val wanted = (ALIASES[name] ?: emptyList()).map { TextUtil.compact(it) } + name
        val apps = installed(context)

        var best: AppInfo? = null
        var bestScore = 0.0
        for (app in apps) {
            val label = TextUtil.compact(app.label)
            for (w in wanted) {
                val score = when {
                    label == w -> 100.0
                    label.startsWith(w) -> 80.0 - (label.length - w.length)
                    label.contains(w) && w.length >= 2 -> 60.0 - (label.length - w.length)
                    w.contains(label) && label.length >= 2 -> 45.0
                    else -> TextUtil.similarity(label, w).let { if (it >= 0.6) it * 50 else 0.0 }
                }
                if (score > bestScore) {
                    bestScore = score
                    best = app
                }
            }
        }
        return if (bestScore >= 30) best else null
    }

    fun launch(context: Context, app: AppInfo): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(app.packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return true
    }
}
