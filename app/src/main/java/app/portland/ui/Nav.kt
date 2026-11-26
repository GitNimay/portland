package app.portland.ui

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

sealed interface Screen {
    val key: String

    data object Home : Screen {
        override val key = "home"
    }

    // Equal screens share a key on purpose: re-opening one that is still animating out makes AnimatedContent reuse
    // that composition, so a screen (and its shared elements) is never on screen twice.
    data class Board(val id: String) : Screen {
        override val key get() = "board:$id"
    }

    /** A swipeable viewer over [ids] (the list it was opened from), starting at [start]. */
    data class Detail(val ids: List<String>, val start: Int) : Screen {
        override val key get() = "detail:${ids.hashCode()}:$start"
    }
}

// shortcut: the back stack is not saved across process death (reopens on Home); fine for a viewer app.
class Nav(private val saved: SaveableStateHolder) {
    val stack = mutableStateListOf<Screen>(Screen.Home)

    /** True when the top screen was pushed (forward), false when it was reached by going back. */
    var forward by mutableStateOf(true)
        private set

    fun push(s: Screen) {
        if (s == stack.last()) return // a double tap would otherwise need two backs
        forward = true
        stack += s
    }

    fun pop() {
        if (stack.size > 1) {
            forward = false
            saved.removeState(stack.removeAt(stack.lastIndex).key)
        }
    }
}

val LocalNav = staticCompositionLocalOf<Nav> { error("Nav not provided") }

@OptIn(ExperimentalSharedTransitionApi::class)
private val LocalShared = staticCompositionLocalOf<SharedTransitionScope?> { null }
private val LocalAnim = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/** Morphs a pin's media between the grid and the viewer. Apply before clip/size modifiers. */
@Composable
fun Modifier.sharedPin(id: String): Modifier = sharedPin(rememberSharedPin(id))

/** The shared-element state behind [sharedPin]; hold on to it to hand a gesture's velocity to the transition. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun rememberSharedPin(id: String): SharedTransitionScope.SharedContentState? {
    val shared = LocalShared.current ?: return null
    return with(shared) { rememberSharedContentState("pin:$id") }
}

/**
 * [sharedPin] with a state from [rememberSharedPin]. Shared bounds come from layout, so anything that should
 * move the shared media (e.g. a drag offset) must be placement (Modifier.offset) applied *before* this.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedPin(state: SharedTransitionScope.SharedContentState?): Modifier {
    val shared = LocalShared.current ?: return this
    val anim = LocalAnim.current ?: return this
    if (state == null) return this
    return with(shared) {
        this@sharedPin.sharedElement(state, anim, boundsTransform = { _, _ -> Motion.snappy() })
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AppRoot() {
    val p = LocalPalette.current
    val saved = rememberSaveableStateHolder()
    val nav = remember { Nav(saved) }
    val scope = rememberCoroutineScope()
    val back = remember { BackPreview() }
    // Predictive back: the screen shrinks and follows the finger; letting go goes back, pulling away springs it home.
    // Without a gesture (3-button nav) the flow just completes, which is a plain back.
    PredictiveBackHandler(nav.stack.size > 1) { events ->
        val run = back.start(nav.stack.last())
        try {
            events.collect { back.update(it.progress, it.swipeEdge) }
            back.release()
            nav.pop()
            // The leaving screen keeps its shrunken pose while it fades; forget it once the transition is over.
            scope.launch {
                delay(700)
                back.clear(run)
            }
        } catch (e: CancellationException) {
            back.release()
            scope.launch { back.settle(run) }
            throw e
        }
    }
    // Behind the shrinking screen: a slightly deeper tone, so its rounded edge reads. Fades out once released.
    val behind = if (p.dark) Color(0xFF1C1C1E) else Color(0xFFE5E5EA)
    val behindAlpha by animateFloatAsState(if (back.dragging) 1f else 0f, tween(200), label = "behind")
    SharedTransitionLayout(
        Modifier.drawBehind {
            val a = BackEasing.transform(back.progress) * behindAlpha
            if (a > 0f) drawRect(behind, alpha = a)
        },
    ) {
        AnimatedContent(
            targetState = nav.stack.last(),
            transitionSpec = {
                if (nav.forward) {
                    (fadeIn(tween(220)) + scaleIn(Motion.smooth(), initialScale = 0.94f)) togetherWith
                        (fadeOut(tween(180)) + scaleOut(Motion.smooth(), targetScale = 1.02f))
                } else {
                    (fadeIn(tween(220)) + scaleIn(Motion.smooth(), initialScale = 1.02f)) togetherWith
                        (fadeOut(tween(180)) + scaleOut(Motion.smooth(), targetScale = 0.94f))
                }
            },
            label = "nav",
        ) { screen ->
            CompositionLocalProvider(LocalNav provides nav, LocalShared provides this@SharedTransitionLayout, LocalAnim provides this) {
                saved.SaveableStateProvider(screen.key) {
                    Box(Modifier.backPreview(back, screen)) {
                        when (screen) {
                            Screen.Home -> HomeScreen()
                            is Screen.Board -> BoardScreen(screen.id)
                            is Screen.Detail -> DetailScreen(screen.ids, screen.start)
                        }
                    }
                }
            }
        }
    }
}

// Material's predictive-back curve: most of the movement early in the gesture, then a long, gentle tail.
private val BackEasing = CubicBezierEasing(0.1f, 0.1f, 0f, 1f)

/** The in-flight back gesture: which screen is being swiped away, how far (0..1), and from which edge. */
@Stable
private class BackPreview {
    var screen by mutableStateOf<Screen?>(null)
        private set
    var progress by mutableFloatStateOf(0f)
        private set
    var edge by mutableIntStateOf(BackEventCompat.EDGE_LEFT)
        private set
    var dragging by mutableStateOf(false)
        private set
    private var run = 0

    fun start(s: Screen): Int {
        screen = s
        progress = 0f
        dragging = true
        return ++run
    }

    fun update(p: Float, e: Int) {
        progress = p
        edge = e
    }

    fun release() {
        dragging = false
    }

    /** Cancelled: spring back to full size. A newer gesture ([run] moved on) takes over at once. */
    suspend fun settle(r: Int) {
        animate(progress, 0f, animationSpec = Motion.snappy()) { v, _ -> if (r == run) progress = v }
        if (r == run) screen = null
    }

    fun clear(r: Int) {
        if (r == run) {
            screen = null
            progress = 0f
        }
    }
}

/**
 * Material 3's "shifted surface" back preview for [screen] while it is the one being swiped: down to 90%,
 * nudged toward the side the swipe came from, corners rounding in. Read at draw time, so it never recomposes.
 */
private fun Modifier.backPreview(back: BackPreview, screen: Screen): Modifier = graphicsLayer {
    if (back.screen != screen) return@graphicsLayer
    val e = BackEasing.transform(back.progress.coerceIn(0f, 1f))
    if (e <= 0f) return@graphicsLayer
    val s = 1f - 0.1f * e
    scaleX = s
    scaleY = s
    val toward = if (back.edge == BackEventCompat.EDGE_RIGHT) -1f else 1f
    translationX = toward * (size.width / 20f - 8.dp.toPx()).coerceAtLeast(0f) * e
    shape = RoundedCornerShape(32.dp.toPx() * e)
    clip = true
}
