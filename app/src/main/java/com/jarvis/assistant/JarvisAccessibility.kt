package com.jarvis.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

/** Gives Jarvis the power to press Home/Back, scroll, lock, screenshot etc. */
class JarvisAccessibility : AccessibilityService() {
    companion object {
        var instance: JarvisAccessibility? = null
        fun global(action: Int): Boolean = instance?.performGlobalAction(action) ?: false

        fun swipe(up: Boolean): Boolean {
            val s = instance ?: return false
            val m = s.resources.displayMetrics
            val x = m.widthPixels / 2f
            val a = m.heightPixels * 0.7f
            val b = m.heightPixels * 0.3f
            val p = Path().apply { if (up) { moveTo(x, a); lineTo(x, b) } else { moveTo(x, b); lineTo(x, a) } }
            val g = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(p, 0, 350)).build()
            return s.dispatchGesture(g, null, null)
        }
    }
    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: android.content.Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
