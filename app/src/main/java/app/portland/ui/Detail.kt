package app.portland.ui

import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import java.time.LocalDate
import java.time.format.FormatStyle
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import app.portland.R
import app.portland.data.Pin
import app.portland.data.R2
import app.portland.store
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState

/**
 * Swipeable viewer over [ids], laid out like a gallery: the media centred between a slim top bar (back, when it
 * was saved) and the bottom chrome (a filmstrip of the other pins over the action bar). Gestures on a pin's
 * media (see [MediaSwipe]): pull it down to go back to where it was opened from, push it up for the send shelf;
 * sideways pages between pins. The note, board and AI description live in the details sheet.
 */
@Composable
fun DetailScreen(ids: List<String>, start: Int) {
    val p = LocalPalette.current
    val store = LocalContext.current.store
    val nav = LocalNav.current
    val haptics = LocalHapticFeedback.current
    val lib by store.library.collectAsStateWithLifecycle()
    val wanted = remember(ids) { ids.toHashSet() }
    val pins = remember(ids, lib.pins) {
        val byId = HashMap<String, Pin>(wanted.size * 2)
        for (pin in lib.pins) if (pin.id in wanted) byId[pin.id] = pin
        ids.mapNotNull(byId::get)
    }
    // Leaving happens once: a back tap, a committed pull and an empty list must never pop twice (that would close the screen below too).
    var left by remember { mutableStateOf(false) }
    val leave: () -> Unit = { if (!left) { left = true; nav.pop() } }

    if (pins.isEmpty()) {
        LaunchedEffect(ids) { leave() }
        Box(Modifier.fillMaxSize().background(p.canvas))
        return
    }

    val pager = rememberPagerState(initialPage = start.coerceIn(0, pins.lastIndex)) { pins.size }
    LaunchedEffect(pins.size) { if (pager.currentPage > pins.lastIndex) pager.scrollToPage(pins.lastIndex) }
    // One player for the whole viewer (swapped between videos), not one built and released per page.
    val player = if (pins.any { it.video }) rememberViewerPlayer() else null
    val current = pins.getOrNull(pager.currentPage) ?: pins.first()
    val boardName = lib.boards.firstOrNull { it.id == current.boardId }?.name
    var details by remember { mutableStateOf<DetailsOpen?>(null) }
    var picker by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val backdrop = rememberLayerBackdrop { drawRect(p.canvas); drawContent() }
    // A committed pull pops straight away: the shared-element transition carries the media home from where it was let go.
    val swipe = rememberMediaSwipe { leave() }
    val chrome = Modifier.graphicsLayer { alpha = 1f - swipe.dismissProgress }
    // The bottom bar and filmstrip give way to the send dock as it rises into their place.
    val underDock = Modifier.graphicsLayer { alpha = (1f - swipe.dismissProgress) * (1f - swipe.sendProgress) }
    val strip = pins.size > 1
    val bottomChrome = BarHeight + BarGap + if (strip) StripHeight + StripGap else 0.dp

    Box(Modifier.fillMaxSize().background(p.canvas)) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize().layerBackdrop(backdrop),
            beyondViewportPageCount = 1,
            pageSpacing = 16.dp,
            userScrollEnabled = !swipe.shelfOpen,
            key = { pins.getOrNull(it)?.id ?: it },
        ) { page ->
            val pin = pins.getOrNull(page) ?: return@HorizontalPager
            // The whole stage takes the vertical swipes, not just the media, so a pull from anywhere closes.
            Box(
                Modifier
                    .fillMaxSize()
                    .mediaSwipe(swipe, pin.id)
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(top = TopBarHeight + StageGap, bottom = bottomChrome + StageGap)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                MediaBox(pin, active = pager.settledPage == page, swipe, player)
            }
        }

        // Top bar: back, and when this pin was saved.
        Row(
            chrome.fillMaxWidth().statusBarsPadding().height(TopBarHeight).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBtn(R.drawable.ic_back, "Back", backdrop = backdrop) { leave() }
            Spacer(Modifier.width(12.dp))
            AnimatedContent(
                targetState = current.createdAt to boardName,
                transitionSpec = { fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(120)) },
                modifier = Modifier.weight(1f),
                label = "when",
            ) { (at, board) ->
                Column {
                    Text(dayLabel(at), style = Type.headline, color = p.ink, maxLines = 1)
                    Text(
                        listOfNotNull(timeLabel(at), board).joinToString(" · "),
                        style = Type.footnote,
                        color = p.ink3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Column(
            underDock.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = BarGap),
        ) {
            if (strip) {
                Filmstrip(pins, pager)
                Spacer(Modifier.height(StripGap))
            }
            Row(
                Modifier.fillMaxWidth().height(BarHeight).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBtn(R.drawable.ic_share, "Share", backdrop = backdrop) { swipe.openShelf(current.id) }
                Spacer(Modifier.weight(1f))
                Row(Modifier.glass(backdrop, Corners.pill).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    BarAction(R.drawable.ic_bookmark, if (boardName == null) "Save to board" else "Change board") {
                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        picker = true
                    }
                    BarAction(R.drawable.ic_edit, "Edit note") { details = DetailsOpen.Note }
                    BarAction(R.drawable.ic_trash, "Delete") {
                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        confirm = true
                    }
                }
                Spacer(Modifier.weight(1f))
                IconBtn(R.drawable.ic_info, "Details", backdrop = backdrop) { details = DetailsOpen.Info }
            }
        }

        SendShelf(swipe, pins.firstOrNull { it.id == swipe.id }, backdrop)
    }

    details?.let { open ->
        DetailsSheet(
            pin = current,
            boardName = boardName,
            focusNote = open == DetailsOpen.Note,
            onBoard = { picker = true },
            onDismiss = { details = null },
        )
    }

    if (picker) {
        val pin = current
        BoardPickerSheet(
            current = pin.boardId,
            onPick = { id ->
                picker = false
                store.launch { setBoard(listOf(pin.id), id) }
            },
            onDismiss = { picker = false },
        )
    }

    if (confirm) DeletePinsDialog(
        count = 1,
        onDismiss = { confirm = false },
        onConfirm = {
            confirm = false
            val id = current.id
            // The pager moves on to the next pin; deleting the last one pops via the empty check above.
            store.launch { deletePins(listOf(id)) }
        },
    )
}

