package app.portland.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.portland.R
import app.portland.data.Library
import app.portland.data.R2
import app.portland.data.Release
import app.portland.data.Updater
import app.portland.data.search
import app.portland.store
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlin.math.roundToInt

@Composable
fun HomeScreen() {
    val p = LocalPalette.current
    val store = LocalContext.current.store
    val nav = LocalNav.current
    val lib by store.library.collectAsStateWithLifecycle()
    val importing by store.importing.collectAsStateWithLifecycle()
    val update by Updater.ready.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    var tab by rememberSaveable { mutableIntStateOf(0) } // 0 = pins, 1 = boards: the section chosen, outside search
    var query by rememberSaveable { mutableStateOf<String?>(null) } // null = search closed
    val searching = query != null
    val selection = rememberPinSelection()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    val pins = remember(lib.pins, lib.boards, query) { lib.search(query.orEmpty()) }

    // Pins and Boards side by side: swipe between them, or tap the segmented control. Search shows pins only.
    val pager = rememberPagerState(initialPage = tab) { 2 }
    // The thumb rides the pager itself, so it follows the finger 1:1 and glides on a tap.
    val pagePos = { (pager.currentPage + pager.currentPageOffsetFraction).coerceIn(0f, 1f) }
    LaunchedEffect(searching) {
        val target = if (searching) 0 else tab
        if (pager.currentPage != target || pager.currentPageOffsetFraction != 0f) pager.animateScrollToPage(target, animationSpec = Motion.snappy())
    }
    // A swipe that lands on the other section makes it the chosen one.
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { if (query == null && tab != it) { tab = it; selection.clear() } }
    }
    // One tick as a swipe crosses over to the other section, like the segmented control's own.
    val dragged by pager.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(pager) {
        snapshotFlow { pager.targetPage }.drop(1).collect { if (dragged) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick) }
    }
    val showTab: (Int) -> Unit = { i ->
        tab = i
        selection.clear()
        scope.launch { pager.animateScrollToPage(i, animationSpec = Motion.snappy()) }
    }

    BackHandler(searching) { query = null }
    // Back from Boards returns to Pins first, the app's start, before it leaves the app.
    BackHandler(!searching && !selection.active && tab == 1) { showTab(0) }

    val backdrop = rememberLayerBackdrop { drawRect(p.canvas); drawContent() }
    val density = LocalDensity.current

    val pinsState = rememberLazyStaggeredGridState()
    val boardsState = rememberLazyStaggeredGridState()
    var headerHeight by remember { mutableIntStateOf(0) } // the whole pinned header, status bar included
    var largeHeight by remember { mutableIntStateOf(0) } // only the large-title block that scrolls away
    // Past the large title the Pins/Boards control keeps rising until it docks in the top bar beside search,
    // so the collapsed header is a single row and the feed gets the rest of the screen.
    val dock = if (query == null) with(density) { TabDock.roundToPx() } else 0
    val collapse = largeHeight + dock
    val edgeSpan = with(density) { 24.dp.toPx() }

    // Mid-swipe the header sits between the two sections' scroll positions, in proportion to the swipe.
    val headerScroll = {
        val cap = collapse.toFloat()
        lerp(pinsState.headerScroll().coerceAtMost(cap), boardsState.headerScroll().coerceAtMost(cap), pagePos())
    }
    val edge = {
        lerp((pinsState.headerScroll() / edgeSpan).coerceIn(0f, 1f), (boardsState.headerScroll() / edgeSpan).coerceIn(0f, 1f), pagePos())
    }

    // Collapse tied 1:1 to the finger (scrolling or swiping), but gliding when the title block changes (search).
    val shift = remember { Animatable(0f) }
    LaunchedEffect(collapse, searching) {
        var first = true
        snapshotFlow { headerScroll() }.collect { target ->
            if (first) {
                first = false
                shift.animateTo(target, Motion.smooth())
            } else {
                shift.snapTo(target)
            }
        }
    }
    // 0 expanded .. 1 once the large title has scrolled away (the tabs may still be docking).
    val progress = { if (largeHeight == 0) 0f else (shift.value / largeHeight).coerceAtMost(1f) }

    val edgeBottom = with(density) { 12.dp.roundToPx() }

    Box(Modifier.fillMaxSize().background(p.canvas)) {
        // One backdrop over the pager, not one per grid: mid-swipe the glass sees both sections.
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize().layerBackdrop(backdrop),
            // Both grids stay composed and measured, so the other section is ready the moment a swipe starts.
            beyondViewportPageCount = 1,
            userScrollEnabled = !searching && !selection.active,
            key = { it },
        ) { page ->
            if (page == 1) {
                BoardsGrid(
                    lib = lib,
                    modifier = Modifier.fillMaxSize(),
                    state = boardsState,
                    header = { Spacer(Modifier.height(with(density) { headerHeight.coerceAtLeast(1).toDp() })) },
                )
            } else {
                PinGrid(
                    pins = pins,
                    onOpen = { i -> nav.push(Screen.Detail(pins.map { it.id }, i)) },
                    modifier = Modifier.fillMaxSize(),
                    selection = selection,
                    loading = if (query == null) importing else 0,
                    state = pinsState,
                    header = {
                        Column {
                            // Never 0 tall: a 0-height first row makes the grid anchor on the first pin, so it would open scrolled.
                            Spacer(Modifier.height(with(density) { headerHeight.coerceAtLeast(1).toDp() }))
                            when {
                                importing > 0 && query == null -> {}
                                lib.pins.isEmpty() -> EmptyState(
                                    "Nothing saved yet",
                                    "Tap + to add images, videos or a link, or share them to Portland from any app.",
                                )
                                pins.isEmpty() -> EmptyState("No matches", "Nothing in your notes, descriptions, board names or colours matches “${query?.trim()}”.")
                            }
                        }
                    },
                )
            }
        }
        // Scroll edge: the feed is blurred and washed as it slides under the pinned header.
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .layout { m, c ->
                    val h = (headerHeight + edgeBottom - shift.value).toInt().coerceAtLeast(0)
                    val pl = m.measure(Constraints.fixed(c.maxWidth, h))
                    layout(pl.width, pl.height) { pl.place(0, 0) }
                }
                .scrollEdge(backdrop, edge),
        )
        Header(
            lib = lib,
            importing = importing,
            matches = if (query.isNullOrBlank()) null else pins.size,
            update = update,
            onUpdate = { activity?.let { a -> store.launch { Updater.install(a) } } },
            query = query,
            onQuery = { query = it },
            tab = { if (searching) 0 else pager.targetPage },
            onTab = showTab,
            tabPos = pagePos,
            backdrop = backdrop,
            shift = { shift.value },
            progress = progress,
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
            onHeaderHeight = { headerHeight = it },
            onLargeHeight = { largeHeight = it },
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(0f to p.canvas.copy(alpha = 0f), 1f to p.canvas.copy(alpha = 0.85f)))
                .navigationBarsPadding()
                .height(72.dp),
        )
        AddMenu(backdrop, boardId = null, visible = !selection.active)
        SelectionBar(selection)
    }
}

