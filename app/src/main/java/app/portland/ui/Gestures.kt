package app.portland.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import app.portland.data.GridColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/** What a vertical swipe on a pin's media is doing. */
enum class SwipeMode { None, Dismiss, Send }

/**
 * The viewer's vertical swipes on a pin's media: pull down to close back to where it was opened from,
 * push up to lift the send shelf. One per viewer (and one per zoom dialog); only the touched pin ([id]) moves.
 *
 * Everything the drag changes is snapshot state meant to be read at draw time (graphicsLayer / offset
 * lambdas): a swipe invalidates a few layers per frame and never recomposes. [mode] and [shelfOpen]
 * change once per gesture and may be read in composition.
 */
@Stable
class MediaSwipe internal constructor(
    private val scope: CoroutineScope,
    private val haptics: Haptics,
    /** Pull down this far (px) and letting go closes. */
    private val dismissPx: Float,
    /** A flick faster than this (px/s) commits regardless of distance. */
    private val flingPx: Float,
    /** Shelf travel used until the shelf has measured itself. */
    private val shelfFallbackPx: Float,
    /** How far past its resting place the shelf may be stretched, asymptotically. */
    private val stretchPx: Float,
    /** When set, a committed pull carries the media this far down (px) before [onDismiss]; else it calls at once. */
    private val flyOutPx: Float,
    private val onDismiss: () -> Unit,
) {
    /** The pin whose media is moving, or whose shelf is up; null at rest. */
    var id by mutableStateOf<String?>(null)
        private set
    var mode by mutableStateOf(SwipeMode.None)
        private set

    /** The shelf is (heading) open: shows the scrim that closes it. */
    var shelfOpen by mutableStateOf(false)
        private set

    /** Let go past the threshold: the screen is closing, so the media stays where the finger left it. */
    var dismissing by mutableStateOf(false)
        private set

    /** Finger travel while dismissing, px (y > 0 is down). */
    private var pull by mutableStateOf(Offset.Zero)

    /** How far the media has been pulled down, px. Read at draw time. */
    val pullY: Float get() = if (mode == SwipeMode.Dismiss) pull.y.coerceAtLeast(0f) else 0f

    /**
     * Called with the release velocity just before a committed pull closes the screen, for the pin that was
     * pulled; the viewer hands it to the shared-element transition so the media keeps its momentum.
     */
    internal val handoffs = mutableMapOf<String, (Velocity) -> Unit>()

    /** Finger travel while sending, px upward; [shelfPx] = open. */
    private var lift by mutableFloatStateOf(0f)

    /** The shelf's height including its margins; it reports this from onSizeChanged. */
    var shelfPx by mutableFloatStateOf(0f)

    private var settle: Job? = null
    private var armed = false

    private val travel get() = if (shelfPx > 0f) shelfPx else shelfFallbackPx

    // ---- Read these at draw time ----

    /** 0..1: how close a pull-down is to closing. Fades the screen's chrome. */
    val dismissProgress: Float get() = if (mode == SwipeMode.Dismiss) (pull.y / dismissPx).coerceIn(0f, 1f) else 0f

    /** 0..1: how far the shelf is up. */
    val sendProgress: Float get() = if (mode == SwipeMode.Send) (lift / travel).coerceIn(0f, 1f) else 0f

    /** How far the shelf has risen, px; rubber-banded past its resting place. */
    val shelfRise: Float get() = if (mode == SwipeMode.Send) rubberPast(lift, travel, stretchPx) else 0f

    /** The translation of [pinId]'s media. */
    fun mediaOffset(pinId: String): Offset {
        if (id != pinId) return Offset.Zero
        return when (mode) {
            // Follows the finger 1:1; above its start it resists, so it can't be flung off the top.
            SwipeMode.Dismiss -> Offset(pull.x, if (pull.y >= 0f) pull.y else -rubber(-pull.y, stretchPx / 2f))
            // Rises a little with the shelf, so the gesture reads as pushing the pin up.
            SwipeMode.Send -> Offset(0f, -shelfRise * MediaRise)
            SwipeMode.None -> Offset.Zero
        }
    }

    /** The scale of [pinId]'s media: it settles back a touch as the shelf comes up. */
    fun mediaScale(pinId: String): Float = if (id == pinId) 1f - 0.05f * sendProgress else 1f

    // ---- Gesture plumbing ----

    /** The mode to continue at once (no touch slop) when [pinId] is grabbed while it is still moving. */
    internal fun resumable(pinId: String): SwipeMode =
        if (id == pinId && settle?.isActive == true && !dismissing) mode else SwipeMode.None

    internal fun begin(pinId: String, newMode: SwipeMode) {
        settle?.cancel()
        if (id != pinId || mode != newMode) {
            // Another pin (or the other direction) was still settling: put it to rest.
            pull = Offset.Zero
            lift = 0f
            if (newMode != SwipeMode.Send) shelfOpen = false
        }
        id = pinId
        mode = newMode
        armed = committing(Velocity.Zero)
    }

    internal fun dragBy(delta: Offset) {
        if (!delta.x.isFinite() || !delta.y.isFinite()) return
        when (mode) {
            SwipeMode.Dismiss -> pull += delta
            SwipeMode.Send -> lift = (lift - delta.y).coerceAtLeast(0f)
            SwipeMode.None -> return
        }
        // One tick as the gesture becomes "let go to commit", and a softer one if it is taken back, like the
        // system back gesture. Both are rate-limited, so hovering on the line cannot buzz.
        val now = committing(Velocity.Zero)
        if (now && !armed) haptics.play(Fx.Threshold)
        if (!now && armed) haptics.play(Fx.ThresholdBack)
        armed = now
    }

    /** [lifted]: the finger really let go. Otherwise the gesture was taken away (or its node left) and must never commit a close. */
    internal fun release(rawVelocity: Velocity, lifted: Boolean = true) {
        // A NaN/Infinity velocity would poison the springs and crash Offset.round() in a placement lambda.
        val velocity = if (lifted && rawVelocity.x.isFinite() && rawVelocity.y.isFinite()) rawVelocity else Velocity.Zero
        // Thrown by a flick before the distance tick ever played: a whoosh instead, so every commit is felt once.
        if (lifted && !armed && mode != SwipeMode.None && committing(velocity)) haptics.play(Fx.Whoosh)
        when (mode) {
            SwipeMode.Dismiss -> if (lifted && committing(velocity)) {
                dismissing = true
                if (flyOutPx > 0f) {
                    flyOut(velocity)
                } else {
                    id?.let { handoffs[it] }?.invoke(velocity)
                    onDismiss()
                }
            } else {
                settleTo(dismissed = false, velocity)
            }
            SwipeMode.Send -> settleShelf(committing(velocity), velocity.y)
            SwipeMode.None -> {}
        }
    }

    /** Would letting go now (at [velocity]) close the screen or open the shelf? */
    private fun committing(velocity: Velocity): Boolean = when (mode) {
        SwipeMode.Dismiss -> when {
            velocity.y > flingPx -> pull.y > 0f
            velocity.y < -flingPx -> false // flicked back up: the user changed their mind
            else -> pull.y > dismissPx
        }
        SwipeMode.Send -> when {
            velocity.y < -flingPx -> true
            velocity.y > flingPx -> false
            else -> lift > travel / 2f
        }
        SwipeMode.None -> false
    }

    /** Opens the shelf without a gesture (the Share button). */
    fun openShelf(pinId: String) {
        if (dismissing) return
        if (id != pinId || mode != SwipeMode.Send) begin(pinId, SwipeMode.Send)
        settleShelf(open = true, velocityY = 0f)
    }

    fun closeShelf() {
        if (mode == SwipeMode.Send) settleShelf(open = false, velocityY = 0f)
    }

    private fun settleShelf(open: Boolean, velocityY: Float) {
        shelfOpen = open
        settle?.cancel()
        settle = scope.launch {
            // The shelf moves with -y; hand the finger's velocity over so the release is seamless.
            animate(lift, if (open) travel else 0f, -velocityY, if (open) ShelfSpring else ShelfDownSpring) { v, _ -> lift = v }
            if (!open) rest()
        }
    }

    private fun settleTo(dismissed: Boolean, velocity: Velocity) {
        settle?.cancel()
        settle = scope.launch {
            animate(
                Offset.VectorConverter,
                pull,
                Offset.Zero,
                Offset(velocity.x, velocity.y),
                ReturnSpring,
            ) { v, _ -> pull = v }
            if (!dismissed) rest()
        }
    }

    /** Carries the media off the bottom with the finger's momentum, then closes. */
    private fun flyOut(velocity: Velocity) {
        settle?.cancel()
        settle = scope.launch {
            val target = Offset(pull.x + velocity.x * 0.08f, pull.y + flyOutPx)
            val start = Offset(velocity.x, velocity.y.coerceAtLeast(0f))
            animate(Offset.VectorConverter, pull, target, start, FlyOutSpring) { v, _ -> pull = v }
            onDismiss()
        }
    }

    private fun rest() {
        id = null
        mode = SwipeMode.None
        pull = Offset.Zero
        lift = 0f
    }

    /**
     * Back to rest at once, whatever was under way: a viewer brought back while it is still animating out (see
     * [ReviveEffect]) must not keep the pull that closed it, nor the [dismissing] latch that ignores every touch.
     */
    fun reset() {
        settle?.cancel() // first: a fly-out still running would end in onDismiss()
        settle = null
        dismissing = false
        shelfOpen = false
        armed = false
        rest()
    }
}