private enum class DetailsOpen { Info, Note }

private val TopBarHeight = 60.dp // a 44dp glass button with 8dp above and below
private val BarHeight = 44.dp
private val BarGap = 8.dp // below the action bar, above the navigation bar
private val StripHeight = 44.dp
private val StripGap = 14.dp // between the filmstrip and the action bar
private val StageGap = 12.dp // the media keeps at least this much air from the chrome

/** One icon in the action bar's glass pill: a 48×44 target with a small icon that swells under the finger. */
@Composable
private fun BarAction(@DrawableRes icon: Int, label: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.86f else 1f, Motion.press(), label = "press")
    Box(
        Modifier
            .size(width = 48.dp, height = BarHeight)
            .clickable(interactionSource = press, indication = null, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(icon),
            contentDescription = label,
            tint = p.ink,
            modifier = Modifier.size(IconSize.medium).graphicsLayer { scaleX = scale; scaleY = scale },
        )
    }
}

private val FrameWidth = 30.dp // a filmstrip frame that is not the current pin
private val FrameGap = 3.dp
private val FrameMargin = 5.dp // either side of the current frame

/**
 * The other pins as a strip of narrow frames, the current one opened up to its own shape and kept centred.
 * Tap a frame to go to it; drag the strip to scrub through the pins, the viewer following frame by frame.
 */
