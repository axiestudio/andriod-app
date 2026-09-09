package com.axie.remote.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

/**
 * Injects remote input (goal.md §3 + SPEC.md §5).
 *
 * Requires the user to enable "Axie Control" in system Accessibility settings
 * (off by default — MainActivity deep-links there; Android offers no API to
 * enable it silently). Needs `android:canPerformGestures="true"` for
 * [dispatchGesture] and `android:canRetrieveWindowContent="true"` for typing
 * and node scrolling (see res/xml/accessibilityservice.xml).
 *
 * Coordinates arrive normalized 0..1 and are scaled to the current display at
 * dispatch time, so rotation is safe.
 */
class ControlAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AxieRemote"
        @Volatile private var instance: ControlAccessibilityService? = null

        fun isEnabled(): Boolean = instance != null

        /**
         * System truth: is our service enabled in Settings? Unlike [isEnabled],
         * this survives process death — the system binds the service in its own
         * lifecycle, so a null [instance] never means "disabled".
         */
        fun isEnabled(context: Context): Boolean {
            val am = context.getSystemService(AccessibilityManager::class.java)
                ?: return false
            return am.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            ).any {
                val si = it.resolveInfo.serviceInfo
                si.packageName == context.packageName &&
                    si.name == ControlAccessibilityService::class.java.name
            }
        }

        /** Tap the center of the screen (on-device self-test). */
        fun tapCenter(): Boolean {
            val svc = instance ?: return false
            val (w, h) = svc.displaySize()
            return svc.tap(w / 2f, h / 2f)
        }

        fun tapNormalized(x: Float, y: Float): Boolean {
            val svc = instance ?: return false
            val (w, h) = svc.displaySize()
            return svc.tap(x.coerceIn(0f, 1f) * w, y.coerceIn(0f, 1f) * h)
        }

        fun swipeNormalized(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
            val svc = instance ?: return false
            val (w, h) = svc.displaySize()
            return svc.swipe(
                x1.coerceIn(0f, 1f) * w, y1.coerceIn(0f, 1f) * h,
                x2.coerceIn(0f, 1f) * w, y2.coerceIn(0f, 1f) * h,
                durationMs
            )
        }

        fun longPressNormalized(x: Float, y: Float, durationMs: Long): Boolean {
            val svc = instance ?: return false
            val (w, h) = svc.displaySize()
            return svc.longPress(x.coerceIn(0f, 1f) * w, y.coerceIn(0f, 1f) * h, durationMs)
        }

        fun scrollNormalized(x: Float, y: Float, down: Boolean): Boolean {
            val svc = instance ?: return false
            val (w, h) = svc.displaySize()
            return svc.scroll(x.coerceIn(0f, 1f) * w, y.coerceIn(0f, 1f) * h, down)
        }

        fun typeText(text: String): Boolean =
            instance?.typeIntoFocused(text) ?: false

        fun pressBack(): Boolean =
            instance?.performGlobalAction(GLOBAL_ACTION_BACK) ?: false

        fun pressHome(): Boolean =
            instance?.performGlobalAction(GLOBAL_ACTION_HOME) ?: false

        fun pressRecents(): Boolean =
            instance?.performGlobalAction(GLOBAL_ACTION_RECENTS) ?: false

        /**
         * Single entry point for the signaling channel (SPEC.md §5 + Phase-3
         * extensions). Returns true when the command was dispatched.
         *
         * Accepted shapes:
         *  {"type":"tap","x":0.5,"y":0.3}
         *  {"type":"swipe","x1":..,"y1":..,"x2":..,"y2":..,"durationMs":400}
         *  {"type":"drag", same as swipe but long duration}
         *  {"type":"longpress","x":..,"y":..,"durationMs":600}
         *  {"type":"scroll","x":..,"y":..,"direction":"down"|"up"}
         *  {"type":"key","action":"back"|"home"|"recents"}
         *  {"type":"text","text":"hello"}
         */
        fun handleRemoteCommand(msg: JSONObject): Boolean {
            return try {
                when (msg.optString("type")) {
                    "tap" -> tapNormalized(
                        msg.optDouble("x", 0.5).toFloat(),
                        msg.optDouble("y", 0.5).toFloat()
                    )
                    "swipe", "drag" -> swipeNormalized(
                        msg.optDouble("x1", 0.5).toFloat(),
                        msg.optDouble("y1", 0.5).toFloat(),
                        msg.optDouble("x2", 0.5).toFloat(),
                        msg.optDouble("y2", 0.5).toFloat(),
                        msg.optLong("durationMs", if (msg.optString("type") == "drag") 800 else 400)
                    )
                    "longpress" -> longPressNormalized(
                        msg.optDouble("x", 0.5).toFloat(),
                        msg.optDouble("y", 0.5).toFloat(),
                        msg.optLong("durationMs", 600)
                    )
                    "scroll" -> scrollNormalized(
                        msg.optDouble("x", 0.5).toFloat(),
                        msg.optDouble("y", 0.5).toFloat(),
                        msg.optString("direction", "down") != "up"
                    )
                    "key" -> when (msg.optString("action")) {
                        "back" -> pressBack()
                        "home" -> pressHome()
                        "recents" -> pressRecents()
                        else -> {
                            Log.w(TAG, "unknown key action: ${msg.optString("action")}")
                            false
                        }
                    }
                    "text" -> typeText(msg.optString("text", ""))
                    else -> {
                        Log.w(TAG, "unknown input type: ${msg.optString("type")}")
                        false
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "handleRemoteCommand failed: $msg", e)
                false
            }
        }
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
        // No event processing needed: gestures and global actions are
        // stateless. Window content is only pulled on demand for typing/scroll.
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

    private fun longPress(x: Float, y: Float, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x, y)
            // Zero-length stroke with a long duration = long press.
            lineTo(x + 1f, y + 1f)
        }
        return dispatch(path, 0L, durationMs.coerceIn(300L, 3000L))
    }

    /**
     * Prefer node scrolling (works inside lists/WebViews without guessing
     * distances); fall back to a swipe gesture when no scrollable node is hit.
     */
    private fun scroll(x: Float, y: Float, down: Boolean): Boolean {
        val root = rootInActiveWindow
        if (root != null) {
            try {
                val target = findScrollable(root, x, y) ?: root
                val action = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                if (target.performAction(action)) {
                    if (target != root) target.recycle()
                    return true
                }
                if (target != root) target.recycle()
            } catch (e: Exception) {
                Log.w(TAG, "node scroll failed, falling back to swipe", e)
            } finally {
                try { root.recycle() } catch (_: Exception) {}
            }
        }
        // Fallback: vertical swipe covering ~40% of the screen.
        val (w, h) = displaySize()
        val cx = x.coerceIn(0f, w)
        val cy = y.coerceIn(0f, h)
        val span = h * 0.4f
        return if (down) swipe(cx, cy + span / 2, cx, cy - span / 2, 350L)
        else swipe(cx, cy - span / 2, cx, cy + span / 2, 350L)
    }

    private fun findScrollable(node: AccessibilityNodeInfo, x: Float, y: Float): AccessibilityNodeInfo? {
        // Depth-first search for the deepest scrollable node containing (x,y).
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                val bounds = android.graphics.Rect()
                child.getBoundsInScreen(bounds)
                if (bounds.contains(x.toInt(), y.toInt())) {
                    val deep = findScrollable(child, x, y)
                    if (deep != null) {
                        child.recycle()
                        return deep
                    }
                    if (child.isScrollable) return child
                }
            } catch (_: Exception) {
            } finally {
                // Recycled by caller when returned; otherwise recycle here.
                // (We return early with the node alive; all other paths recycle.)
            }
            // Not the hit path — release.
            try { child.recycle() } catch (_: Exception) {}
        }
        return if (node.isScrollable) node else null
    }

    private fun dispatch(path: Path, startMs: Long, durationMs: Long): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false // unreachable: minSdk is 29
        val stroke = GestureDescription.StrokeDescription(path, startMs, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    /** Rotation-safe display size: WindowMetrics on API 30+, real metrics below. */
    private fun displaySize(): Pair<Float, Float> {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= 30) {
            val bounds = wm.currentWindowMetrics.bounds
            bounds.width().coerceAtLeast(1).toFloat() to
                bounds.height().coerceAtLeast(1).toFloat()
        } else {
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels.coerceAtLeast(1).toFloat() to
                dm.heightPixels.coerceAtLeast(1).toFloat()
        }
    }

    /**
     * Type into the currently focused input field. Requires
     * `android:canRetrieveWindowContent="true"` — without it
     * [rootInActiveWindow] is always null and typing silently fails.
     */
    private fun typeIntoFocused(text: String): Boolean {
        if (text.isEmpty()) return false
        val root = rootInActiveWindow ?: run {
            Log.w(TAG, "type failed: no active window (is canRetrieveWindowContent=true?)")
            return false
        }
        return try {
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            if (focused == null) {
                Log.w(TAG, "type failed: no focused input node")
                false
            } else try {
                val args = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
                    )
                }
                val ok = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                if (!ok) Log.w(TAG, "ACTION_SET_TEXT rejected by focused node")
                ok
            } finally {
                focused.recycle()
            }
        } catch (e: Exception) {
            Log.w(TAG, "type failed", e)
            false
        } finally {
            try { root.recycle() } catch (_: Exception) {}
        }
    }
}