/**
 * A [MediaSwipe] for one viewer. With [flyOut] a committed pull carries the media off screen before
 * [onDismiss] (for a dialog, which has no transition of its own); without it [onDismiss] runs on release
 * and the navigation transition takes over from where the finger let go.
 */
@Composable
fun rememberMediaSwipe(flyOut: Boolean = false, onDismiss: () -> Unit): MediaSwipe {
    val scope = rememberCoroutineScope()
    val haptics = LocalHaptics.current
    val density = LocalDensity.current
    val height = LocalWindowInfo.current.containerSize.height
    val dismiss = rememberUpdatedState(onDismiss)
    return remember(scope, haptics, density, flyOut) {
        with(density) {
            MediaSwipe(
                scope = scope,
                haptics = haptics,
                dismissPx = 112.dp.toPx(),
                flingPx = 1000.dp.toPx(),
                shelfFallbackPx = 280.dp.toPx(),
                stretchPx = 72.dp.toPx(),
                flyOutPx = if (flyOut) height.toFloat() else 0f,
                onDismiss = { dismiss.value() },
            )
        }
    }
}

/**
 * Vertical swipes on [pinId]'s media, feeding [swipe]: down to dismiss, up to lift the send shelf.
 *
 * A drag is only claimed once it is clearly vertical, so horizontal drags still reach the pager and taps
 * still reach the media's own clickables (claiming consumes the move, which cancels their tap). A downward
 * drag is left to the page's own scroll when [canDismiss] says no (the page is scrolled down); a pinch is
 * never claimed. [enabled] is checked per gesture (e.g. off while zoomed in).
 */