@Composable
private fun Filmstrip(pins: List<Pin>, pager: PagerState) {
    val p = LocalPalette.current
    val store = LocalContext.current.store
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    // Opens already centred on the current pin, rather than sliding there.
    val strip = rememberLazyListState(
        initialFirstVisibleItemIndex = pager.currentPage.coerceIn(0, pins.lastIndex),
        initialFirstVisibleItemScrollOffset = with(density) { (frameWidth(pins[pager.currentPage.coerceIn(0, pins.lastIndex)]) / 2).roundToPx() },
    )
    val dragged by strip.interactionSource.collectIsDraggedAsState()
    // From the first drag until the strip comes to rest (fling included), the strip leads and the pager follows.
    var scrubbing by remember { mutableStateOf(false) }
    LaunchedEffect(dragged) { if (dragged) scrubbing = true }
    LaunchedEffect(strip) {
        snapshotFlow { strip.isScrollInProgress }.collect { if (!it) scrubbing = false }
    }
    LaunchedEffect(strip) {
        snapshotFlow { if (scrubbing) centreFrame(strip) else null }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { i ->
                if (i != pager.currentPage) {
                    pager.scrollToPage(i)
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                }
            }
    }
    // Otherwise the pager leads: keep its pin centred (and re-centre once a scrub lets go).
    val page = pager.currentPage
    LaunchedEffect(page, scrubbing) {
        if (scrubbing) return@LaunchedEffect
        val half = with(density) { (frameWidth(pins.getOrNull(page) ?: return@LaunchedEffect) / 2).roundToPx() }
        strip.animateScrollToItem(page, half)
    }

    BoxWithConstraints(Modifier.fillMaxWidth().height(StripHeight)) {
        LazyRow(
            state = strip,
            modifier = Modifier.fillMaxSize(),
            // Half a screen either side, so the first and last pins can sit in the middle too.
            contentPadding = PaddingValues(horizontal = maxWidth / 2),
            horizontalArrangement = Arrangement.spacedBy(FrameGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            itemsIndexed(pins, key = { _, pin -> pin.id }, contentType = { _, _ -> "frame" }) { i, pin ->
                val selected = i == page
                // 0 = a narrow frame, 1 = opened up to the pin's shape and standing a little apart, like a lens.
                val open by animateFloatAsState(if (selected) 1f else 0f, Motion.snappy(), label = "frame")
                val wide = frameWidth(pin)
                Box(
                    Modifier
                        // Read in layout, so a swipe re-lays out two frames instead of recomposing them every frame.
                        // The spring overshoots past 0 and 1: the margin is clamped (a negative padding throws) and
                        // the width kept positive.
                        .layout { m, c ->
                            val t = open
                            val margin = (FrameMargin.toPx() * t).coerceAtLeast(0f).roundToInt()
                            val w = lerp(FrameWidth.toPx(), wide.toPx(), t).roundToInt().coerceAtLeast(1)
                            val h = c.maxHeight
                            val placeable = m.measure(Constraints.fixed(w, h))
                            layout(w + 2 * margin, h) { placeable.place(margin, 0) }
                        }
                        .clip(Corners.frame)
                        .background(p.fill)
                        .clickable(onClickLabel = "Show pin ${i + 1} of ${pins.size}") {
                            if (!selected) scope.launch { pager.animateScrollToPage(i, animationSpec = Motion.smooth()) }
                        },
                ) {
                    AsyncImage(
                        model = remember(pin.id) { store.thumb(pin) },
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize(),
                    )
                    if (pin.video) {
                        Icon(
                            painterResource(R.drawable.ic_play),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.align(Alignment.BottomStart).padding(5.dp).size(IconSize.inline),
                        )
                    }
                }
            }
        }
    }
}

/** The current pin's frame: its own shape at strip height, within reason. */
private fun frameWidth(pin: Pin): Dp {
    val ratio = pin.w.toFloat() / pin.h.coerceAtLeast(1)
    return (StripHeight * ratio).coerceIn(40.dp, 72.dp)
}

/** The frame under the middle of the strip. */
private fun centreFrame(strip: LazyListState): Int? {
    val info = strip.layoutInfo
    val centre = (info.viewportStartOffset + info.viewportEndOffset) / 2
    return info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - centre) }?.index
}

