package app.portland.ui

import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.portland.R
import app.portland.data.Board
import app.portland.data.Library
import app.portland.data.Pin
import app.portland.store
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun BoardsGrid(
    lib: Library,
    modifier: Modifier = Modifier,
    state: LazyStaggeredGridState = rememberLazyStaggeredGridState(),
    header: @Composable () -> Unit,
) {
    val p = LocalPalette.current
    val store = LocalContext.current.store
    val nav = LocalNav.current
    var naming by remember { mutableStateOf(false) }
    val byBoard = remember(lib.pins) { lib.pins.groupBy { it.boardId } }
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, Motion.press(), label = "press")

    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(2),
        state = state,
        modifier = modifier,
        contentPadding = PaddingValues(start = GridGutter, end = GridGutter, bottom = 120.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalItemSpacing = 20.dp,
    ) {
        item(key = "header", span = StaggeredGridItemSpan.FullLine, contentType = "header") { header() }
        item(key = "new", contentType = "new") {
            Column(
                Modifier
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .clickable(interactionSource = press, indication = null, onClickLabel = "New board") { naming = true },
            ) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(1f).glass(null, Corners.card).clip(CardShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.ic_plus), contentDescription = null, tint = p.ink2, modifier = Modifier.size(IconSize.large))
                }
                Spacer(Modifier.height(10.dp))
                Text("New board", style = Type.body.copy(fontWeight = FontWeight.Medium), color = p.ink)
            }
        }
        items(lib.boards.size, key = { lib.boards[it].id }, contentType = { "board" }) { i ->
            val board = lib.boards[i]
            BoardCell(board, byBoard[board.id].orEmpty()) { nav.push(Screen.Board(board.id)) }
        }
    }

    if (naming) {
        NameDialog("New board", "Create", onDismiss = { naming = false }) { name ->
            naming = false
            store.launch { createBoard(name) }
        }
    }
}

