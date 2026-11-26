package app.portland.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import com.kyant.backdrop.Backdrop
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.portland.R
import app.portland.data.Captions
import app.portland.data.Pin
import app.portland.data.Prefs
import app.portland.data.sharedBoard
import app.portland.store
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest

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
            Icon(painterResource(icon), contentDescription = label, tint = tint, modifier = Modifier.size(IconSize.medium))
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
        Icon(painterResource(icon), contentDescription = label, tint = tint, modifier = Modifier.size(IconSize.medium))
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    // Drifts up into place rather than popping in under the header.
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) { shown.animateTo(1f, Motion.smooth()) }
    Column(
        modifier
            .graphicsLayer {
                alpha = shown.value
                translationY = (1f - shown.value) * 12.dp.toPx()
            }
            .padding(horizontal = 4.dp, vertical = 48.dp),
    ) {
        Text(title, style = Type.headline, color = p.ink)
        Spacer(Modifier.height(6.dp))
        Text(body, style = Type.footnote, color = p.ink2)
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

/**
 * Opens the viewer over [pins] at the tapped index. The same function instance survives library changes (it
 * reads the newest list when called), so a caption arriving for one pin does not hand every visible card a new
 * click lambda and recompose the whole feed.
 */
@Composable
fun rememberOpenPin(pins: List<Pin>): (Int) -> Unit {
    val nav = LocalNav.current
    val latest by rememberUpdatedState(pins)
    return remember(nav) { { i -> nav.push(Screen.Detail(latest.map { it.id }, i)) } }
}

/**
 * Memory-cache key shared by a pin's grid card and the viewer's media, so the viewer can show the card's
 * already-decoded thumbnail the instant a shared-element transition starts (the guidance for Coil + shared elements).
 */
private fun thumbCacheKey(id: String) = "pin-thumb:$id"

/** The grid card's thumbnail request: [model] under the shared [thumbCacheKey]. */
@Composable
internal fun rememberCardThumb(id: String, model: Any): ImageRequest {
    val context = LocalContext.current
    return remember(id) { ImageRequest.Builder(context).data(model).memoryCacheKey(thumbCacheKey(id)).build() }
}

/** The viewer's thumbnail request: starts from whatever the grid card already decoded, then loads at stage size. */
@Composable
internal fun rememberViewerThumb(id: String, model: Any): ImageRequest {
    val context = LocalContext.current
    return remember(id) {
        ImageRequest.Builder(context).data(model)
            .memoryCacheKey(thumbCacheKey(id))
            .placeholderMemoryCacheKey(thumbCacheKey(id))
            .build()
    }
}

// Placeholder shapes for pins still being copied in; a mix so they read as a masonry, not a table.
private val SkeletonRatios = floatArrayOf(0.8f, 1.25f, 1f, 0.7f)

/**
 * Masonry of pins. [header] scrolls with the content as a full-width row.
 * [onOpen] receives the index into [pins]. With a [selection], long-press picks pins for bulk actions.
 * [loading] adds that many skeleton cards on top for pins that are still on their way in.
 * Pinching the grid changes its column count, the same for every pin grid and kept across launches.
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
    val haptics = LocalHaptics.current
    val context = LocalContext.current
    val prefs by remember(context) { Prefs.state(context) }.collectAsStateWithLifecycle()
    val columns by rememberUpdatedState(prefs.gridColumns)
    // The pinch detector outlives recompositions, so it reads the latest haptics (they change with the setting).
    val setColumns by rememberUpdatedState<(Int) -> Unit> {
        haptics.play(Fx.Detent)
        Prefs.setGridColumns(context, it)
    }
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(columns),
        state = state,
        modifier = modifier.pinchColumns(columns = { columns }, onColumns = { setColumns(it) }),
        contentPadding = PaddingValues(start = GridGutter, end = GridGutter, bottom = bottomPadding),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalItemSpacing = 16.dp,
    ) {
        item(key = "header", span = StaggeredGridItemSpan.FullLine, contentType = "header") { header() }
        items(loading, key = { "skeleton:$it" }, contentType = { "skeleton" }) {
            SkeletonCard(SkeletonRatios[it % SkeletonRatios.size], Modifier.animateItem())
        }
        itemsIndexed(pins, key = { _, pin -> pin.id }, contentType = { _, _ -> "pin" }) { i, pin ->
            // Read per card, not around the grid: toggling one pin re-runs the cards, not the grid and its item list.
            val picked = selection?.ids.orEmpty()
            val selected = pin.id in picked
            PinCard(
                pin,
                selecting = picked.isNotEmpty(),
                selected = selected,
                onClick = {
                    if (selection?.active == true) {
                        haptics.play(if (selected) Fx.ToggleOff else Fx.ToggleOn)
                        selection.toggle(pin.id)
                    } else {
                        onOpen(i)
                    }
                },
                onLongClick = selection?.let {
                    {
                        haptics.play(
                            when {
                                !it.active -> Fx.Grab
                                selected -> Fx.ToggleOff
                                else -> Fx.ToggleOn
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
    val request = rememberCardThumb(pin.id, remember(pin.id) { store.thumb(pin) })
    var loaded by remember(pin.id) { mutableStateOf(false) }
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, Motion.press(), label = "press")
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
                    model = request,
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
                            // Plain translucent pill: a backdrop layer + lens shader per video card is too costly in a scrolling grid.
                            .clip(Corners.pill)
                            .background(Color.Black.copy(alpha = 0.38f))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(painterResource(R.drawable.ic_play), contentDescription = "Video", tint = Color.White, modifier = Modifier.size(IconSize.inline))
                        Spacer(Modifier.width(4.dp))
                        val duration = remember(pin.durationMs) { formatDuration(pin.durationMs) }
                        Text(duration, style = VideoBadgeStyle, color = Color.White)
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
                    style = Type.footnote,
                    color = p.ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp),
                )
            }
        }
    }
}

private val VideoBadgeStyle = Type.footnote.copy(fontWeight = FontWeight.Medium).digits()

// The description usually lands within seconds; past this, stop promising one (e.g. offline).
private const val DESCRIBING_MS = 2 * 60_000L
private const val DESCRIBING = "\u0000describing"

@Composable
private fun SelectMark(visible: Boolean, selected: Boolean, modifier: Modifier) {
    AnimatedVisibility(
        visible,
        modifier,
        enter = scaleIn(spring(stiffness = Spring.StiffnessMedium), initialScale = 0.5f) + fadeIn(tween(120)),
        exit = scaleOut(Motion.dismiss(), targetScale = 0.5f) + fadeOut(tween(120)),
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
            Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = p.canvas, modifier = Modifier.size(IconSize.small))
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
    val haptics = LocalHaptics.current
    var confirm by remember { mutableStateOf(false) }
    // Ids being filed, captured when the sheet opens: the selection is cleared on pick, and the
    // sheet's own animation out must not see it change underneath.
    var filing by remember { mutableStateOf<Set<String>?>(null) }
    // Keeps the count steady while the bar slides away after clearing.
    var shown by remember { mutableIntStateOf(0) }
    if (selection.active) shown = selection.ids.size

    BackHandler(selection.active) { selection.clear() }

    AnimatedVisibility(
        selection.active,
        Modifier.align(Alignment.TopCenter),
        enter = slideInVertically(Motion.snappy()) { -it } + fadeIn(),
        exit = slideOutVertically(Motion.dismiss()) { -it } + fadeOut(tween(200)),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().background(p.canvas).statusBarsPadding().height(56.dp).padding(horizontal = 12.dp),
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
                    Text("$n selected", style = Type.headline, color = p.ink)
                }
                IconBtn(R.drawable.ic_bookmark, "Add selected to board") {
                    filing = selection.ids
                }
                IconBtn(R.drawable.ic_trash, "Delete selected") {
                    confirm = true
                }
            }
            // A soft edge instead of a hard cut where the grid slides under the bar.
            Box(Modifier.fillMaxWidth().height(16.dp).background(Brush.verticalGradient(listOf(p.canvas, p.canvas.copy(alpha = 0f)))))
        }
    }

    filing?.let { ids ->
        // Read once on open; a check only makes sense if every picked pin agrees.
        val (known, current) = remember(ids) { store.library.value.sharedBoard(ids) }
        BoardPickerSheet(
            current = current,
            known = known,
            title = if (ids.size == 1) "Save to board" else "Save ${ids.size} pins to board",
            onPick = { boardId ->
                filing = null
                selection.clear()
                store.launch { setBoard(ids, boardId) }
            },
            onDismiss = { filing = null },
        )
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
    PortlandDialog(
        title = if (count == 1) "Delete this pin?" else "Delete $count pins?",
        body = if (count == 1) "It will be removed from this phone and your backup." else "They will be removed from this phone and your backup.",
        confirmLabel = "Delete",
        destructive = true,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
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
        enter = expandVertically(Motion.smooth(), expandFrom = Alignment.Top) + fadeIn(tween(320, delayMillis = 80)),
        exit = shrinkVertically(Motion.smooth()) + fadeOut(tween(160)),
    ) { content(text) }
}