/**
 * Everything about the pin that isn't the pin: its AI description, board, your note (autosaved as you type)
 * and where it came from. [focusNote] opens it straight into the note, keyboard up.
 */
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailsSheet(pin: Pin, boardName: String?, focusNote: Boolean, onBoard: () -> Unit, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val store = LocalContext.current.store
    var text by remember(pin.id) { mutableStateOf(pin.note) }
    val latest = rememberUpdatedState(text)
    val focus = remember { FocusRequester() }

    // Debounced autosave while typing; the dispose hook catches a quick close.
    LaunchedEffect(text) {
        if (text == pin.note) return@LaunchedEffect
        delay(600)
        store.launch { setNote(pin.id, text) }
    }
    DisposableEffect(pin.id) {
        onDispose { if (latest.value != pin.note) store.launch { setNote(pin.id, latest.value) } }
    }
    LaunchedEffect(focusNote) {
        if (!focusNote) return@LaunchedEffect
        delay(280) // let the sheet rise first, so the keyboard doesn't race it
        focus.requestFocus()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = frostedSheet(),
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = Corners.sheetRadius, topEnd = Corners.sheetRadius),
        dragHandle = { BottomSheetDefaults.DragHandle(width = 36.dp, height = 5.dp, color = p.ink3.copy(alpha = 0.5f)) },
    ) {
        FrostBehind()
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
        ) {
            Text(
                "Details",
                style = Type.headline,
                color = p.ink,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            )
            RevealText(pin.caption.orEmpty()) {
                Text(it, style = Type.body, color = p.ink2, modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 20.dp))
            }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp)
                    .glass(null, Corners.card)
                    .clip(Corners.card)
                    .clickable(role = Role.Button, onClickLabel = "Change board", onClick = onBoard)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_bookmark), null, tint = p.ink, modifier = Modifier.size(IconSize.medium))
                Spacer(Modifier.width(12.dp))
                Text(
                    boardName ?: "No board",
                    style = Type.body.copy(fontWeight = FontWeight.Medium),
                    color = if (boardName != null) p.ink else p.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(painterResource(R.drawable.ic_chevron_down), null, tint = p.ink3, modifier = Modifier.size(IconSize.small))
            }
            Spacer(Modifier.height(12.dp))
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = Type.body.copy(color = p.ink),
                cursorBrush = SolidColor(p.ink),
                modifier = Modifier.fillMaxWidth()
                    .focusRequester(focus)
                    .glass(null, Corners.card)
                    .clip(Corners.card)
                    .heightIn(min = 104.dp)
                    .padding(16.dp),
                decorationBox = { field ->
                    Box {
                        if (text.isEmpty()) Text("Add a note", style = Type.body, color = p.ink3)
                        field()
                    }
                },
            )
            Text(
                buildString {
                    append("Saved ${savedDate(pin.createdAt)}")
                    append(" · ${pin.ext.uppercase()}")
                    if (pin.w > 0 && pin.h > 0) append(" · ${pin.w}×${pin.h}")
                    if (pin.video && pin.durationMs > 0) append(" · ${formatDuration(pin.durationMs)}")
                },
                style = Type.footnote,
                color = p.ink3,
                modifier = Modifier.padding(start = 4.dp, top = 12.dp),
            )
        }
    }
}

/**
 * The pin's media, as large as the stage allows at its own shape, centred. It moves (by placement) with the
 * swipe the stage around it feeds.
 */
