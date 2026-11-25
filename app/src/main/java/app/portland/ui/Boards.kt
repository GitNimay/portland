package app.portland.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
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
                    Icon(painterResource(R.drawable.ic_plus), contentDescription = null, tint = p.ink2, modifier = Modifier.size(24.dp))
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
            style = Type.body.copy(fontWeight = FontWeight.Medium),
            color = p.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            if (pins.size == 1) "1 pin" else "${pins.size} pins",
            style = Type.caption,
            color = p.ink3,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun CoverCell(pin: Pin?, modifier: Modifier) {
    val store = LocalContext.current.store
    Box(modifier) {
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
    val pick = rememberLauncherForActivityResult(PickMultipleVisualMedia()) { uris -> store.launch { add(uris, id) } }
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
            state = grid,
            header = {
                Column {
                    Spacer(Modifier.height(with(density) { headerHeight.toDp() }))
                    if (pins.isEmpty()) EmptyState("No pins yet", "Tap + to add images and videos to this board.")
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
                    style = Type.body.copy(fontWeight = FontWeight.SemiBold),
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
                            onClick = { menu = false; renaming = true },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete board", style = Type.body, color = p.danger) },
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
                    style = Type.display,
                    color = p.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (pins.size == 1) "1 pin" else "${pins.size} pins",
                    style = Type.caption,
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
        AddButton("Add images and videos", Modifier.align(Alignment.BottomEnd), backdrop = backdrop) {
            pick.launch(PickVisualMediaRequest(PickVisualMedia.ImageAndVideo))
        }
    }

    if (renaming) {
        NameDialog("Rename board", "Save", initial = board.name, onDismiss = { renaming = false }) { name ->
            renaming = false
            store.launch { renameBoard(id, name) }
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            shape = Corners.sheet,
            containerColor = frostedSheet(),
            tonalElevation = 0.dp,
            title = { FrostBehind(); Text("Delete this board?", style = Type.body.copy(fontWeight = FontWeight.SemiBold), color = p.ink) },
            text = { Text("Pins in it stay in your library.", style = Type.caption, color = p.ink2) },
            confirmButton = {
                TextButton({
                    confirming = false
                    nav.pop()
                    store.launch { deleteBoard(id) }
                }) { Text("Delete", style = Type.body.copy(fontWeight = FontWeight.SemiBold), color = p.danger) }
            },
            dismissButton = {
                TextButton({ confirming = false }) { Text("Cancel", style = Type.body, color = p.ink) }
            },
        )
    }
}

/** Bottom sheet to choose a board for a pin; null = no board. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardPickerSheet(current: String?, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val store = LocalContext.current.store
    val scope = rememberCoroutineScope()
    val lib by store.library.collectAsStateWithLifecycle()
    var naming by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = frostedSheet(),
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        dragHandle = {
            BottomSheetDefaults.DragHandle(width = 36.dp, height = 5.dp, color = p.ink3.copy(alpha = 0.5f))
        },
    ) {
        FrostBehind()
        Text(
            "Save to board",
            style = Type.body.copy(fontWeight = FontWeight.SemiBold),
            color = p.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 12.dp),
        )
        LazyColumn(Modifier.navigationBarsPadding(), contentPadding = PaddingValues(bottom = 12.dp)) {
            item(key = "new") {
                PickerRow("New board", selected = false, visual = { AddGlyph() }) { naming = true }
            }
            item(key = "none") {
                PickerRow("No board", selected = current == null, visual = { ClearGlyph() }) {
                    onPick(null)
                    onDismiss()
                }
            }
            items(lib.boards.size, key = { lib.boards[it].id }) { i ->
                val board = lib.boards[i]
                val latest = lib.pins.firstOrNull { it.boardId == board.id }
                PickerRow(board.name, selected = current == board.id, visual = { BoardThumb(latest) }) {
                    onPick(board.id)
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
                onPick(newId)
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
        if (selected) Icon(painterResource(R.drawable.ic_check), contentDescription = "Selected", tint = p.ink, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun AddGlyph() {
    val p = LocalPalette.current
    Box(Modifier.size(44.dp).clip(Corners.small).background(p.fill), contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_plus), contentDescription = null, tint = p.ink2, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun ClearGlyph() {
    val p = LocalPalette.current
    Box(Modifier.size(44.dp).clip(Corners.small).background(p.fill), contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_close), contentDescription = null, tint = p.ink3, modifier = Modifier.size(18.dp))
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

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = Corners.sheet,
        containerColor = frostedSheet(),
        tonalElevation = 0.dp,
        title = { FrostBehind(); Text(title, style = Type.body.copy(fontWeight = FontWeight.SemiBold), color = p.ink) },
        text = {
            BasicTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                textStyle = Type.body.copy(color = p.ink),
                cursorBrush = SolidColor(p.ink),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) onConfirm(name.trim()) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                decorationBox = { field ->
                    Box(Modifier.glass(null, Corners.small).padding(horizontal = 14.dp, vertical = 12.dp)) {
                        if (name.isEmpty()) Text("Name", style = Type.body, color = p.ink3)
                        field()
                    }
                },
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name.trim()) }) {
                Text(confirm, style = Type.body.copy(fontWeight = FontWeight.SemiBold), color = p.ink)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", style = Type.body, color = p.ink2) }
        },
    )
}
