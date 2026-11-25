package app.portland.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.portland.R
import app.portland.data.Board
import app.portland.data.Library
import app.portland.data.Pin
import app.portland.store
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import kotlinx.coroutines.launch

@Composable
fun BoardsGrid(lib: Library, header: @Composable () -> Unit) {
    val p = LocalPalette.current
    val store = LocalContext.current.store
    val nav = LocalNav.current
    var naming by remember { mutableStateOf(false) }
    val byBoard = remember(lib.pins) { lib.pins.groupBy { it.boardId } }

    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = GridGutter, end = GridGutter, bottom = 120.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalItemSpacing = 20.dp,
    ) {
        item(key = "header", span = StaggeredGridItemSpan.FullLine, contentType = "header") { header() }
        item(key = "new", contentType = "new") {
            Column(Modifier.clickable(onClickLabel = "New board") { naming = true }) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(1f).clip(CardShape).background(p.fill),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.ic_plus), contentDescription = null, tint = p.ink2, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.height(8.dp))
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
    Column(Modifier.clickable(onClickLabel = board.name, onClick = onClick)) {
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
        Spacer(Modifier.height(8.dp))
        Text(
            board.name,
            style = Type.body.copy(fontWeight = FontWeight.Medium),
            color = p.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(if (pins.size == 1) "1 pin" else "${pins.size} pins", style = Type.caption, color = p.ink3)
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
    val pick = rememberLauncherForActivityResult(PickMultipleVisualMedia()) { uris -> store.launch { add(uris, id) } }
    val importing by store.importing.collectAsStateWithLifecycle()
    val selection = rememberPinSelection()

    LaunchedEffect(board) { if (board == null) nav.pop() }
    if (board == null) return

    Box(Modifier.fillMaxSize().background(p.canvas)) {
        PinGrid(
            pins = pins,
            onOpen = { i -> nav.push(Screen.Detail(pins.map { it.id }, i)) },
            selection = selection,
            loading = importing,
            header = {
                Column(Modifier.statusBarsPadding().padding(top = 8.dp, bottom = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBtn(R.drawable.ic_back, "Back") { nav.pop() }
                        Spacer(Modifier.weight(1f))
                        Box {
                            IconBtn(R.drawable.ic_more, "More") { menu = true }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Rename", style = Type.body, color = p.ink) },
                                    onClick = { menu = false; renaming = true },
                                )
                                DropdownMenuItem(
                                    text = { Text("Delete board", style = Type.body, color = p.ink) },
                                    onClick = { menu = false; confirming = true },
                                )
                            }
                        }
                    }
                    Text(
                        board.name,
                        style = Type.display,
                        color = p.ink,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                    Text(
                        if (pins.size == 1) "1 pin" else "${pins.size} pins",
                        style = Type.caption,
                        color = p.ink3,
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                    )
                    Spacer(Modifier.height(24.dp))
                    if (pins.isEmpty() && importing == 0) EmptyState("No pins yet", "Tap + to add images and videos to this board.")
                }
            },
        )
        // Keeps scrolled content from running under the status bar icons.
        Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(p.canvas.copy(alpha = 0.94f)))
        AddButton("Add images and videos", Modifier.align(Alignment.BottomEnd), visible = !selection.active) {
            pick.launch(PickVisualMediaRequest(PickVisualMedia.ImageAndVideo))
        }
        SelectionBar(selection)
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
            title = { Text("Delete this board?", style = Type.body.copy(fontWeight = FontWeight.Medium), color = p.ink) },
            text = { Text("Pins in it stay in your library.", style = Type.caption, color = p.ink2) },
            confirmButton = {
                TextButton({
                    confirming = false
                    nav.pop()
                    store.launch { deleteBoard(id) }
                }) { Text("Delete", style = Type.body.copy(fontWeight = FontWeight.Medium)) }
            },
            dismissButton = {
                TextButton({ confirming = false }) { Text("Cancel", style = Type.body) }
            },
        )
    }
}

/** Bottom sheet to choose a board for a pin; null = no board. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardPickerSheet(current: String?, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val haptics = LocalHapticFeedback.current
    // Filing a pin is a small commitment; a confirm tick says it landed.
    val choose: (String?) -> Unit = { haptics.performHapticFeedback(HapticFeedbackType.Confirm); onPick(it) }
    val store = LocalContext.current.store
    val scope = rememberCoroutineScope()
    val lib by store.library.collectAsStateWithLifecycle()
    var naming by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "Save to board",
            style = Type.body.copy(fontWeight = FontWeight.Medium),
            color = p.ink,
            modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp),
        )
        LazyColumn(Modifier.navigationBarsPadding()) {
            item(key = "new") {
                PickerRow("New board", selected = false, visual = { AddGlyph() }) { naming = true }
            }
            item(key = "none") {
                PickerRow("No board", selected = current == null, visual = { ClearGlyph() }) {
                    choose(null)
                    onDismiss()
                }
            }
            items(lib.boards.size, key = { lib.boards[it].id }) { i ->
                val board = lib.boards[i]
                val latest = lib.pins.firstOrNull { it.boardId == board.id }
                PickerRow(board.name, selected = current == board.id, visual = { BoardThumb(latest) }) {
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
            .heightIn(min = 56.dp)
            .clickable(onClickLabel = label, onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        visual()
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            style = Type.body,
            color = p.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) Icon(painterResource(R.drawable.ic_check), contentDescription = "Selected", tint = p.ink, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun AddGlyph() {
    val p = LocalPalette.current
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(p.fill), contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_plus), contentDescription = null, tint = p.ink2, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun ClearGlyph() {
    val p = LocalPalette.current
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(p.fill), contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_close), contentDescription = null, tint = p.ink3, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun BoardThumb(pin: Pin?) {
    val p = LocalPalette.current
    val store = LocalContext.current.store
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(p.fill)) {
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
        title = { Text(title, style = Type.body.copy(fontWeight = FontWeight.Medium), color = p.ink) },
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
                    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(p.fill).padding(12.dp)) {
                        if (name.isEmpty()) Text("Name", style = Type.body, color = p.ink3)
                        field()
                    }
                },
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name.trim()) }) {
                Text(confirm, style = Type.body.copy(fontWeight = FontWeight.Medium))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", style = Type.body) }
        },
    )
}
