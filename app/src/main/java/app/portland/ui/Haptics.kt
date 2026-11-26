package app.portland.ui

import android.Manifest
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibrationEffect.Composition
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.HapticFeedbackConstants as H
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView

/**
 * Portland's haptic vocabulary. Pick the meaning, not a buzz: every effect is defined once here, so the whole
 * app feels consistent and restrained (Apple's "don't overuse", Android's "clear over buzzy").
 *
 * Most effects are the system's own touch-feedback constants: no permission, they follow the device's tuning and
 * its touch-feedback setting. Three signature effects ([Thud], [Whoosh], [Heartbeat]) are short
 * [VibrationEffect.Composition]s where the hardware supports the primitives; elsewhere they fall back to the
 * nearest constant. Frequent effects are soft (0.5), events firmer (0.7–0.8), and only the two "weight" effects
 * peak near 1. Ticks are rate-limited and never step on a running event, so fast scrubbing cannot buzz.
 */
enum class Fx(internal val minGapMs: Long, internal val event: Boolean) {
    /** A discrete step: a tab, crossing between sections, a ±10s skip. */
    Tick(40, false),
    /** Dense stepping, as frames pass under the finger. Silent where the device has no light enough tick. */
    Detent(70, false),
    ToggleOn(0, true),
    ToggleOff(0, true),
    /** A long press recognised: something has been picked up. */
    Grab(0, true),
    /** A drag has gone far enough that letting go will act. */
    Threshold(120, false),
    /** …and has come back, so letting go will not. */
    ThresholdBack(120, false),
    /** It worked: saved, copied, added. Rises soft to firm. */
    Confirm(0, true),
    /** It didn't: two equal firm pulses. */
    Reject(0, true),
    /** Something was deleted: weight, without the "success" lift. */
    Thud(0, true),
    /** A pin thrown away by a flick: a quick rise and fall. */
    Whoosh(0, true),
    /** The one moment of delight: a pin you saved long ago, brought back by a notification. */
    Heartbeat(0, true),
}

private class Prim(val id: Int, val scale: Float, val delayMs: Int = 0)

/** [legacy] is the View constant for Android 11–13, [modern] for 14+; -1 stays silent. */
private class Spec(val prims: List<Prim>, val legacy: Int, val modern: Int = legacy, val rich: Boolean = false, val busyMs: Long = 15)

private val Specs: Map<Fx, Spec> = mapOf(
    Fx.Tick to Spec(listOf(Prim(Composition.PRIMITIVE_TICK, 0.5f)), H.CLOCK_TICK, H.SEGMENT_TICK),
    Fx.Detent to Spec(listOf(Prim(Composition.PRIMITIVE_TICK, 0.5f)), -1, H.SEGMENT_FREQUENT_TICK),
    Fx.ToggleOn to Spec(listOf(Prim(Composition.PRIMITIVE_CLICK, 0.7f)), H.CONTEXT_CLICK, H.TOGGLE_ON),
    Fx.ToggleOff to Spec(listOf(Prim(Composition.PRIMITIVE_CLICK, 0.5f)), H.CLOCK_TICK, H.TOGGLE_OFF),
    Fx.Grab to Spec(listOf(Prim(Composition.PRIMITIVE_CLICK, 0.8f)), H.LONG_PRESS, busyMs = 60),
    Fx.Threshold to Spec(listOf(Prim(Composition.PRIMITIVE_TICK, 0.7f)), H.CONTEXT_CLICK, H.GESTURE_THRESHOLD_ACTIVATE),
    Fx.ThresholdBack to Spec(listOf(Prim(Composition.PRIMITIVE_TICK, 0.5f)), -1, H.GESTURE_THRESHOLD_DEACTIVATE),
    Fx.Confirm to Spec(listOf(Prim(Composition.PRIMITIVE_CLICK, 0.8f)), H.CONFIRM, busyMs = 80),
    Fx.Reject to Spec(listOf(Prim(Composition.PRIMITIVE_CLICK, 0.8f)), H.REJECT, busyMs = 110),
    Fx.Thud to Spec(
        listOf(Prim(PRIMITIVE_THUD, 0.9f)),
        H.LONG_PRESS, rich = true, busyMs = 60,
    ),
    Fx.Whoosh to Spec(
        listOf(Prim(Composition.PRIMITIVE_QUICK_RISE, 0.4f), Prim(Composition.PRIMITIVE_QUICK_FALL, 0.3f)),
        H.GESTURE_END, rich = true, busyMs = 280,
    ),
    Fx.Heartbeat to Spec(
        listOf(Prim(PRIMITIVE_THUD, 0.45f), Prim(PRIMITIVE_THUD, 0.8f, 100)),
        H.CONTEXT_CLICK, rich = true, busyMs = 220,
    ),
)

