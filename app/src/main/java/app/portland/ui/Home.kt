package app.portland.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.portland.R
import app.portland.data.Library
import app.portland.data.R2
import app.portland.store

@Composable
fun HomeScreen() {
    val p = LocalPalette.current
    val store = LocalContext.current.store
    val nav = LocalNav.current
    val lib by store.library.collectAsStateWithLifecycle()
    val importing by store.importing.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) } // 0 = pins, 1 = boards
    var query by rememberSaveable { mutableStateOf<String?>(null) } // null = search closed
    val pick = rememberLauncherForActivityResult(PickMultipleVisualMedia()) { uris -> store.launch { add(uris, null) } }

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

    val header: @Composable () -> Unit = {
        Header(
            lib = lib,
            importing = importing,
            matches = if (query.isNullOrBlank()) null else pins.size,
            query = query,
            onQuery = { query = it },
            tab = if (query != null) 0 else tab,
            onTab = { tab = it },
        )
    }

    BackHandler(query != null) { query = null }

    Box(Modifier.fillMaxSize().background(p.canvas)) {
        if (tab == 1 && query == null) {
            BoardsGrid(lib, header = header)
        } else {
            PinGrid(
                pins = pins,
                onOpen = { i -> nav.push(Screen.Detail(pins.map { it.id }, i)) },
                header = {
                    Column {
                        header()
                        when {
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
        // Keeps scrolled content from running under the status bar icons.
        Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(p.canvas.copy(alpha = 0.94f)))
        AddButton(
            "Add images and videos",
            Modifier.align(Alignment.BottomEnd),
        ) { pick.launch(PickVisualMediaRequest(PickVisualMedia.ImageAndVideo)) }
    }
}

@Composable
private fun Header(
    lib: Library,
    importing: Int,
    matches: Int?,
    query: String?,
    onQuery: (String?) -> Unit,
    tab: Int,
    onTab: (Int) -> Unit,
) {
    val p = LocalPalette.current
    Column(Modifier.statusBarsPadding().padding(start = 4.dp, top = 20.dp, bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (query == null) {
                Text("Portland", style = Type.display, color = p.ink, modifier = Modifier.weight(1f))
                IconBtn(R.drawable.ic_search, "Search") { onQuery("") }
            } else {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                BasicTextField(
                    value = query,
                    onValueChange = { onQuery(it) },
                    singleLine = true,
                    textStyle = Type.display.copy(color = p.ink),
                    cursorBrush = SolidColor(p.ink),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    decorationBox = { field ->
                        Box {
                            if (query.isEmpty()) Text("Search", style = Type.display, color = p.ink3)
                            field()
                        }
                    },
                )
                IconBtn(R.drawable.ic_close, "Close search") { onQuery(null) }
            }
        }
        Text(matches?.let { if (it == 1) "1 match" else "$it matches" } ?: status(lib, importing), style = Type.caption, color = p.ink3)
        if (query == null) {
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                listOf("Pins", "Boards").forEachIndexed { i, label ->
                    Text(
                        label,
                        style = Type.body.copy(fontWeight = FontWeight.Medium),
                        color = if (i == tab) p.ink else p.ink3,
                        modifier = Modifier.clickable(role = Role.Tab, onClickLabel = label) { onTab(i) }.padding(vertical = 6.dp),
                    )
                }
            }
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
