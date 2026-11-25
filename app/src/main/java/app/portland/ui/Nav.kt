package app.portland.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

sealed interface Screen {
    val key: String

    data object Home : Screen {
        override val key = "home"
    }

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
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedPin(id: String): Modifier {
    val shared = LocalShared.current ?: return this
    val anim = LocalAnim.current ?: return this
    return with(shared) {
        this@sharedPin.sharedElement(
            rememberSharedContentState("pin:$id"),
            anim,
            boundsTransform = { _, _ -> Motion.snappy() },
        )
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AppRoot() {
    val saved = rememberSaveableStateHolder()
    val nav = remember { Nav(saved) }
    BackHandler(nav.stack.size > 1) { nav.pop() }
    SharedTransitionLayout {
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