fun Modifier.mediaSwipe(
    swipe: MediaSwipe,
    pinId: String,
    canDismiss: () -> Boolean = { true },
    enabled: () -> Boolean = { true },
): Modifier = pointerInput(swipe, pinId) {
    val tracker = VelocityTracker()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (!enabled() || swipe.dismissing) return@awaitEachGesture
        tracker.resetTracking()
        tracker.addPointerInputChange(down)

        // Caught mid-settle: carry on from where it is, no slop, so it feels like one continuous object.
        val resumed = swipe.resumable(pinId)
        if (resumed != SwipeMode.None) {
            swipe.begin(pinId, resumed)
        } else {
            val slop = viewConfiguration.touchSlop
            var total = Offset.Zero
            var mode = SwipeMode.None
            while (mode == SwipeMode.None) {
                val event = awaitPointerEvent()
                if (event.changes.count { it.pressed } > 1) return@awaitEachGesture
                val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                if (!change.pressed || change.isConsumed) return@awaitEachGesture
                tracker.addPointerInputChange(change)
                total += change.positionChange()
                if (abs(total.x) > slop && abs(total.x) >= abs(total.y)) return@awaitEachGesture // a page swipe
                if (abs(total.y) > slop) {
                    mode = when {
                        total.y < 0f -> SwipeMode.Send
                        canDismiss() -> SwipeMode.Dismiss
                        else -> return@awaitEachGesture // scroll the page back up instead
                    }
                    change.consume()
                }
            }
            swipe.begin(pinId, mode)
            // Start from the slop boundary, not the touch point, so the media doesn't jump.
            swipe.dragBy(Offset(0f, total.y - sign(total.y) * slop))
        }
        trackDrag(down.id, swipe, tracker)
    }
}

/**
 * Vertical drags on the open send shelf and its scrim, feeding [swipe]: drag it down to close it, or
 * further up against its stretch. Claimed after touch slop, so the shelf's buttons and the scrim's tap
 * still work.
 */
