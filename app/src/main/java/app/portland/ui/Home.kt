package app.portland.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.portland.R
import app.portland.data.Library
import app.portland.data.R2
import app.portland.data.Release
import app.portland.data.Updater
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
    var tab by rememberSaveable { mutableIntStateOf(0) } // 0 = pins, 1 = boards
    var query by rememberSaveable { mutableStateOf<String?>(null) } // null = search closed
    val pick = rememberLauncherForActivityResult(PickMultipleVisualMedia()) { uris -> store.launch { add(uris, null) } }
    val selection = rememberPinSelection()

    val pins = remember(lib.pins, lib.boards, query) {
        val q = query?.trim().orEmpty()
        if (q.isEmpty()) lib.pins
        else {
            val boardHits = lib.boards.filter { it.name.contains(q, ignoreCase = true) }.mapTo(HashSet()) { it.id }
            lib.pins.filter {
                it.note.contains(q, ignoreCase = true) || it.caption.orEmpty().contains(q, ignoreCase = true) || it.boardId in boardHits
            }
        }
    }

    // Hoisted so the segmented control keeps sliding while the grid swaps between pins and boards.
    val tabPos by animateFloatAsState(if (query != null) 0f else tab.toFloat(), Motion.snappy(), label = "tab")

    BackHandler(query != null) { query = null }

    val backdrop = rememberLayerBackdrop { drawRect(p.canvas); drawContent() }
    val density = LocalDensity.current

    val pinsState = rememberLazyStaggeredGridState()
    val boardsState = rememberLazyStaggeredGridState()
    val grid = if (tab == 1 && query == null) boardsState else pinsState
    var headerHeight by remember { mutableIntStateOf(0) } // the whole pinned header, status bar included
    var largeHeight by remember { mutableIntStateOf(0) } // only the large-title block that scrolls away

    // Collapse tied 1:1 to the finger, but gliding when the grid swaps (tab change or search).
    val shift = remember { Animatable(0f) }
    LaunchedEffect(grid, largeHeight) {
        var first = true
        snapshotFlow { grid.headerScroll().coerceAtMost(largeHeight.toFloat()) }.collect { target ->
            if (first) {
                first = false
                shift.animateTo(target, Motion.smooth())
            } else {
                shift.snapTo(target)
            }
        }
    }
    val progress = { if (largeHeight == 0) 0f else shift.value / largeHeight } // 0 expanded .. 1 collapsed

    val edgeBottom = with(density) { 12.dp.roundToPx() }
    val edgeSpan = with(density) { 24.dp.toPx() }

    Box(Modifier.fillMaxSize().background(p.canvas)) {
        if (tab == 1 && query == null) {
            BoardsGrid(
                lib = lib,
                modifier = Modifier.fillMaxSize().layerBackdrop(backdrop),
                state = boardsState,
                header = { Spacer(Modifier.height(with(density) { headerHeight.toDp() })) },
            )
        } else {
            PinGrid(
                pins = pins,
                onOpen = { i -> nav.push(Screen.Detail(pins.map { it.id }, i)) },
                modifier = Modifier.fillMaxSize().layerBackdrop(backdrop),
                selection = selection,
                loading = if (query == null) importing else 0,
                state = pinsState,
                header = {
                    Column {
                        Spacer(Modifier.height(with(density) { headerHeight.toDp() }))
                        when {
                            importing > 0 && query == null -> {}
                            lib.pins.isEmpty() -> EmptyState(
                                "Nothing saved yet",
                                "Tap + to add images and videos, or share them to Portland from any app.",
                            )
                            pins.isEmpty() -> EmptyState("No matches", "Nothing in your notes, descriptions or board names matches “${query?.trim()}”.")
                        }
                    }
                },
            )
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
                .scrollEdge(backdrop) { (grid.headerScroll() / edgeSpan).coerceIn(0f, 1f) },
        )
        Header(
            lib = lib,
            importing = importing,
            matches = if (query.isNullOrBlank()) null else pins.size,
            update = update,
            onUpdate = { activity?.let { a -> store.launch { Updater.install(a) } } },
            query = query,
            onQuery = { query = it },
            tab = if (query != null) 0 else tab,
            onTab = { tab = it; selection.clear() },
            tabPos = { tabPos },
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
        AddButton(
            "Add images and videos",
            Modifier.align(Alignment.BottomEnd),
            backdrop = backdrop,
            visible = !selection.active,
        ) { pick.launch(PickVisualMediaRequest(PickVisualMedia.ImageAndVideo)) }
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
    tab: Int,
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
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (query == null) {
                Spacer(Modifier.size(44.dp))
                Text(
                    "Portland",
                    style = Type.body.copy(fontWeight = FontWeight.SemiBold),
                    color = p.ink,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).graphicsLayer {
                        alpha = ((progress() - 0.55f) / 0.45f).coerceIn(0f, 1f)
                    },
                )
                IconBtn(R.drawable.ic_search, "Search", backdrop = backdrop) { onQuery("") }
            } else {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                BasicTextField(
                    value = query,
                    onValueChange = { onQuery(it) },
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
                                if (query.isEmpty()) Text("Search", style = Type.body, color = p.ink3)
                                field()
                            }
                        }
                    },
                )
                Spacer(Modifier.width(8.dp))
                IconBtn(R.drawable.ic_close, "Close search", backdrop = backdrop) { onQuery(null) }
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
            Text(
                matches?.let { if (it == 1) "1 match" else "$it matches" } ?: status(lib, importing),
                style = Type.caption,
                color = p.ink3,
                modifier = Modifier.padding(top = if (query == null) 4.dp else 0.dp),
            )
            if (update != null && query == null) {
                Text(
                    "Version ${update.versionName} is ready · Restart",
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
                    .padding(start = 20.dp, top = 16.dp, bottom = 12.dp),
            ) {
                Box(
                    Modifier
                        .width(220.dp)
                        .height(36.dp)
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
                            val color by animateColorAsState(if (i == tab) p.ink else p.ink2, Motion.smooth(), label = "tabColor")
                            Box(
                                Modifier
                                    .width(107.dp)
                                    .fillMaxHeight()
                                    .clip(Corners.pill)
                                    .clickable(role = Role.Tab, onClickLabel = label) {
                                        if (i != tab) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                        onTab(i)
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    label,
                                    style = Type.caption.copy(fontWeight = if (i == tab) FontWeight.SemiBold else FontWeight.Medium),
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
