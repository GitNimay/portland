package app.portland.ui

import android.os.Build
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Content appearing out of glass, as in iOS 26: it comes into focus (a blur that clears) while it fades and
 * scales in, instead of only fading. Use inside AnimatedVisibility/AnimatedContent content, alongside a
 * fade/scale enter. Before Android 12 there is no blur; the fade and scale still play.
 */
@Composable
fun AnimatedVisibilityScope.materialize(radius: Dp = 12.dp): Modifier {
    val blur by transition.animateFloat(transitionSpec = { Motion.smooth() }, label = "materialize") {
        if (it == EnterExitState.Visible) 0f else 1f
    }
    return Modifier.graphicsLayer {
        val px = blur * radius.toPx()
        renderEffect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && px > 0.5f) BlurEffect(px, px) else null
    }
}

/**
 * An icon-only control inside a glass panel: no glass of its own (never glass on glass), just a circle that
 * fills faintly and sinks under the finger. [label] is what screen readers say. [content] replaces the icon
 * (a spinner, a check) when given.
 */
@Composable
fun GlassIcon(
    @DrawableRes icon: Int,
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    tint: Color = LocalPalette.current.ink,
    fill: Color = Color.Transparent,
    enabled: Boolean = true,
    content: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val p = LocalPalette.current
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.86f else 1f, Motion.press(), label = "press")
    val wash by animateFloatAsState(if (pressed) 1f else 0f, Motion.press(), label = "wash")
    Box(
        modifier
            .size(size)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(fill)
            .drawBehind {
                val w = wash
                if (w > 0f) drawRect(p.fill.copy(alpha = p.fill.alpha * w))
            }
            .clickable(interactionSource = press, indication = null, enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (content != null) content() else Icon(painterResource(icon), contentDescription = label, tint = tint, modifier = Modifier.size(IconSize.medium))
    }
}