fun Modifier.shelfSwipe(swipe: MediaSwipe): Modifier = pointerInput(swipe) {
    val tracker = VelocityTracker()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val pinId = swipe.id ?: return@awaitEachGesture
        if (swipe.mode != SwipeMode.Send) return@awaitEachGesture
        tracker.resetTracking()
        tracker.addPointerInputChange(down)
        val slop = viewConfiguration.touchSlop
        var total = Offset.Zero
        while (true) {
            val event = awaitPointerEvent()
            if (event.changes.count { it.pressed } > 1) return@awaitEachGesture
            val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if (!change.pressed || change.isConsumed) return@awaitEachGesture
            tracker.addPointerInputChange(change)
            total += change.positionChange()
            if (abs(total.y) > slop && abs(total.y) >= abs(total.x)) {
                change.consume()
                break
            }
        }
        swipe.begin(pinId, SwipeMode.Send)
        swipe.dragBy(Offset(0f, total.y - sign(total.y) * slop))
        trackDrag(down.id, swipe, tracker)
    }
}

/** Follows [pointer] until it lifts (release with its velocity) or the gesture is taken away (release at rest). */
private suspend fun AwaitPointerEventScope.trackDrag(pointer: PointerId, swipe: MediaSwipe, tracker: VelocityTracker) {
    var lifted = false
    try {
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == pointer } ?: break
            if (!change.pressed) {
                tracker.addPointerInputChange(change)
                lifted = true
                break
            }
            if (change.isConsumed) break
            tracker.addPointerInputChange(change)
            swipe.dragBy(change.positionChange())
            change.consume()
        }
    } finally {
        // Also runs if the node leaves mid-drag, so nothing is ever left hanging half-way.
        // Only a real lift may commit; a node that left mid-drag just settles back.
        swipe.release(if (lifted) tracker.calculateVelocity() else Velocity.Zero, lifted)
    }
}

/**
 * A pinch on the viewer's media, Photos-style: the media grows (or shrinks) under the fingers and follows them,
 * then [reset] springs it back. Read [scale] and [offset] at draw time only (graphicsLayer), so a pinch never
 * recomposes. [offset] is the translation of the media's centre, in px.
 */
@Stable
class PeekZoom internal constructor(private val scope: CoroutineScope) {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set

    /** Whether a pinch is under way; one-finger swipes wait for it to end. */
    var pinching by mutableStateOf(false)
        internal set

    /**
     * Called when a pinch ends with its final [scale] and the fingers' centroid relative to the media's centre.
     * Null, or one that does nothing else, springs back. Set by whoever shows the media; read only on release.
     */
    var onRelease: ((scale: Float, centroid: Offset) -> Unit)? = null

    private var settle: Job? = null

    internal fun begin() {
        settle?.cancel()
        pinching = true
    }

    /**
     * Applies one frame of a pinch: [zoom] times bigger around [centroid] (relative to the media's centre), moved
     * by [pan]. The point under the fingers stays under the fingers.
     */
    internal fun transform(zoom: Float, pan: Offset, centroid: Offset) {
        val next = (scale * zoom).coerceIn(MinPeek, MaxPeek)
        val z = next / scale
        offset = centroid + pan - (centroid - offset) * z
        scale = next
    }

    /** Springs back to rest (or jumps there, unanimated). */
    fun reset(animated: Boolean = true) {
        settle?.cancel()
        if (!animated) {
            scale = 1f
            offset = Offset.Zero
            return
        }
        val fromScale = scale
        val fromOffset = offset
        settle = scope.launch {
            animate(0f, 1f, animationSpec = PeekSpring) { t, _ ->
                scale = lerp(fromScale, 1f, t)
                offset = lerp(fromOffset, Offset.Zero, t)
            }
        }
    }

    internal companion object {
        const val MinPeek = 0.8f
        const val MaxPeek = 5f
    }
}

@Composable
fun rememberPeekZoom(key: Any?): PeekZoom {
    val scope = rememberCoroutineScope()
    return remember(key) { PeekZoom(scope) }
}

/**
 * Two-finger pinches feeding [zoom]. One-finger touches pass through untouched (pager swipes, pull to dismiss,
 * taps). Once a pinch starts, its moves are consumed so the pager and the vertical swipes leave it alone, until
 * every finger lifts; then [PeekZoom.onRelease] gets the final scale and the fingers' last centroid relative to
 * the node's centre, or without one the media springs back. [enabled] is checked per gesture.
 */
