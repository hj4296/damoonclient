package com.damoon.sseuregi.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.TextView
import androidx.annotation.RequiresApi
import com.damoon.sseuregi.Assistant
import com.damoon.sseuregi.ListenActivity
import com.damoon.sseuregi.MainActivity
import com.damoon.sseuregi.Prefs
import com.damoon.sseuregi.brain.AnswerPicker
import com.damoon.sseuregi.brain.TextUtil
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * 접근성 서비스: 다른 앱의 화면을 읽고, 대신 눌러 주고, 화면 사진을 찍는다.
 * 사용자가 휴대폰 설정에서 직접 켜야만 동작한다.
 */
class ScreenService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: ScreenService? = null
            private set
    }

    class Item(val text: String, val node: AccessibilityNodeInfo, val bounds: Rect)

    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private val busyListener: (Boolean) -> Unit = { busy -> bubble?.text = if (busy) "⏳" else "🗑️" }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        if (Prefs.bubbleEnabled(this)) showBubble()
        Assistant.addBusyListener(busyListener)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        Assistant.removeBusyListener(busyListener)
        hideBubble()
        if (instance === this) instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ───────────── 화면 읽기 ─────────────

    /** 지금 앞에 떠 있는 (쓰레기 앱이 아닌) 화면의 맨 위 칸 */
    private fun currentRoot(): AccessibilityNodeInfo? {
        val active = rootInActiveWindow
        if (active != null && active.packageName != packageName) return active
        val others = try {
            windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .mapNotNull { it.root }
                .firstOrNull { it.packageName != packageName }
        } catch (e: Exception) {
            null
        }
        return others ?: active
    }

    fun currentPackage(): String? = currentRoot()?.packageName?.toString()

    /** 화면에 보이는 글자 칸들을 위에서부터 순서대로 모은다 */
    fun items(): List<Item> {
        val root = currentRoot() ?: return emptyList()
        val out = ArrayList<Item>()
        walk(root, 0) { node ->
            if (!node.isVisibleToUser) return@walk
            val text = (node.text?.toString()?.takeIf { it.isNotBlank() }
                ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() })
                ?.replace(Regex("\\s+"), " ")?.trim()
            if (!text.isNullOrEmpty()) {
                val r = Rect()
                node.getBoundsInScreen(r)
                if (r.width() > 0 && r.height() > 0) out += Item(text, node, r)
            }
        }
        return out
    }

    fun screenTexts(): List<String> = items().map { it.text }

    private fun walk(node: AccessibilityNodeInfo, depth: Int, visit: (AccessibilityNodeInfo) -> Unit) {
        if (depth > 60) return
        visit(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            walk(child, depth + 1, visit)
        }
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth < 8) {
            if (n.isClickable) return n
            n = n.parent
            depth++
        }
        return null
    }

    // ───────────── 누르기 ─────────────

    /** 말한 글자와 가장 비슷한 칸을 찾는다 */
    fun findBest(target: String): Item? {
        val tg = TextUtil.compact(target)
        if (tg.isEmpty()) return null
        var best: Item? = null
        var bestScore = 0.0
        for (item in items()) {
            val tc = TextUtil.compact(item.text)
            if (tc.isEmpty()) continue
            var score = when {
                tc == tg -> 100.0
                tc.startsWith(tg) -> 80.0 - (tc.length - tg.length) * 0.5
                tc.contains(tg) -> 60.0 - (tc.length - tg.length) * 0.3
                tg.contains(tc) && tc.length >= 2 -> 40.0
                tc.length <= tg.length * 2 + 3 -> TextUtil.similarity(tc, tg).let { if (it >= 0.6) it * 50 else 0.0 }
                else -> 0.0
            }
            if (score <= 0) continue
            if (clickableAncestor(item.node) != null) score += 5
            if (score > bestScore) {
                bestScore = score
                best = item
            }
        }
        return if (bestScore >= 30) best else null
    }

    fun click(item: Item): Boolean {
        val clickable = clickableAncestor(item.node)
        if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        return tap(item.bounds.exactCenterX(), item.bounds.exactCenterY())
    }

    /** 검색 결과처럼 눌러서 들어갈 수 있는 글 링크들 (위에서부터) */
    fun resultLinks(): List<Item> {
        val h = resources.displayMetrics.heightPixels
        val seen = HashSet<String>()
        return items()
            .filter { it.bounds.top > h * 0.12 && it.text.length >= 6 }
            .filter { AnswerPicker.isCandidate(it.text, "") || it.text.length in 6..60 }
            .filter { it.text.lowercase() !in setOf("다른 사용자가 함께 찾은 질문", "관련 검색어", "ai 개요") }
            .mapNotNull { item ->
                val anc = clickableAncestor(item.node) ?: return@mapNotNull null
                val r = Rect()
                anc.getBoundsInScreen(r)
                if (seen.add(r.flattenToString())) Item(item.text, anc, r) else null
            }
            .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
    }

    fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long = 350): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, ms))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun scroll(down: Boolean): Boolean {
        val w = resources.displayMetrics.widthPixels.toFloat()
        val h = resources.displayMetrics.heightPixels.toFloat()
        return if (down) swipe(w / 2, h * 0.72f, w / 2, h * 0.28f) else swipe(w / 2, h * 0.28f, w / 2, h * 0.72f)
    }

    fun global(action: Int): Boolean = performGlobalAction(action)

    // ───────────── 글자 입력 ─────────────

    private fun inputField(): AccessibilityNodeInfo? {
        val root = currentRoot() ?: return null
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { if (it.isEditable) return it }
        var found: AccessibilityNodeInfo? = null
        walk(root, 0) { node -> if (found == null && node.isEditable && node.isVisibleToUser) found = node }
        return found
    }

    fun typeText(text: String): Boolean {
        val field = inputField() ?: return false
        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun pressEnter(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val field = inputField() ?: return false
        return field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    // ───────────── 화면 사진 ─────────────

    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun screenshot(): Bitmap? = suspendCancellableCoroutine { cont ->
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    val bitmap = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                    buffer.close()
                    if (cont.isActive) cont.resume(bitmap)
                }

                override fun onFailure(errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }
            })
        } catch (e: Exception) {
            if (cont.isActive) cont.resume(null)
        }
    }

    // ───────────── 떠 있는 버튼 ─────────────

    fun setBubbleVisible(visible: Boolean) {
        if (visible) showBubble() else hideBubble()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        if (bubble != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val dm = resources.displayMetrics
        val size = (56 * dm.density).toInt()
        val view = TextView(this).apply {
            text = if (Assistant.isBusy) "⏳" else "🗑️"
            textSize = 24f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xE62E7D32.toInt())
                setStroke((2 * dm.density).toInt(), 0xFFFFFFFF.toInt())
            }
            contentDescription = "쓰레기 부르기"
        }
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dm.widthPixels - size - (8 * dm.density).toInt()
            y = dm.heightPixels / 3
        }

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var downTime = 0L
        var dragging = false
        val slop = 12 * dm.density

        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = lp.x; startY = lp.y
                    downTime = SystemClock.uptimeMillis()
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        wm.updateViewLayout(v, lp)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        if (SystemClock.uptimeMillis() - downTime > 600) openApp() else startListening()
                    }
                }
            }
            true
        }
        wm.addView(view, lp)
        bubble = view
        bubbleParams = lp
    }

    private fun hideBubble() {
        val v = bubble ?: return
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (_: Exception) {
        }
        bubble = null
        bubbleParams = null
    }

    private fun startListening() {
        startActivity(
            Intent(this, ListenActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
        )
    }

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
