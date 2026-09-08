package com.axie.remote.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Injects remote input. Requires the user to enable "Axie Control" in system
 * Accessibility settings (off by default — [MainActivity][com.axie.remote.MainActivity]
 * deep-links there). Needs `android:canPerformGestures="true"` (see res/xml).
 *
 * Phase 0 exposes static helpers for the on-device test buttons; Phase 3
 * ([com.axie.remote.net.SignalingClient]) calls the same helpers with normalized
 * 0..1 coordinates scaled to the current display at dispatch time.
 */
class ControlAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AxieRemote"
        @Volatile private var instance: ControlAccessibilityService? = null

        fun isEnabled(): Boolean = instance != null

        /** Tap the center of the screen (M0 self-test). */
        fun tapCenter(): Boolean {
            val svc = instance ?: return false
            val dm = svc.displaySize()
            return svc.tap(dm.widthPixels / 2f, dm.heightPixels / 2f)
        }

        fun tapNormalized(x: Float, y: Float): Boolean {
            val svc = instance ?: return false
            val dm = svc.displaySize()
            return svc.tap(x * dm.widthPixels, y * dm.heightPixels)
        }

        fun swipeNormalized(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
            val svc = instance ?: return false
            val dm = svc.displaySize()
            return svc.swipe(
                x1 * dm.widthPixels, y1 * dm.heightPixels,
                x2 * dm.widthPixels, y2 * dm.heightPixels,
                durationMs
            )
        }

        fun pressBack(): Boolean =
            instance?.performGlobalAction(GLOBAL_ACTION_BACK) ?: false

        fun pressHome(): Boolean =
            instance?.performGlobalAction(GLOBAL_ACTION_HOME) ?: false

        @Suppress("unused")
        fun pressRecents(): Boolean =
            instance?.performGlobalAction(GLOBAL_ACTION_RECENTS) ?: false
    }

    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "control service connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        Log.i(TAG, "control service disconnected")
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Phase 0: no event processing. Later phases may observe window changes
        // to resolve focused nodes for ACTION_SET_TEXT typing.
    }

    override fun onInterrupt() {
        Log.i(TAG, "control service interrupted")
    }

    private fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 1f, y + 1f)
        }
        return dispatch(path, 0L, 80L)
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        return dispatch(path, 0L, durationMs.coerceIn(50L, 2000L))
    }

    private fun dispatch(path: Path, startMs: Long, durationMs: Long): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false // unreachable: minSdk is 29
        val stroke = GestureDescription.StrokeDescription(path, startMs, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    private fun displaySize(): DisplayMetrics {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as android.view.WindowManager)
            .defaultDisplay.getRealMetrics(dm)
        return dm
    }

    @Suppress("unused")
    private fun typeIntoFocused(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        root.recycle()
        if (focused == null) return false
        return try {
            val args = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } finally {
            focused.recycle()
        }
    }
}