@kotlin.OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun MediaBox(pin: Pin, active: Boolean, swipe: MediaSwipe, player: ExoPlayer?) {
    val p = LocalPalette.current
    val store = LocalContext.current.store
    val thumbModel = remember(pin.id) { store.thumb(pin) }
    val fullModel = remember(pin.id) { store.media(pin) }
    val desc = pin.note.ifBlank { null }
    var zoom by remember(pin.id) { mutableStateOf(false) }
    var loaded by remember(pin.id) { mutableStateOf(false) }
    val ratio = if (pin.w > 0 && pin.h > 0) (pin.w.toFloat() / pin.h).coerceIn(0.05f, 20f) else 1f
    // Only the settled page takes part in the shared-element transition: neighbour pages composed inside the pager
    // would register elements under grid keys and place/attach mid-swipe, a known crash source.
    val shared = rememberSharedPin(pin.id).takeIf { active }
    DisposableEffect(swipe, pin.id, shared) {
        swipe.handoffs[pin.id] = { velocity -> shared?.prepareTransitionWithInitialVelocity(velocity) }
        onDispose { swipe.handoffs.remove(pin.id) }
    }

    Box(
        Modifier
            // Placement, not a layer transform: the shared element reads layout, so a pulled pin flies home from where it is.
            .offset { swipe.mediaOffset(pin.id).safeRound() }
            .sharedPin(shared)
            // Fits the stage both ways: full width for wide media, full height for tall.
            .aspectRatio(ratio)
            .graphicsLayer { val s = swipe.mediaScale(pin.id); scaleX = s; scaleY = s }
            .clip(Corners.media)
            .background(p.fill)
            .then(if (loaded) Modifier else Modifier.shimmer(p.shimmer)),
    ) {
        // The thumbnail shows instantly and stays under the video until frames arrive.
        AsyncImage(
            model = thumbModel,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            onState = { if (it is AsyncImagePainter.State.Success || it is AsyncImagePainter.State.Error) loaded = true },
            modifier = Modifier.matchParentSize(),
        )
        if (pin.video) {
            if (player != null) VideoLayer(pin, active, player, Modifier.matchParentSize())
        } else {
            AsyncImage(
                model = fullModel,
                contentDescription = desc,
                contentScale = ContentScale.Fit,
                modifier = Modifier.matchParentSize().clickable(onClickLabel = "Open image") { zoom = true },
            )
        }
    }

    if (zoom) ZoomDialog(pin, fullModel, desc) { zoom = false }
}

/**
 * Full-screen pinch-zoom. At rest (not zoomed in) the same swipes work as in the viewer: pull the image
 * down to close (the black fades to show the viewer behind it), push it up for the send shelf.
 */
@Composable
private fun ZoomDialog(pin: Pin, model: Any, description: String?, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // Undimmed, so pulling the image away reveals the viewer as it is, not a grey veil.
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0f) }
        val swipe = rememberMediaSwipe(flyOut = true, onDismiss = onClose)
        val zoomState = rememberZoomableImageState()
        val backdrop = rememberLayerBackdrop { drawRect(Color.Black); drawContent() }
        val fadePx = with(LocalDensity.current) { 360.dp.toPx() }
        val chrome = Modifier.graphicsLayer { alpha = 1f - swipe.dismissProgress }
        // Opens like a lightbox: the black fades up while the image grows the last few percent into place.
        val open = remember { Animatable(0f) }
        LaunchedEffect(Unit) { open.animateTo(1f, Motion.smooth()) }

        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = open.value
                    val s = 0.94f + 0.06f * open.value
                    scaleX = s
                    scaleY = s
                }
                // Telephoto keeps a one-finger drag to itself only while zoomed in; at rest it is a swipe.
                .mediaSwipe(swipe, pin.id, enabled = { (zoomState.zoomableState.zoomFraction ?: 0f) <= 0.01f }),
        ) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = 1f - (swipe.pullY / fadePx).coerceIn(0f, 1f) }
                    .background(Color.Black),
            )
            ZoomableAsyncImage(
                model = model,
                contentDescription = description,
                state = zoomState,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .offset { swipe.mediaOffset(pin.id).safeRound() }
                    .graphicsLayer { val s = swipe.mediaScale(pin.id); scaleX = s; scaleY = s }
                    .layerBackdrop(backdrop),
            )
            IconBtn(
                R.drawable.ic_close,
                "Close",
                chrome.align(Alignment.TopEnd).statusBarsPadding().padding(16.dp),
                tint = Color.White,
                backdrop = backdrop,
                onClick = onClose,
            )
            IconBtn(
                R.drawable.ic_share,
                "Share",
                chrome.align(Alignment.TopStart).statusBarsPadding().padding(16.dp),
                tint = Color.White,
                backdrop = backdrop,
            ) { swipe.openShelf(pin.id) }
            SendShelf(swipe, pin, backdrop)
        }
    }
}