// Composition.PRIMITIVE_THUD is API 31; the value is fixed by the platform. Only read where SDK >= 31.
private const val PRIMITIVE_THUD = 2

/**
 * Plays [Fx] effects. One per activity, provided by [PortlandTheme] as [LocalHaptics]. [enabled] is the in-app
 * Haptics setting; the system touch-feedback setting is honoured as well. Call [play] from event callbacks on
 * the main thread, never from composition, layout or draw.
 */
@Stable
class Haptics internal constructor(private val view: View) {
    /** The in-app switch (Settings → Haptics). */
    @Volatile var enabled: Boolean = true

    private val context = view.context.applicationContext
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)

    // Built once (effects are immutable). Absent = play the View constant instead.
    private val rich: Map<Fx, VibrationEffect> = buildMap {
        val v = vibrator ?: return@buildMap
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !v.hasVibrator()) return@buildMap
        if (context.checkSelfPermission(Manifest.permission.VIBRATE) != PackageManager.PERMISSION_GRANTED) return@buildMap
        for ((fx, s) in Specs) {
            if (!s.rich) continue
            if (!v.areAllPrimitivesSupported(*s.prims.map { it.id }.toIntArray())) continue
            runCatching {
                VibrationEffect.startComposition().apply { s.prims.forEach { addPrimitive(it.id, it.scale, it.delayMs) } }.compose()
            }.getOrNull()?.let { put(fx, it) }
        }
    }

    // View constants follow the system touch-feedback setting by themselves; the vibrator does not, so watch it.
    private val resolver = context.contentResolver
    @Volatile private var systemOn = readSystem()
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { systemOn = readSystem() }
    }

    init {
        if (rich.isNotEmpty()) {
            resolver.registerContentObserver(Settings.System.getUriFor(Settings.System.HAPTIC_FEEDBACK_ENABLED), false, observer)
        }
    }

    private fun readSystem() = Settings.System.getInt(resolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0

    internal fun close() {
        if (rich.isNotEmpty()) resolver.unregisterContentObserver(observer)
    }

    private val last = LongArray(Fx.entries.size)
    private var busyUntil = 0L

    /** Plays [fx]. [force] ignores the in-app switch: only for previewing the switch itself. */
    fun play(fx: Fx, force: Boolean = false) {
        if (!enabled && !force) return
        val now = SystemClock.uptimeMillis()
        if (now - last[fx.ordinal] < fx.minGapMs) return
        if (!fx.event && now < busyUntil) return // ticks never step on a running effect
        val spec = Specs.getValue(fx)
        last[fx.ordinal] = now
        busyUntil = now + spec.busyMs

        val effect = rich[fx]
        if (effect != null) {
            if (systemOn) vibrate(effect)
            return
        }
        val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) spec.modern else spec.legacy
        if (constant != -1) view.performHapticFeedback(constant)
    }

    private fun vibrate(effect: VibrationEffect) {
        val v = vibrator ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build())
            }
        } catch (_: SecurityException) {
            // VIBRATE missing: stay silent rather than crash.
        }
    }
}

val LocalHaptics = staticCompositionLocalOf<Haptics> { error("LocalHaptics is provided by PortlandTheme") }

/** The activity's [Haptics], kept for as long as its root view lives. */
@Composable
internal fun rememberHaptics(enabled: Boolean): Haptics {
    val view = LocalView.current
    val haptics = remember(view) { Haptics(view) }
    haptics.enabled = enabled
    DisposableEffect(haptics) { onDispose { haptics.close() } }
    return haptics
}
