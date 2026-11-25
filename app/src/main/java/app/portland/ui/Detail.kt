package app.portland.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
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
import app.portland.R
import app.portland.data.Pin
import app.portland.data.R2
import app.portland.store
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import okhttp3.Request
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun DetailScreen(ids: List<String>, start: Int) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val store = context.store
    val nav = LocalNav.current
    val lib by store.library.collectAsStateWithLifecycle()
    val pins = remember(ids, lib.pins) {
        val byId = lib.pins.associateBy { it.id }
        ids.mapNotNull(byId::get)
    }

    if (pins.isEmpty()) {
        LaunchedEffect(ids) { nav.pop() }
        Box(Modifier.fillMaxSize().background(p.canvas))
        return
    }

    val pager = rememberPagerState(initialPage = start.coerceIn(0, pins.lastIndex)) { pins.size }
    LaunchedEffect(pins.size) { if (pager.currentPage > pins.lastIndex) pager.scrollToPage(pins.lastIndex) }
    val current = pins.getOrNull(pager.currentPage) ?: pins.first()
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(p.canvas)) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
            key = { pins[it].id },
        ) { page ->
            val pin = pins[page]
            PinPage(
                pin = pin,
                boardName = lib.boards.firstOrNull { it.id == pin.boardId }?.name,
                active = pager.settledPage == page,
            )
        }

        Row(
            Modifier.fillMaxWidth().background(p.canvas).statusBarsPadding().padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBtn(R.drawable.ic_back, "Back") { nav.pop() }
            Spacer(Modifier.weight(1f))
            IconBtn(R.drawable.ic_share, "Share") {
                val pin = current
                store.launch {
                    val local = mediaFile(pin)
                    val file = if (local.exists()) local else {
                        val out = File(File(context.cacheDir, "shared").apply { mkdirs() }, "${pin.id}.${pin.ext}")
                        withContext(Dispatchers.IO) {
                            R2.http.newCall(Request.Builder().url(R2.url(pin.mediaKey)).build()).execute().use { res ->
                                check(res.isSuccessful) { "share download failed: ${res.code}" }
                                res.body.byteStream().use { input -> out.outputStream().use { input.copyTo(it) } }
                            }
                        }
                        out
                    }
                    shareFile(context, file, pin)
                }
            }
            Box {
                IconBtn(R.drawable.ic_more, "More") { menu = true }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Delete", style = Type.body, color = p.ink) },
                        leadingIcon = {
                            Icon(painterResource(R.drawable.ic_trash), null, tint = p.ink, modifier = Modifier.size(20.dp))
                        },
                        onClick = { menu = false; confirm = true },
                    )
                }
            }
        }
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

@Composable
private fun PinPage(pin: Pin, boardName: String?, active: Boolean) {
    val p = LocalPalette.current
    val store = LocalContext.current.store
    var text by remember(pin.id) { mutableStateOf(pin.note) }
    var picker by remember(pin.id) { mutableStateOf(false) }
    val latest = rememberUpdatedState(text)

    // Debounced autosave while typing; the dispose hook catches a quick back-out.
    LaunchedEffect(text) {
        if (text == pin.note) return@LaunchedEffect
        delay(600)
        store.launch { setNote(pin.id, text) }
    }
    DisposableEffect(pin.id) {
        onDispose { store.launch { setNote(pin.id, latest.value) } }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp)
            .padding(top = 52.dp),
    ) {
        MediaBox(pin, active)
        RevealText(pin.caption.orEmpty()) {
            Text(it, style = Type.body, color = p.ink2, modifier = Modifier.padding(top = 16.dp))
        }
        Spacer(Modifier.height(20.dp))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClickLabel = "Change board") { picker = true },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_bookmark), null, tint = p.ink, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                boardName ?: "No board",
                style = Type.body.copy(fontWeight = FontWeight.Medium),
                color = if (boardName != null) p.ink else p.ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(painterResource(R.drawable.ic_chevron_down), null, tint = p.ink3, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.line))
        Spacer(Modifier.height(16.dp))
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            textStyle = Type.body.copy(color = p.ink),
            cursorBrush = SolidColor(p.ink),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { field ->
                Box {
                    if (text.isEmpty()) Text("Add a note", style = Type.body, color = p.ink3)
                    field()
                }
            },
        )
        Spacer(Modifier.height(12.dp))
        Text("Saved ${savedDate(pin.createdAt)}", style = Type.caption, color = p.ink3)
        Spacer(Modifier.height(48.dp))
    }

    if (picker) BoardPickerSheet(
        current = pin.boardId,
        onPick = { id ->
            picker = false
            store.launch { setBoard(listOf(pin.id), id) }
        },
        onDismiss = { picker = false },
    )
}

