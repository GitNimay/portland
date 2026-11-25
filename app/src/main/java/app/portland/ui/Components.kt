package app.portland.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.portland.R
import app.portland.data.Captions
import app.portland.data.Pin
import app.portland.store
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter

val CardShape = RoundedCornerShape(16.dp)
val GridGutter = 12.dp

/** 48dp touch target, 24dp glyph, tinted with the ink ladder. */
@Composable
fun IconBtn(@DrawableRes icon: Int, label: String, modifier: Modifier = Modifier, tint: Color = LocalPalette.current.ink, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(painterResource(icon), contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/** The one primary action: a black circle with a plus. Steps aside while pins are selected. */
@Composable
fun AddButton(label: String, modifier: Modifier = Modifier, visible: Boolean = true, onClick: () -> Unit) {
    val p = LocalPalette.current
    AnimatedVisibility(
        visible,
        modifier,
        enter = scaleIn(spring(stiffness = Spring.StiffnessMediumLow), initialScale = 0.6f) + fadeIn(),
        exit = scaleOut(tween(160), targetScale = 0.6f) + fadeOut(tween(160)),
    ) {
        Box(
            Modifier
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

/** Which pins are picked for a bulk action. Empty = not selecting. */
@Stable
class PinSelection {
    var ids by mutableStateOf(emptySet<String>())
        private set
    val active get() = ids.isNotEmpty()

    fun toggle(id: String) {
        ids = if (id in ids) ids - id else ids + id
    }

    fun clear() {
        ids = emptySet()
    }
}

@Composable
fun rememberPinSelection() = remember { PinSelection() }

// Placeholder shapes for pins still being copied in; a mix so they read as a masonry, not a table.
private val SkeletonRatios = floatArrayOf(0.8f, 1.25f, 1f, 0.7f)

/**
 * Masonry of pins. [header] scrolls with the content as a full-width row.
 * [onOpen] receives the index into [pins]. With a [selection], long-press picks pins for bulk actions.
 * [loading] adds that many skeleton cards on top for pins that are still on their way in.
 */
@Composable
fun PinGrid(
    pins: List<Pin>,
    onOpen: (Int) -> Unit,
    modifier: Modifier = Modifier,
    selection: PinSelection? = null,
    loading: Int = 0,
    state: LazyStaggeredGridState = rememberLazyStaggeredGridState(),
    bottomPadding: Dp = 120.dp,
    header: @Composable () -> Unit = {},
) {
    val haptics = LocalHapticFeedback.current
    val picked = selection?.ids.orEmpty()
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(2),
        state = state,
        modifier = modifier,
        contentPadding = PaddingValues(start = GridGutter, end = GridGutter, bottom = bottomPadding),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalItemSpacing = 14.dp,
    ) {
        item(key = "header", span = StaggeredGridItemSpan.FullLine, contentType = "header") { header() }
        items(loading, key = { "skeleton:$it" }, contentType = { "skeleton" }) {
            SkeletonCard(SkeletonRatios[it % SkeletonRatios.size], Modifier.animateItem())
        }
        items(pins, key = { it.id }, contentType = { "pin" }) { pin ->
            val selected = pin.id in picked
            PinCard(
                pin,
                selecting = picked.isNotEmpty(),
                selected = selected,
                onClick = {
                    if (selection?.active == true) {
                        haptics.performHapticFeedback(if (selected) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                        selection.toggle(pin.id)
                    } else {
                        onOpen(pins.indexOf(pin))
                    }
                },
                onLongClick = selection?.let {
                    {
                        haptics.performHapticFeedback(
                            when {
                                !it.active -> HapticFeedbackType.LongPress
                                selected -> HapticFeedbackType.ToggleOff
                                else -> HapticFeedbackType.ToggleOn
                            },
                        )
                        it.toggle(pin.id)
                    }
                },
                modifier = Modifier.animateItem(),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PinCard(
    pin: Pin,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selecting: Boolean = false,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    val p = LocalPalette.current
    val store = LocalContext.current.store
    val model = remember(pin.id) { store.thumb(pin) }
    var loaded by remember(pin.id) { mutableStateOf(false) }
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, label = "press")
    // A picked pin sinks into its slot, like a pressed key that stays down.
    val inset by animateFloatAsState(
        if (selected) 0.9f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "selected",
    )
    // Very tall images are capped like a feed would; the viewer shows them whole.
    val ratio = (pin.w.toFloat() / pin.h.coerceAtLeast(1)).coerceIn(0.45f, 2f)

    Column(modifier.graphicsLayer { scaleX = scale; scaleY = scale }) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(ratio)
                .combinedClickable(
                    interactionSource = press,
                    indication = null,
                    onClickLabel = if (selecting) (if (selected) "Deselect" else "Select") else "Open",
                    onLongClickLabel = if (onLongClick != null) "Select" else null,
                    // We play our own haptics: a long-press tick to start selecting, toggles after that.
                    hapticFeedbackEnabled = false,
                    onLongClick = onLongClick,
                    onClick = onClick,
                ),
        ) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer { scaleX = inset; scaleY = inset }
                    .sharedPin(pin.id)
                    .clip(CardShape)
                    .background(p.fill)
                    .then(if (loaded) Modifier else Modifier.shimmer(p.shimmer)),
            ) {
                AsyncImage(
                    model = model,
                    contentDescription = pin.note.ifBlank { null },
                    contentScale = ContentScale.Crop,
                    onState = { loaded = it is AsyncImagePainter.State.Success || it is AsyncImagePainter.State.Error },
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
            SelectMark(selecting, selected, Modifier.align(Alignment.TopEnd).padding(8.dp))
        }
        // Your note wins; otherwise the AI description, which arrives a second or two after adding.
        // One line in the grid; the viewer shows it whole.
        val label = pin.note.ifBlank { pin.caption.orEmpty() }
        val describing = label.isBlank() && pin.caption == null && Captions.enabled &&
            System.currentTimeMillis() - pin.createdAt < DESCRIBING_MS
        AnimatedContent(
            targetState = if (describing) DESCRIBING else label,
            transitionSpec = {
                fadeIn(tween(320, delayMillis = 80)) togetherWith fadeOut(tween(120)) using SizeTransform(clip = false)
            },
            label = "label",
        ) { text ->
            when {
                text == DESCRIBING -> SkeletonLine(0.7f)
                text.isNotBlank() -> Text(
                    text,
                    style = Type.caption,
                    color = p.ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 6.dp),
                )
            }
        }
    }
}

// The description usually lands within seconds; past this, stop promising one (e.g. offline).
private const val DESCRIBING_MS = 2 * 60_000L
private const val DESCRIBING = "\u0000describing"

@Composable
private fun SelectMark(visible: Boolean, selected: Boolean, modifier: Modifier) {
    AnimatedVisibility(
        visible,
        modifier,
        enter = scaleIn(spring(stiffness = Spring.StiffnessMedium), initialScale = 0.5f) + fadeIn(tween(120)),
        exit = scaleOut(tween(120), targetScale = 0.5f) + fadeOut(tween(120)),
    ) { Mark(selected) }
}

@Composable
private fun Mark(selected: Boolean) {
    val p = LocalPalette.current
    Box(
        Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(if (selected) p.ink else Color.Black.copy(alpha = 0.2f))
            .border(1.5.dp, if (selected) p.canvas else Color.White, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(selected, enter = scaleIn(spring(stiffness = Spring.StiffnessHigh)) + fadeIn(), exit = scaleOut() + fadeOut()) {
            Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = p.canvas, modifier = Modifier.size(16.dp))
        }
    }
}

/**
 * While pins are selected: a top bar with the count and delete, back to cancel, and the delete
 * confirmation. Compose it last inside the screen's Box so it sits over the grid.
 */
@Composable
fun BoxScope.SelectionBar(selection: PinSelection) {
    val p = LocalPalette.current
    val store = LocalContext.current.store
    val haptics = LocalHapticFeedback.current
    var confirm by remember { mutableStateOf(false) }
    // Keeps the count steady while the bar slides away after clearing.
    var shown by remember { mutableIntStateOf(0) }
    if (selection.active) shown = selection.ids.size

    BackHandler(selection.active) { selection.clear() }

    AnimatedVisibility(
        selection.active,
        Modifier.align(Alignment.TopCenter),
        enter = slideInVertically(spring(stiffness = Spring.StiffnessMediumLow)) { -it } + fadeIn(),
        exit = slideOutVertically(tween(200)) { -it } + fadeOut(tween(200)),
    ) {
        Row(
            Modifier.fillMaxWidth().background(p.canvas).statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBtn(R.drawable.ic_close, "Cancel selection") { selection.clear() }
            AnimatedContent(
                shown,
                transitionSpec = {
                    val up = targetState > initialState
                    (slideInVertically { if (up) it else -it } + fadeIn()) togetherWith (slideOutVertically { if (up) -it else it } + fadeOut())
                },
                modifier = Modifier.weight(1f).padding(start = 4.dp),
                label = "count",
            ) { n ->
                Text("$n selected", style = Type.body.copy(fontWeight = FontWeight.Medium), color = p.ink)
            }
            IconBtn(R.drawable.ic_trash, "Delete selected") {
                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                confirm = true
            }
        }
    }

    if (confirm) DeletePinsDialog(
        count = selection.ids.size,
        onDismiss = { confirm = false },
        onConfirm = {
            confirm = false
            val ids = selection.ids
            selection.clear()
            store.launch { deletePins(ids) }
        },
    )
}

/** Asks before deleting [count] pins; plays the confirm haptic when the user goes ahead. */
@Composable
fun DeletePinsDialog(count: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val p = LocalPalette.current
    val haptics = LocalHapticFeedback.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (count == 1) "Delete this pin?" else "Delete $count pins?",
                style = Type.body.copy(fontWeight = FontWeight.Medium),
                color = p.ink,
            )
        },
        text = {
            Text(
                if (count == 1) "It will be removed from this phone and your backup." else "They will be removed from this phone and your backup.",
                style = Type.caption,
                color = p.ink2,
            )
        },
        confirmButton = {
            TextButton(onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                onConfirm()
            }) { Text("Delete", style = Type.body.copy(fontWeight = FontWeight.Medium), color = p.ink) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", style = Type.body, color = p.ink2) }
        },
        containerColor = p.canvas,
    )
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
        enter = expandVertically(tween(360, easing = FastOutSlowInEasing), expandFrom = Alignment.Top) + fadeIn(tween(480, delayMillis = 120)),
        exit = shrinkVertically(tween(240)) + fadeOut(tween(160)),
    ) { content(text) }
}