private const val SeekStepMs = 10_000L
private const val SeekWindowMs = 700L // taps this soon after a seek keep adding to it
private const val HideAfterMs = 2_500L
private val ScrubPad = 16.dp // inset of the track from the video's edges

/** The viewer's one player, shared by whichever video page is settled; released with the viewer, paused when the app leaves the foreground. */
@OptIn(UnstableApi::class)
@Composable
private fun rememberViewerPlayer(): ExoPlayer {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val exo = remember { ExoPlayer.Builder(context).build().apply { repeatMode = Player.REPEAT_MODE_ONE } }
    DisposableEffect(exo, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) exo.pause()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            exo.release()
        }
    }
    return exo
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoLayer(pin: Pin, active: Boolean, player: ExoPlayer, modifier: Modifier = Modifier) {
    val store = LocalContext.current.store
    val haptics = LocalHapticFeedback.current
    val holder = remember(pin.id) { mutableStateOf<ExoPlayer?>(null) }
    var playing by remember(pin.id) { mutableStateOf(true) }
    var duration by remember(pin.id) { mutableLongStateOf(pin.durationMs) }
    // Read only in draw lambdas and derived state, so it never recomposes per frame.
    var position by remember(pin.id) { mutableLongStateOf(0L) }
    var scrubbing by remember(pin.id) { mutableStateOf(false) }
    var shown by remember(pin.id) { mutableStateOf(true) }
    var poke by remember(pin.id) { mutableIntStateOf(0) } // bump to show the controls and restart the hide timer
    var seekSide by remember(pin.id) { mutableIntStateOf(0) } // -1 back, 1 forward
    var seekTotal by remember(pin.id) { mutableIntStateOf(0) } // accumulated seconds in the current chain
    var seekAt by remember(pin.id) { mutableLongStateOf(0L) }
    var pressAt by remember(pin.id) { mutableLongStateOf(0L) }
    var flash by remember(pin.id) { mutableStateOf(false) }
    var flashTick by remember(pin.id) { mutableIntStateOf(0) }

    DisposableEffect(pin.id, active) {
        if (!active) return@DisposableEffect onDispose {}
        val exo = player
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) duration = exo.duration.coerceAtLeast(0L)
            }
        }
        playing = true
        exo.addListener(listener)
        exo.playWhenReady = true
        val file = store.mediaFile(pin)
        if (file.exists()) {
            exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        } else {
            exo.setMediaSource(
                ProgressiveMediaSource.Factory(OkHttpDataSource.Factory(R2.http))
                    .createMediaSource(MediaItem.fromUri(store.media(pin).toString())),
            )
        }
        exo.prepare()
        holder.value = exo
        onDispose {
            // The surface leaves composition with this page; hand the player back empty for the next video.
            holder.value = null
            exo.removeListener(listener)
            exo.stop()
            exo.clearMediaItems()
        }
    }

    val exo = holder.value
    // Per-frame position while it is moving by itself (read only at draw time, so it redraws, never recomposes).
    LaunchedEffect(exo, playing, scrubbing) {
        if (exo == null) return@LaunchedEffect
        position = exo.currentPosition
        while (playing && !scrubbing) withFrameMillis { position = exo.currentPosition }
    }
    LaunchedEffect(poke, playing, scrubbing) {
        shown = true
        if (playing && !scrubbing) {
            delay(HideAfterMs)
            shown = false
        }
    }
    LaunchedEffect(flashTick) {
        if (flashTick == 0) return@LaunchedEffect
        delay(600)
        flash = false
    }
    val controls by animateFloatAsState(if (shown || !playing || scrubbing) 1f else 0f, tween(200), label = "controls")
    val flashAlpha by animateFloatAsState(if (flash) 1f else 0f, tween(if (flash) 90 else 260), label = "flash")

    val toggle by rememberUpdatedState<() -> Unit> {
        val e = exo ?: return@rememberUpdatedState
        haptics.performHapticFeedback(if (e.isPlaying) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
        if (e.isPlaying) e.pause() else e.play()
        poke++
    }
    // [at] is when the finger went down: a single tap is only reported after the double-tap timeout.
    val seekBy by rememberUpdatedState<(Int, Long) -> Unit> { side, at ->
        val e = exo ?: return@rememberUpdatedState
        seekTotal = if (side == seekSide && at - seekAt < SeekWindowMs) seekTotal + 10 else 10
        seekSide = side
        seekAt = at
        flash = true
        flashTick++
        val target = seekTarget(e.currentPosition, side * SeekStepMs, duration)
        e.seekTo(target)
        position = target
        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        poke++
    }

    Box(modifier) {
        if (exo != null) {
            // TextureView keeps the Compose controls above the video and shows the thumb until frames arrive.
            PlayerSurface(exo, Modifier.matchParentSize(), SURFACE_TYPE_TEXTURE_VIEW)
            Box(
                Modifier.matchParentSize()
                    .semantics {
                        onClick(if (playing) "Pause" else "Play") { toggle(); true }
                        customActions = listOf(
                            CustomAccessibilityAction("Back 10 seconds") { seekBy(-1, SystemClock.uptimeMillis()); true },
                            CustomAccessibilityAction("Forward 10 seconds") { seekBy(1, SystemClock.uptimeMillis()); true },
                        )
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = { pressAt = SystemClock.uptimeMillis() },
                            onDoubleTap = { seekBy(if (it.x < size.width / 2f) -1 else 1, pressAt) },
                            // After a seek, single taps keep seeking (YouTube-style) instead of pausing.
                            onTap = {
                                if (pressAt - seekAt < SeekWindowMs) seekBy(if (it.x < size.width / 2f) -1 else 1, pressAt) else toggle()
                            },
                        )
                    },
            )
            if (seekSide != 0) {
                val left = seekSide < 0
                Box(
                    Modifier.matchParentSize()
                        .graphicsLayer { alpha = flashAlpha }
                        .drawBehind {
                            val half = size.width / 2f
                            clipRect(left = if (left) 0f else half, right = if (left) half else size.width) {
                                drawCircle(
                                    Color.White.copy(alpha = 0.18f),
                                    radius = size.minDimension * 0.9f,
                                    center = Offset(if (left) 0f else size.width, size.height / 2f),
                                )
                            }
                        },
                )
                Box(
                    Modifier.align(if (left) Alignment.CenterStart else Alignment.CenterEnd)
                        .padding(horizontal = 28.dp)
                        .graphicsLayer { alpha = flashAlpha }
                        .clip(Corners.pill)
                        .background(Color.Black.copy(alpha = 0.40f))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Text(
                        "${if (left) "−" else "+"}${seekTotal}s",
                        style = Type.footnote.copy(fontWeight = FontWeight.SemiBold),
                        color = Color.White,
                    )
                }
            }
            if (duration > 0) {
                // Scrim so the white controls read on bright video.
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(88.dp)
                        .graphicsLayer { alpha = controls }
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f)))),
                )
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()) {
                    TimePill(playing, duration, { position }, Modifier.padding(start = 12.dp).graphicsLayer { alpha = controls })
                    Scrubber(
                        exo = exo,
                        duration = duration,
                        position = { position },
                        scrubbing = scrubbing,
                        onScrubbing = { scrubbing = it },
                        onSeek = { position = it },
                        controls = { controls },
                    )
                }
            }
        }
    }
}

