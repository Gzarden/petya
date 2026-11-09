package com.petya.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Нажимает кнопки в других приложениях (Play, Старт, Отправить). */
class ClickService : AccessibilityService() {
    companion object {
        @Volatile var instance: ClickService? = null
        const val MUSIC = "ru.yandex.music"
        val PLAY = listOf("воспроизвести", "играть", "play", "слушать", "моя волна")
    }

    private val handler = Handler(Looper.getMainLooper())
    private var busy = false
    private var lastAuto = 0L
    private var lastPkg = ""
    private val audio by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        // Яндекс.Музыку открыли (мной или руками) — сама начинает играть
        if (pkg == MUSIC && lastPkg != MUSIC) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastAuto > 30_000) { lastAuto = now; autoPlay() }
        }
        lastPkg = pkg
    }

    private fun norm(s: CharSequence?) = s?.toString()?.lowercase()?.replace('ё', 'е')?.trim() ?: ""

    private fun matches(n: AccessibilityNodeInfo, labels: List<String>): Boolean =
        listOf(norm(n.text), norm(n.contentDescription)).any { t ->
            t.isNotEmpty() && labels.any { l -> t == l || (l.length >= 6 && t.contains(l)) }
        }

    private fun walk(n: AccessibilityNodeInfo?, f: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (n == null) return null
        if (f(n)) return n
        for (i in 0 until n.childCount) walk(n.getChild(i), f)?.let { return it }
        return null
    }

    fun hasAny(labels: List<String>) = walk(rootInActiveWindow) { matches(it, labels) } != null

    fun clickAny(labels: List<String>): Boolean {
        val node = walk(rootInActiveWindow) { matches(it, labels) } ?: return false
        var c: AccessibilityNodeInfo? = node
        while (c != null && !c.isClickable) c = c.parent
        if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val r = Rect(); node.getBoundsInScreen(r)
        val p = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        return dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 60)).build(), null, null
        )
    }

    /** Пробует нажать кнопку несколько раз (приложение ещё грузится). */
    fun clickRetry(labels: List<String>, tries: Int = 10, stop: List<String> = emptyList(), delay: Long = 1500) {
        var n = 0
        fun step() {
            if (n++ >= tries) return
            if (stop.isNotEmpty() && hasAny(stop)) return
            if (clickAny(labels)) return
            handler.postDelayed({ step() }, 1000)
        }
        handler.postDelayed({ step() }, delay)
    }

    private fun mediaPlay() {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
    }

    /** Запустить воспроизведение в открытой Яндекс.Музыке (максимум один клик). */
    fun autoPlay() {
        if (busy) return
        busy = true
        var n = 0
        fun step() {
            if (audio.isMusicActive || n++ > 10) { busy = false; return }
            if (n == 1) mediaPlay()
            else if (clickAny(PLAY)) { handler.postDelayed({ busy = false }, 2000); return }
            handler.postDelayed({ step() }, 1000)
        }
        handler.postDelayed({ step() }, 1500)
    }

    /** Список подписей на экране — чтобы подобрать названия кнопок. */
    fun dump(): String {
        val out = LinkedHashSet<String>()
        fun go(n: AccessibilityNodeInfo?) {
            if (n == null || out.size > 40) return
            val t = (n.text ?: n.contentDescription)?.toString()?.trim()
            if (!t.isNullOrEmpty()) out.add((if (n.isClickable) "[кнопка] " else "") + t.take(40))
            for (i in 0 until n.childCount) go(n.getChild(i))
        }
        go(rootInActiveWindow)
        return out.joinToString("\n")
    }
}