@Composable
private fun Header(
    lib: Library,
    importing: Int,
    matches: Int?,
    update: Release?,
    onUpdate: () -> Unit,
    query: String?,
    onQuery: (String?) -> Unit,
    tab: () -> Int,
    onTab: (Int) -> Unit,
    tabPos: () -> Float,
    backdrop: Backdrop,
    shift: () -> Float,
    progress: () -> Float,
    modifier: Modifier = Modifier,
    onHeaderHeight: (Int) -> Unit,
    onLargeHeight: (Int) -> Unit,
) {
    val p = LocalPalette.current
    val haptics = LocalHapticFeedback.current
    Column(modifier.onSizeChanged { onHeaderHeight(it.height) }.statusBarsPadding()) {
        // Keeps the typed text on the field while it fades out after closing.
        var lastQuery by remember { mutableStateOf("") }
        if (query != null) lastQuery = query
        // The title row and the search field cross-fade. Opacity only: scaling would bend the glass refraction.
        AnimatedContent(
            targetState = query != null,
            transitionSpec = {
                fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(120)) using SizeTransform(clip = false)
            },
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxWidth().height(TopBarHeight),
            label = "search",
        ) { searching ->
            Row(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!searching) {
                    // No collapsed title: the Pins/Boards control docks on this side once scrolled.
                    Spacer(Modifier.weight(1f))
                    IconBtn(R.drawable.ic_search, "Search", backdrop = backdrop) { onQuery("") }
                } else {
                    val focus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { focus.requestFocus() }
                    BasicTextField(
                        // While it fades out after closing, the field no longer takes input.
                        value = lastQuery,
                        onValueChange = { onQuery(it) },
                        enabled = query != null,
                        singleLine = true,
                        textStyle = Type.body.copy(color = p.ink),
                        cursorBrush = SolidColor(p.ink),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        modifier = Modifier.weight(1f).height(44.dp).glass(backdrop).clip(Corners.pill).padding(horizontal = 14.dp).focusRequester(focus),
                        decorationBox = { field ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(painterResource(R.drawable.ic_search), contentDescription = null, tint = p.ink3, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Box(Modifier.weight(1f)) {
                                    if (lastQuery.isEmpty()) Text("Search", style = Type.body, color = p.ink3)
                                    field()
                                }
                            }
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                    IconBtn(R.drawable.ic_close, "Close search", backdrop = backdrop) { onQuery(null) }
                }
            }
        }
        // Large title, caption and update pill: slide up and shrink away with the scroll.
        Column(
            Modifier
                .fillMaxWidth()
                .onSizeChanged { onLargeHeight(it.height) }
                .offset { IntOffset(0, -shift().roundToInt()) }
                .graphicsLayer {
                    val t = progress()
                    alpha = (1f - t * 1.6f).coerceIn(0f, 1f)
                    scaleX = 1f - 0.06f * t
                    scaleY = scaleX
                    transformOrigin = TransformOrigin(0f, 0f)
                }
                .padding(start = 20.dp, end = 20.dp, top = 4.dp),
        ) {
            if (query == null) Text("Portland", style = Type.display, color = p.ink)
            // Status changes ("Adding 2…" → "backed up") cross-fade instead of snapping.
            AnimatedContent(
                targetState = matches?.let { if (it == 1) "1 match" else "$it matches" } ?: status(lib, importing),
                transitionSpec = { fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(120)) using SizeTransform(clip = false) },
                modifier = Modifier.padding(top = if (query == null) 4.dp else 0.dp),
                label = "status",
            ) { line ->
                Text(line, style = Type.caption, color = p.ink3)
            }
            // Remembers the version so the pill can fold away with its text intact.
            var lastUpdate by remember { mutableStateOf(update) }
            if (update != null) lastUpdate = update
            AnimatedVisibility(
                visible = update != null && query == null,
                // No height animation: the header re-measures its collapse on every size change.
                enter = fadeIn(tween(240, delayMillis = 80)) + scaleIn(Motion.smooth(), initialScale = 0.9f, transformOrigin = TransformOrigin(0f, 0.5f)),
                exit = fadeOut(tween(160)),
            ) {
                Text(
                    "Version ${lastUpdate?.versionName.orEmpty()} is ready · Restart",
                    style = Type.caption.copy(fontWeight = FontWeight.Medium),
                    color = p.ink,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .glass(null)
                        .clip(Corners.pill)
                        .clickable(role = Role.Button, onClickLabel = "Restart to update", onClick = onUpdate)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        if (query == null) {
            Box(
                Modifier
                    .offset { IntOffset(0, -shift().roundToInt()) }
                    .padding(start = 20.dp, top = TabGap, bottom = 12.dp),
            ) {
                Box(
                    Modifier
                        .width(220.dp)
                        .height(TabHeight)
                        .glass(backdrop)
                        .clip(Corners.pill)
                        .padding(3.dp),
                ) {
                    Box(
                        Modifier
                            .offset { IntOffset((tabPos() * 107.dp.toPx()).roundToInt(), 0) }
                            .width(107.dp)
                            .fillMaxHeight()
                            .shadow(
                                2.dp,
                                Corners.pill,
                                ambientColor = Color.Black.copy(alpha = 0.08f),
                                spotColor = Color.Black.copy(alpha = 0.12f),
                            )
                            .glass(null, tint = p.thumb)
                    )
                    Row(Modifier.fillMaxSize()) {
                        listOf("Pins", "Boards").forEachIndexed { i, label ->
                            val color by animateColorAsState(if (i == tab()) p.ink else p.ink2, Motion.smooth(), label = "tabColor")
                            Box(
                                Modifier
                                    .width(107.dp)
                                    .fillMaxHeight()
                                    .clip(Corners.pill)
                                    .clickable(role = Role.Tab, onClickLabel = label) {
                                        if (i != tab()) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                        onTab(i)
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    label,
                                    style = Type.caption.copy(fontWeight = if (i == tab()) FontWeight.SemiBold else FontWeight.Medium),
                                    color = color,
                                )
                            }
                        }
                    }
                }
            }
        } else {
            Spacer(Modifier.height(12.dp))
        }
    }
}

private fun status(lib: Library, importing: Int): String {
    val n = lib.pins.size
    val saved = if (n == 1) "1 saved" else "$n saved"
    val waiting = lib.pins.count { !it.synced }
    return when {
        importing > 0 -> "Adding $importing…"
        !R2.enabled -> "$saved · on this phone only"
        waiting > 0 -> "$saved · $waiting waiting to back up"
        else -> "$saved · backed up"
    }
}

private val TopBarHeight = 56.dp
private val TabHeight = 36.dp
private val TabGap = 16.dp // between the large title block and the tabs

/** Extra collapse after the large title so the tabs end up vertically centred in the top bar, level with search. */
private val TabDock = TopBarHeight + TabGap - (TopBarHeight - TabHeight) / 2