@Composable
private fun TimePill(playing: Boolean, duration: Long, position: () -> Long, modifier: Modifier = Modifier) {
    // Whole seconds only, so the text recomposes once a second rather than every frame.
    val seconds by remember(duration) { derivedStateOf { position().coerceIn(0L, duration) / 1000 } }
    Row(
        modifier
            .clip(Corners.pill)
            .background(Color.Black.copy(alpha = 0.40f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play),
            contentDescription = if (playing) "Pause" else "Play",
            tint = Color.White,
            modifier = Modifier.size(IconSize.inline),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "${formatDuration(seconds * 1000)} / ${formatDuration(duration)}",
            style = Type.footnote.digits(),
            color = Color.White,
        )
    }
}

/**
 * Thin timeline with a tall touch target. Taps jump, drags scrub. Every pointer change is consumed so the
 * pager and the page's vertical scroll never take the gesture. [controls] (0..1) fades the full track and
 * thumb out in favour of a 2dp progress line at the bottom edge.
 */
@OptIn(UnstableApi::class)
@Composable
private fun Scrubber(
    exo: ExoPlayer,
    duration: Long,
    position: () -> Long,
    scrubbing: Boolean,
    onScrubbing: (Boolean) -> Unit,
    onSeek: (Long) -> Unit,
    controls: () -> Float,
    modifier: Modifier = Modifier,
) {
    val thumb by animateFloatAsState(if (scrubbing) 8f else 6f, animationSpec = Motion.snappy(), label = "thumb") // radius, dp
    val setScrubbing by rememberUpdatedState(onScrubbing)
    val setPosition by rememberUpdatedState(onSeek)
    Box(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .drawBehind {
                val pad = ScrubPad.toPx()
                val width = size.width - 2 * pad
                val fraction = positionToFraction(position(), duration)
                val c = controls()
                if (c > 0f) {
                    val y = size.height - 16.dp.toPx()
                    val h = 3.dp.toPx()
                    val round = CornerRadius(h / 2f)
                    drawRoundRect(Color.White.copy(alpha = 0.30f * c), Offset(pad, y - h / 2f), Size(width, h), round)
                    drawRoundRect(Color.White.copy(alpha = c), Offset(pad, y - h / 2f), Size(width * fraction, h), round)
                    drawCircle(Color.White.copy(alpha = c), thumb.dp.toPx(), Offset(pad + width * fraction, y))
                }
                val line = 1f - c
                if (line > 0f) {
                    val y = size.height - 4.dp.toPx()
                    val h = 2.dp.toPx()
                    drawRect(Color.White.copy(alpha = 0.25f * line), Offset(pad, y), Size(width, h))
                    drawRect(Color.White.copy(alpha = 0.85f * line), Offset(pad, y), Size(width * fraction, h))
                }
            }
            .pointerInput(exo, duration) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val pad = ScrubPad.toPx()
                    val track = (size.width - 2 * pad).coerceAtLeast(1f)
                    fun moveTo(x: Float) {
                        val to = fractionToPosition((x - pad) / track, duration)
                        exo.seekTo(to)
                        setPosition(to)
                    }
                    // Paused in place while dragging; playback resumes on release if it was running.
                    val resume = exo.playWhenReady
                    exo.pause()
                    exo.setScrubbingModeEnabled(true)
                    setScrubbing(true)
                    try {
                        moveTo(down.position.x)
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            change.consume()
                            if (!change.pressed) break
                            if (change.position != change.previousPosition) moveTo(change.position.x)
                        }
                    } finally {
                        setScrubbing(false)
                    }
                    exo.setScrubbingModeEnabled(false)
                    if (resume) exo.play()
                }
            },
    )
}

private fun savedDate(ms: Long): String =
    DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())
        .format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

/** "Today", "Yesterday", or the date (with the year only when it isn't this one). */
private fun dayLabel(ms: Long): String {
    val day = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when {
        day == today -> "Today"
        day == today.minusDays(1) -> "Yesterday"
        day.year == today.year -> DateTimeFormatter.ofPattern("d MMMM", Locale.getDefault()).format(day)
        else -> DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.getDefault()).format(day)
    }
}

private fun timeLabel(ms: Long): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault())
        .format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
