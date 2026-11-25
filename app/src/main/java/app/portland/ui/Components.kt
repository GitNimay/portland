package app.portland.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.portland.R
import app.portland.data.Pin
import app.portland.store
import coil3.compose.AsyncImage

val CardShape = RoundedCornerShape(16.dp)
val GridGutter = 12.dp

/** 48dp touch target, 24dp glyph, tinted with the ink ladder. */
@Composable
fun IconBtn(@DrawableRes icon: Int, label: String, modifier: Modifier = Modifier, tint: Color = LocalPalette.current.ink, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(painterResource(icon), contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/** The one primary action: a black circle with a plus. */
@Composable
fun AddButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        modifier
            .navigationBarsPadding()
            .padding(20.dp)
            .size(56.dp)
            .clip(CircleShape)
            .background(p.ink)
            .clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.ic_plus), contentDescription = label, tint = p.canvas, modifier = Modifier.size(24.dp))
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Column(modifier.padding(horizontal = 4.dp, vertical = 40.dp)) {
        Text(title, style = Type.body.copy(fontWeight = FontWeight.Medium), color = p.ink)
        Spacer(Modifier.height(4.dp))
        Text(body, style = Type.caption, color = p.ink2)
    }
}

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
        verticalItemSpacing = 14.dp,
    ) {
        item(key = "header", span = StaggeredGridItemSpan.FullLine, contentType = "header") { header() }
        items(pins, key = { it.id }, contentType = { "pin" }) { pin ->
            PinCard(pin, onClick = { onOpen(pins.indexOf(pin)) })
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
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, label = "press")
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
                modifier = Modifier.matchParentSize(),
            )
            if (pin.video) {
                Row(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.44f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(painterResource(R.drawable.ic_play), contentDescription = "Video", tint = Color.White, modifier = Modifier.size(10.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(formatDuration(pin.durationMs), style = Type.caption, color = Color.White)
                }
            }
        }
        if (pin.note.isNotBlank()) {
            Text(
                pin.note,
                style = Type.caption,
                color = p.ink2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp),
            )
        }
    }
}
