package com.basavaraj.screenmirror

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

/**
 * Lets the paired iPad control this phone: tap, long-press, swipe, Back/Home/Recents, and typing.
 * Android only allows this through an Accessibility service that the user switches on by hand.
 * Commands are accepted only while a casting session is running (see RelayCastService).
 */
class ControlService : AccessibilityService() {

    companion object {
        @Volatile var instance: ControlService? = null
            private set

        fun isEnabled(ctx: Context): Boolean {
            val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return list.split(':').any { it.endsWith("/.ControlService") || it.endsWith(ControlService::class.java.name) }
        }
    }

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: android.content.Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** Real screen size right now (changes with rotation). */
    private fun screen(): Pair<Float, Float> {
        val wm = getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds; b.width().toFloat() to b.height().toFloat()
        } else {
            val m = android.util.DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
            m.widthPixels.toFloat() to m.heightPixels.toFloat()
        }
    }

    /** x, y arrive as 0..1 (share of the screen width/height), so any iPad size works. */
    fun handle(m: JSONObject) {
        val (w, h) = screen()
        fun px(k: String) = (m.optDouble(k, 0.0).coerceIn(0.0, 1.0) * w).toFloat().coerceIn(0f, w - 1)
        fun py(k: String) = (m.optDouble(k, 0.0).coerceIn(0.0, 1.0) * h).toFloat().coerceIn(0f, h - 1)
        when (m.optString("action")) {
            "tap" -> gesture(Path().apply { moveTo(px("x"), py("y")) }, 50)
            "long" -> gesture(Path().apply { moveTo(px("x"), py("y")) }, 700)
            "swipe" -> gesture(Path().apply { moveTo(px("x1"), py("y1")); lineTo(px("x2"), py("y2")) },
                m.optLong("ms", 300).coerceIn(50, 2000))
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "text" -> typeText(m.optString("text").take(2000), m.optBoolean("enter", false))
        }
    }

    private fun gesture(path: Path, ms: Long) {
        val stroke = GestureDescription.StrokeDescription(path, 0, ms)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    /** Adds text to the box that currently has the cursor (like typing on the phone keyboard). */
    private fun typeText(text: String, enter: Boolean) {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        if (text.isNotEmpty()) {
            val current = node.text?.toString().orEmpty()
            // Hint text is shown when the box is empty; don't keep it.
            val base = if (node.isShowingHintText) "" else current
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, base + text)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
        if (enter && Build.VERSION.SDK_INT >= 30) {
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
    }
}
