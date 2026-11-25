package app.portland.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch

/** What a dialog's custom content can do: [submit] as if the main button were tapped (e.g. the keyboard's Done). */
class DialogScope internal constructor(val submit: () -> Unit)

/**
 * The app's one alert. A frosted squircle card that springs up from the centre (scale 0.9 → 1 on the Material 3
 * expressive spatial spring, with a slight settle) and sinks away quickly on the way out. Two equal pill buttons
 * sit at the bottom, Cancel on the left and the action on the right, filled red when [destructive].
 *
 * [onConfirm] and [onDismiss] run once the exit has played, so callers can drop the dialog from composition
 * inside them as usual. [content] (e.g. a text field) goes between the body and the buttons.
 */
@Composable
fun PortlandDialog(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    body: String? = null,
    destructive: Boolean = false,
    confirmEnabled: Boolean = true,
    dismissLabel: String = "Cancel",
    content: (@Composable DialogScope.() -> Unit)? = null,
) {
    val p = LocalPalette.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val shown = remember { Animatable(0f) }
    var leaving by remember { mutableStateOf(false) }
    val enabled by rememberUpdatedState(confirmEnabled)
    val confirmNow by rememberUpdatedState(onConfirm)
    val dismissNow by rememberUpdatedState(onDismiss)

    LaunchedEffect(Unit) { shown.animateTo(1f, Motion.dialog()) }

    // Plays the exit first; [then] usually removes this dialog, so it has to come last.
    val leave: (() -> Unit) -> Unit = { then ->
        if (!leaving) {
            leaving = true
            scope.launch {
                shown.animateTo(0f, tween(140, easing = FastOutLinearInEasing))
                then()
            }
        }
    }
    val submit: () -> Unit = {
        if (enabled && !leaving) {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            leave { confirmNow() }
        }
    }
    val cancel: () -> Unit = { leave { dismissNow() } }
    val dialogScope = remember { DialogScope { submit() } }

    Dialog(onDismissRequest = cancel) {
        FrostBehind()
        Column(
            Modifier
                .widthIn(min = 280.dp, max = 360.dp)
                .semantics { paneTitle = title }
                .graphicsLayer {
                    val t = shown.value
                    alpha = t.coerceIn(0f, 1f)
                    // In from 90% with a settle; out to 96% only, so leaving feels quick rather than collapsing.
                    val s = lerp(if (leaving) 0.96f else 0.9f, 1f, t)
                    scaleX = s
                    scaleY = s
                }
                .clip(Corners.sheet)
                .background(frostedSheet())
                .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 20.dp),
        ) {
            Text(title, style = Type.body.copy(fontWeight = FontWeight.SemiBold), color = p.ink)
            if (body != null) {
                Spacer(Modifier.height(6.dp))
                Text(body, style = Type.caption, color = p.ink2)
            }
            if (content != null) {
                Spacer(Modifier.height(16.dp))
                dialogScope.content()
            }
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DialogButton(
                    label = dismissLabel,
                    container = p.fill,
                    ink = p.ink,
                    strong = false,
                    enabled = true,
                    modifier = Modifier.weight(1f),
                    onClick = cancel,
                )
                DialogButton(
                    label = confirmLabel,
                    container = if (destructive) p.danger else p.ink,
                    ink = if (destructive) Color.White else p.canvas,
                    strong = true,
                    enabled = confirmEnabled,
                    modifier = Modifier.weight(1f),
                    onClick = submit,
                )
            }
        }
    }
}

@Composable
private fun DialogButton(
    label: String,
    container: Color,
    ink: Color,
    strong: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, Motion.press(), label = "press")
    val dim by animateFloatAsState(if (enabled) 1f else 0.38f, Motion.smooth(), label = "enabled")
    Box(
        modifier
            .height(48.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = dim
            }
            .clip(Corners.pill)
            .background(container)
            .clickable(interactionSource = press, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = Type.body.copy(fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Medium),
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
