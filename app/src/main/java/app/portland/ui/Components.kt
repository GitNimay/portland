package app.portland.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.portland.R
import app.portland.data.Pin
import app.portland.store
import coil3.compose.AsyncImage
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

val CardShape = Corners.card
val GridGutter = 16.dp

/**
 * Icon button. Plain (48dp target, no surface) inside content; with a [backdrop] it floats as a 44dp
 * Liquid Glass circle that swells slightly under the finger, like iOS 26 toolbar buttons.
 */
@Composable
fun IconBtn(
    @DrawableRes icon: Int,
    label: String,
    modifier: Modifier = Modifier,
    tint: Color = LocalPalette.current.ink,
    backdrop: Backdrop? = null,
    onClick: () -> Unit,
) {
    if (backdrop == null) {
        IconButton(onClick = onClick, modifier = modifier) {
            Icon(painterResource(icon), contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
        }
        return
    }
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 1.1f else 1f, Motion.press(), label = "press")
    Box(
        modifier
            .glass(backdrop, Corners.pill, scale = { scale })
            .size(44.dp)
            .clickable(interactionSource = press, indication = null, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/** The one primary action: an ink-tinted glass circle with a plus, floating bottom-right. */
@Composable
fun AddButton(label: String, modifier: Modifier = Modifier, backdrop: Backdrop? = null, onClick: () -> Unit) {
    val p = LocalPalette.current
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 1.1f else 1f, Motion.press(), label = "press")
    Box(
        modifier
            .navigationBarsPadding()
            .padding(20.dp)
            .glass(backdrop, Corners.pill, scale = { scale })
            .size(56.dp)
            .clickable(interactionSource = press, indication = null, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.ic_plus), contentDescription = label, tint = p.ink, modifier = Modifier.size(24.dp))
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Column(modifier.padding(horizontal = 4.dp, vertical = 48.dp)) {
        Text(title, style = Type.body.copy(fontWeight = FontWeight.SemiBold), color = p.ink)
        Spacer(Modifier.height(6.dp))
        Text(body, style = Type.caption, color = p.ink2)
    }
}

/**
 * How far a grid has scrolled its first item (the header slot) out of view, in px; +inf once that item is gone.
 * Read it inside offset {} / graphicsLayer {} lambdas so scrolling never recomposes the header.
 */
fun LazyStaggeredGridState.headerScroll(): Float =
    if (firstVisibleItemIndex == 0) firstVisibleItemScrollOffset.toFloat() else Float.POSITIVE_INFINITY

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

/**
 * Masonry of pins. [header] scrolls with the content as a full-width row.
 * [onOpen] receives the index into [pins].
 */
@Composable
fun PinGrid(
    pins: List<Pin>,
    onOpen: (Int) -> Unit,
    modifier: Modifier = Modifier,
    state: LazyStaggeredGridState = rememberLazyStaggeredGridState(),
    bottomPadding: Dp = 120.dp,
    header: @Composable () -> Unit = {},
) {
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(2),
        state = state,
        modifier = modifier,
        contentPadding = PaddingValues(start = GridGutter, end = GridGutter, bottom = bottomPadding),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalItemSpacing = 16.dp,
    ) {
        item(key = "header", span = StaggeredGridItemSpan.FullLine, contentType = "header") { header() }
        items(pins, key = { it.id }, contentType = { "pin" }) { pin ->
            PinCard(pin, onClick = { onOpen(pins.indexOf(pin)) }, modifier = Modifier.animateItem())
        }
    }
}

@Composable
fun PinCard(pin: Pin, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val store = LocalContext.current.store
    val model = remember(pin.id) { store.thumb(pin) }
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, Motion.press(), label = "press")
    val badgeBackdrop = if (pin.video) rememberLayerBackdrop() else null
    // Very tall images are capped like a feed would; the viewer shows them whole.
    val ratio = (pin.w.toFloat() / pin.h.coerceAtLeast(1)).coerceIn(0.45f, 2f)

    Column(modifier.graphicsLayer { scaleX = scale; scaleY = scale }) {
        Box(
            Modifier
                .sharedPin(pin.id)
                .fillMaxWidth()
                .aspectRatio(ratio)
                .clip(CardShape)
                .background(p.fill)
                .clickable(interactionSource = press, indication = null, onClickLabel = "Open", onClick = onClick),
        ) {
            AsyncImage(
                model = model,
                contentDescription = pin.note.ifBlank { null },
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize().then(if (badgeBackdrop != null) Modifier.layerBackdrop(badgeBackdrop) else Modifier),
            )
            if (pin.video) {
                Row(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .glass(badgeBackdrop, Corners.pill, tint = Color.Black.copy(alpha = 0.22f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(painterResource(R.drawable.ic_play), contentDescription = "Video", tint = Color.White, modifier = Modifier.size(10.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(formatDuration(pin.durationMs), style = Type.caption, color = Color.White)
                }
            }
        }
        // Your note wins; otherwise the AI description, which arrives a second or two after adding.
        val label = pin.note.ifBlank { pin.caption.orEmpty() }
        RevealText(label) {
            Text(
                it,
                style = Type.caption,
                color = p.ink2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp),
            )
        }
    }
}

/**
 * Shows [text] when it is non-blank. Text that is already there on first composition appears
 * instantly (scrolling stays calm); text that arrives later fades and unfolds in.
 */
@Composable
fun RevealText(text: String, content: @Composable (String) -> Unit) {
    val visible = remember { MutableTransitionState(text.isNotBlank()) }
    visible.targetState = text.isNotBlank()
    AnimatedVisibility(
        visibleState = visible,
        enter = expandVertically(Motion.smooth(), expandFrom = Alignment.Top) + fadeIn(tween(320, delayMillis = 80)),
        exit = shrinkVertically(Motion.smooth()) + fadeOut(tween(160)),
    ) { content(text) }
}
