package app.portland

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent

/**
 * Asks for the display's fastest refresh rate while the user is touching the app and while it settles afterwards
 * (a fling, a spring, a shared-element flight), then hands the choice back to the system.
 *
 * Phones with adaptive refresh already boost on touch, but several OEMs cap third-party apps at 60 Hz unless the
 * window asks for more. Asking all the time would hold a 120 Hz panel at full rate while the user just reads, so
 * the request is scoped: set on the first finger down, released [HOLD_MS] after the last finger lifts. That is two
 * window updates per gesture, never one per frame. No-op on 60 Hz-only displays.
 */
internal class FrameRateBoost(private val activity: Activity) {
    private val handler = Handler(Looper.getMainLooper())
    private val release = Runnable { request(0f) }
    private var boosted = false

    /** The fastest mode at the current resolution (switching resolution would be a visible mode change). */
    private val maxHz: Float by lazy {
        val display = runCatching { activity.display }.getOrNull() ?: return@lazy 0f
        val current = display.mode
        display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxOfOrNull { it.refreshRate } ?: 0f
    }

    /** Feed every touch event the activity dispatches. */
    fun onTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> hold()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> releaseAfter(HOLD_MS)
        }
    }

    /** Boost for a moment without a touch: the intro, a pin flying open from a notification. */
    fun pulse(ms: Long = HOLD_MS) {
        hold()
        releaseAfter(ms)
    }

    /** Leaving the app: give the panel back at once. */
    fun stop() {
        handler.removeCallbacks(release)
        if (boosted) request(0f)
    }

    private fun hold() {
        if (maxHz <= MIN_USEFUL_HZ) return
        handler.removeCallbacks(release)
        if (!boosted) request(maxHz)
    }

    private fun releaseAfter(ms: Long) {
        if (!boosted) return
        handler.removeCallbacks(release)
        handler.postDelayed(release, ms)
    }

    private fun request(hz: Float) {
        boosted = hz > 0f
        val window = activity.window
        window.attributes = window.attributes.apply { preferredRefreshRate = hz }
    }

    private companion object {
        /** Long enough for a fling or a spring to settle after the finger lifts. */
        const val HOLD_MS = 2500L
        /** Nothing to gain on a panel that tops out at 60 Hz. */
        const val MIN_USEFUL_HZ = 61f
    }
}