@Composable
private fun BoardCell(board: Board, pins: List<Pin>, onClick: () -> Unit) {
    val p = LocalPalette.current
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, Motion.press(), label = "press")

    Column(
        Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(interactionSource = press, indication = null, onClickLabel = board.name, onClick = onClick),
    ) {
        Row(Modifier.fillMaxWidth().aspectRatio(1f).clip(CardShape).background(p.fill)) {
            // A collage only reads as one once there are three pins; until then the newest fills the cover.
            CoverCell(pins.getOrNull(0), Modifier.weight(2f).fillMaxHeight())
            if (pins.size >= 3) {
                Spacer(Modifier.width(2.dp))
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    CoverCell(pins[1], Modifier.weight(1f).fillMaxWidth())
                    Spacer(Modifier.height(2.dp))
                    CoverCell(pins[2], Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            board.name,
            style = Type.subhead.copy(fontWeight = FontWeight.Medium),
            color = p.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            if (pins.size == 1) "1 pin" else "${pins.size} pins",
            style = Type.footnote,
            color = p.ink3,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun CoverCell(pin: Pin?, modifier: Modifier) {
    val p = LocalPalette.current
    val store = LocalContext.current.store
    var loaded by remember(pin?.id) { mutableStateOf(false) }
    Box(if (pin == null || loaded) modifier else modifier.shimmer(p.shimmer)) {
        if (pin != null) {
            AsyncImage(
                model = remember(pin.id) { store.thumb(pin) },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onState = { loaded = it is AsyncImagePainter.State.Success || it is AsyncImagePainter.State.Error },
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

@Composable
fun BoardScreen(id: String) {
    val p = LocalPalette.current
    val store = LocalContext.current.store
    val nav = LocalNav.current
    val lib by store.library.collectAsStateWithLifecycle()
    val board = lib.boards.find { it.id == id }
    val pins = remember(lib.pins, id) { lib.pins.filter { it.boardId == id } }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    val importing by store.importing.collectAsStateWithLifecycle()
    val selection = rememberPinSelection()
    val backdrop = rememberLayerBackdrop {
        drawRect(p.canvas)
        drawContent()
    }
    val density = LocalDensity.current
    val grid = rememberLazyStaggeredGridState()
    var headerHeight by remember { mutableIntStateOf(0) }
    var largeHeight by remember { mutableIntStateOf(0) }
    val shift = remember { Animatable(0f) }
    LaunchedEffect(grid, largeHeight) {
        snapshotFlow { grid.headerScroll().coerceAtMost(largeHeight.toFloat()) }.collect { shift.snapTo(it) }
    }
    val progress = { if (largeHeight == 0) 0f else shift.value / largeHeight }
    val edgePx = with(density) { 24.dp.toPx() }

    LaunchedEffect(board) { if (board == null) nav.pop() }
    if (board == null) return

    Box(Modifier.fillMaxSize().background(p.canvas)) {
        PinGrid(
            pins = pins,
            onOpen = { i -> nav.push(Screen.Detail(pins.map { it.id }, i)) },
            modifier = Modifier.fillMaxSize().layerBackdrop(backdrop),
            selection = selection,
            loading = importing,
            state = grid,
            header = {
                Column {
                    Spacer(Modifier.height(with(density) { headerHeight.coerceAtLeast(1).toDp() }))
                    if (pins.isEmpty() && importing == 0) EmptyState("No pins yet", "Tap + to add images, videos or a link to this board.")
                }
            },
        )
        // Scroll edge: blurs and washes whatever slides under the pinned header.
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .layout { m, c ->
                    val h = (headerHeight + 12.dp.roundToPx() - shift.value).toInt().coerceAtLeast(0)
                    val pl = m.measure(Constraints.fixed(c.maxWidth, h))
                    layout(pl.width, pl.height) { pl.place(0, 0) }
                }
                .scrollEdge(backdrop) { (grid.headerScroll() / edgePx).coerceIn(0f, 1f) },
        )
        // Pinned header: glass buttons over a large title that collapses into the bar.
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .onSizeChanged { headerHeight = it.height }
                .statusBarsPadding(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBtn(R.drawable.ic_back, "Back", backdrop = backdrop) { nav.pop() }
                Text(
                    board.name,
                    style = Type.headline,
                    color = p.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                        .graphicsLayer { alpha = ((progress() - 0.55f) / 0.45f).coerceIn(0f, 1f) },
                )
                Box {
                    IconBtn(R.drawable.ic_more, "More", backdrop = backdrop) { menu = true }
                    DropdownMenu(
                        expanded = menu,
                        onDismissRequest = { menu = false },
                        shape = Corners.menu,
                        containerColor = p.sheet,
                        tonalElevation = 0.dp,
                        shadowElevation = 16.dp,
                    ) {
                        DropdownMenuItem(
                            text = { Text("Rename", style = Type.body, color = p.ink) },
                            leadingIcon = {
                                Icon(painterResource(R.drawable.ic_edit), null, tint = p.ink, modifier = Modifier.size(IconSize.medium))
                            },
                            onClick = { menu = false; renaming = true },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete board", style = Type.body, color = p.danger) },
                            leadingIcon = {
                                Icon(painterResource(R.drawable.ic_trash), null, tint = p.danger, modifier = Modifier.size(IconSize.medium))
                            },
                            onClick = { menu = false; confirming = true },
                        )
                    }
                }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .onSizeChanged { largeHeight = it.height }
                    .offset { IntOffset(0, -shift.value.roundToInt()) }
                    .graphicsLayer {
                        val t = progress()
                        alpha = (1f - t * 1.6f).coerceIn(0f, 1f)
                        scaleX = 1f - 0.06f * t
                        scaleY = scaleX
                        transformOrigin = TransformOrigin(0f, 0f)
                    }
                    .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp),
            ) {
                Text(
                    board.name,
                    style = Type.largeTitle,
                    color = p.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (pins.size == 1) "1 pin" else "${pins.size} pins",
                    style = Type.footnote,
                    color = p.ink3,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        0f to p.canvas.copy(alpha = 0f),
                        1f to p.canvas.copy(alpha = 0.85f),
                    ),
                )
                .navigationBarsPadding()
                .height(72.dp),
        )
        AddMenu(backdrop, boardId = id, visible = !selection.active)
        SelectionBar(selection)
    }

    if (renaming) {
        NameDialog("Rename board", "Save", initial = board.name, onDismiss = { renaming = false }) { name ->
            renaming = false
            store.launch { renameBoard(id, name) }
        }
    }
    if (confirming) {
        PortlandDialog(
            title = "Delete this board?",
            body = "Pins in it stay in your library.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                confirming = false
                nav.pop()
                store.launch { deleteBoard(id) }
            },
            onDismiss = { confirming = false },
        )
    }
}

/**
 * Bottom sheet to choose a board for a pin; null = no board. [current] is checked unless [known] is
 * false, which is how a mixed multi-selection says "no single board".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardPickerSheet(
    current: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
    known: Boolean = true,
    title: String = "Save to board",
) {
    val p = LocalPalette.current
    val haptics = LocalHapticFeedback.current
    // Filing a pin is a small commitment; a confirm tick says it landed.
    val choose: (String?) -> Unit = { haptics.performHapticFeedback(HapticFeedbackType.Confirm); onPick(it) }
    val store = LocalContext.current.store
    val scope = rememberCoroutineScope()
    val lib by store.library.collectAsStateWithLifecycle()
    var naming by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = frostedSheet(),
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = Corners.sheetRadius, topEnd = Corners.sheetRadius),
        dragHandle = {
            BottomSheetDefaults.DragHandle(width = 36.dp, height = 5.dp, color = p.ink3.copy(alpha = 0.5f))
        },
    ) {
        FrostBehind()
        Text(
            title,
            style = Type.headline,
            color = p.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 12.dp),
        )
        LazyColumn(Modifier.navigationBarsPadding(), contentPadding = PaddingValues(bottom = 12.dp)) {
            item(key = "new") {
                PickerRow("New board", selected = false, visual = { AddGlyph() }) { naming = true }
            }
            item(key = "none") {
                PickerRow("No board", selected = known && current == null, visual = { ClearGlyph() }) {
                    choose(null)
                    onDismiss()
                }
            }
            items(lib.boards.size, key = { lib.boards[it].id }) { i ->
                val board = lib.boards[i]
                val latest = lib.pins.firstOrNull { it.boardId == board.id }
                PickerRow(board.name, selected = known && current == board.id, visual = { BoardThumb(latest) }) {
                    choose(board.id)
                    onDismiss()
                }
            }
        }
    }

    if (naming) {
        NameDialog("New board", "Create", onDismiss = { naming = false }) { name ->
            naming = false
            scope.launch {
                val newId = store.createBoard(name)
                choose(newId)
                onDismiss()
            }
        }
    }
}

@Composable
private fun PickerRow(label: String, selected: Boolean, visual: @Composable () -> Unit, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clickable(onClickLabel = label, onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        visual()
        Spacer(Modifier.width(14.dp))
        Text(
            label,
            style = Type.body,
            color = p.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) Icon(painterResource(R.drawable.ic_check), contentDescription = "Selected", tint = p.ink, modifier = Modifier.size(IconSize.medium))
    }
}

@Composable
private fun AddGlyph() {
    val p = LocalPalette.current
    Box(Modifier.size(44.dp).clip(Corners.small).background(p.fill), contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_plus), contentDescription = null, tint = p.ink2, modifier = Modifier.size(IconSize.medium))
    }
}

@Composable
private fun ClearGlyph() {
    val p = LocalPalette.current
    Box(Modifier.size(44.dp).clip(Corners.small).background(p.fill), contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_close), contentDescription = null, tint = p.ink3, modifier = Modifier.size(IconSize.small))
    }
}

@Composable
private fun BoardThumb(pin: Pin?) {
    val p = LocalPalette.current
    val store = LocalContext.current.store
    Box(Modifier.size(44.dp).clip(Corners.small).background(p.fill)) {
        if (pin != null) {
            AsyncImage(
                model = remember(pin.id) { store.thumb(pin) },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    confirm: String,
    initial: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val p = LocalPalette.current
    var name by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    PortlandDialog(
        title = title,
        confirmLabel = confirm,
        confirmEnabled = name.isNotBlank(),
        onConfirm = { onConfirm(name.trim()) },
        onDismiss = onDismiss,
    ) {
        BasicTextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            textStyle = Type.body.copy(color = p.ink),
            cursorBrush = SolidColor(p.ink),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            decorationBox = { field ->
                Box(Modifier.glass(null, Corners.small).padding(horizontal = 14.dp, vertical = 14.dp)) {
                    if (name.isEmpty()) Text("Name", style = Type.body, color = p.ink3)
                    field()
                }
            },
        )
    }
}