fun Modifier.peekZoom(zoom: PeekZoom, enabled: () -> Boolean = { true }): Modifier = pointerInput(zoom) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        if (!enabled()) return@awaitEachGesture
        var pinching = false
        var total = 1f
        var last = Offset.Zero
        try {
            do {
                val event = awaitPointerEvent()
                val fingers = event.changes.count { it.pressed }
                if (fingers >= 2) {
                    val factor = event.calculateZoom()
                    val pan = event.calculatePan()
                    val centroid = event.calculateCentroid(useCurrent = true)
                    if (!pinching) {
                        total *= factor
                        val span = event.calculateCentroidSize(useCurrent = true)
                        // Past touch slop in finger travel, not a ratio, so small and large spreads feel the same.
                        if (abs(1f - total) * span > viewConfiguration.touchSlop) {
                            pinching = true
                            zoom.begin()
                        }
                    }
                    if (pinching && centroid.isSpecified) {
                        last = centroid - Offset(size.width / 2f, size.height / 2f)
                        zoom.transform(factor, pan, last)
                    }
                }
                // Once pinching, the gesture is ours until the last finger lifts (one finger left is not a swipe).
                if (pinching) event.changes.forEach { if (it.positionChanged()) it.consume() }
            } while (event.changes.any { it.pressed })
        } finally {
            if (pinching) {
                zoom.pinching = false
                zoom.onRelease?.invoke(zoom.scale, last) ?: zoom.reset()
            }
        }
    }
}

/**
 * Pinching a pin grid changes how many columns it shows, one column per [ColumnStep] of spread: fingers apart
 * for fewer, bigger pins, together for more. One-finger touches pass through untouched (scrolling, taps,
 * long-press to select). The pinch is read before the grid sees it, and once it passes touch slop its moves are
 * consumed until every finger lifts, so the grid does not scroll under it. [columns] is read when a step is due;
 * [onColumns] gets each new count.
 */
fun Modifier.pinchColumns(columns: () -> Int, onColumns: (Int) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var pinching = false
        var total = 1f
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } >= 2) {
                total *= event.calculateZoom()
                if (!pinching) {
                    val span = event.calculateCentroidSize(useCurrent = true)
                    if (abs(1f - total) * span > viewConfiguration.touchSlop) pinching = true
                }
                if (pinching) {
                    val current = columns()
                    val next = columnsAfterPinch(current, total)
                    if (next != current) {
                        onColumns(next)
                        total = 1f // the next column needs a fresh step of spread
                    } else {
                        // At the fewest or most columns, pinching further piles up nothing, so turning back answers at once.
                        total = total.coerceIn(1f / ColumnStep, ColumnStep)
                    }
                }
            }
            if (pinching) event.changes.forEach { if (it.positionChanged()) it.consume() }
        } while (event.changes.any { it.pressed })
    }
}

/** How far a pinch spreads (or, inverted, squeezes) before the grid gains or loses a column. */
internal const val ColumnStep = 1.3f

/** The column count after a pinch that has scaled by [zoom] since the last step: spreading shows fewer, bigger pins. */
internal fun columnsAfterPinch(columns: Int, zoom: Float): Int = when {
    !zoom.isFinite() || zoom <= 0f -> columns
    zoom >= ColumnStep -> GridColumns.clamp(columns - 1)
    zoom <= 1f / ColumnStep -> GridColumns.clamp(columns + 1)
    else -> columns
}

/** [Offset.round] throws on NaN/Infinity; a bad frame of spring or velocity math must not crash the app. */
internal fun Offset.safeRound(): IntOffset = if (x.isFinite() && y.isFinite()) round() else IntOffset.Zero

/** iOS-style resistance: 1:1 at first, approaching [limit] however far [x] goes. */
fun rubber(x: Float, limit: Float): Float = if (x <= 0f) 0f else limit * x / (x + limit)

/** [x] unchanged up to [rest], rubber-banded beyond it. */
private fun rubberPast(x: Float, rest: Float, limit: Float): Float = if (x <= rest) x else rest + rubber(x - rest, limit)

/** How much of the shelf's rise the media follows. */
private const val MediaRise = 0.22f

// Critically damped returns (no wobble on a cancelled gesture), a little life when the shelf lands.
private val ReturnSpring = spring<Offset>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 420f)
private val ShelfSpring = spring<Float>(dampingRatio = 0.86f, stiffness = 420f)
private val ShelfDownSpring = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 420f) // sheets leave without a bounce
private val PeekSpring = spring<Float>(dampingRatio = 0.82f, stiffness = 380f) // a pinch let go settles back with a hint of give
private val FlyOutSpring = spring(Spring.DampingRatioNoBouncy, 300f, visibilityThreshold = Offset(40f, 40f))