@Composable
private fun MediaBox(pin: Pin, active: Boolean) {
    val p = LocalPalette.current
    val store = LocalContext.current.store
    val thumbModel = remember(pin.id) { store.thumb(pin) }
    val fullModel = remember(pin.id) { store.media(pin) }
    val desc = pin.note.ifBlank { null }
    var zoom by remember(pin.id) { mutableStateOf(false) }
    var loaded by remember(pin.id) { mutableStateOf(false) }
    val ratio = pin.w.toFloat() / pin.h.coerceAtLeast(1)

    Box(
        Modifier
            .sharedPin(pin.id)
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(20.dp))
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
            VideoLayer(pin, active, Modifier.matchParentSize())
        } else {
            AsyncImage(
                model = fullModel,
                contentDescription = desc,
                contentScale = ContentScale.Fit,
                modifier = Modifier.matchParentSize().clickable(onClickLabel = "Open image") { zoom = true },
            )
        }
    }

    if (zoom) Dialog(
        onDismissRequest = { zoom = false },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            ZoomableAsyncImage(
                model = fullModel,
                contentDescription = desc,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            IconBtn(
                R.drawable.ic_close,
                "Close",
                Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp),
                tint = Color.White,
            ) { zoom = false }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoLayer(pin: Pin, active: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = context.store
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val haptics = LocalHapticFeedback.current
    val holder = remember(pin.id) { mutableStateOf<ExoPlayer?>(null) }
    var playing by remember(pin.id) { mutableStateOf(true) }
    var duration by remember(pin.id) { mutableLongStateOf(pin.durationMs) }
    var position by remember(pin.id) { mutableLongStateOf(0L) }

    DisposableEffect(pin.id, active) {
        if (!active) return@DisposableEffect onDispose {}
        val exo = ExoPlayer.Builder(context).build()
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) duration = exo.duration.coerceAtLeast(0L)
            }
        }
        exo.addListener(listener)
        exo.repeatMode = Player.REPEAT_MODE_ONE
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

        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) exo.pause()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            holder.value = null
            exo.release()
        }
    }

    val exo = holder.value
    LaunchedEffect(exo, playing) {
        while (exo != null && playing) {
            position = exo.currentPosition
            delay(250)
        }
    }

    Box(modifier) {
        if (exo != null) {
            // TextureView keeps the Compose controls above the video and shows the thumb until frames arrive.
            PlayerSurface(exo, Modifier.matchParentSize(), SURFACE_TYPE_TEXTURE_VIEW)
            Box(
                Modifier.matchParentSize().clickable(onClickLabel = if (playing) "Pause" else "Play") {
                    haptics.performHapticFeedback(if (exo.isPlaying) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                    if (exo.isPlaying) exo.pause() else exo.play()
                },
            )
            if (duration > 0) {
                Row(
                    Modifier.align(Alignment.BottomStart).padding(10.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.44f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play),
                        contentDescription = if (playing) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(10.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${formatDuration(position.coerceIn(0L, duration))} / ${formatDuration(duration)}",
                        style = Type.caption,
                        color = Color.White,
                    )
                }
            }
        }
    }
}

private fun savedDate(ms: Long): String =
    DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())
        .format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

private fun shareFile(context: Context, file: File, pin: Pin) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(pin.ext)
        ?: if (pin.video) "video/*" else "image/*"
    val send = Intent(Intent.ACTION_SEND)
        .setType(mime)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Share"))
}
