package app.portland.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.webkit.MimeTypeMap
import androidx.annotation.OptIn
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
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
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
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
    val backdrop = rememberLayerBackdrop { drawRect(p.canvas); drawContent() }

    Box(Modifier.fillMaxSize().background(p.canvas)) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize().layerBackdrop(backdrop),
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

        // Keeps content from running under the status bar icons.
        Box(
            Modifier.fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        0f to p.canvas.copy(alpha = 0.96f),
                        0.6f to p.canvas.copy(alpha = 0.7f),
                        1f to p.canvas.copy(alpha = 0f),
                    ),
                )
                .statusBarsPadding()
                .height(64.dp),
        )

        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBtn(R.drawable.ic_back, "Back", backdrop = backdrop) { nav.pop() }
            Spacer(Modifier.weight(1f))
            IconBtn(R.drawable.ic_share, "Share", backdrop = backdrop) {
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
            Spacer(Modifier.width(8.dp))
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
                        text = { Text("Delete", style = Type.body, color = p.danger) },
                        leadingIcon = {
                            Icon(painterResource(R.drawable.ic_trash), null, tint = p.danger, modifier = Modifier.size(20.dp))
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
            .padding(horizontal = 16.dp)
            .padding(top = 64.dp),
    ) {
        MediaBox(pin, active)
        RevealText(pin.caption.orEmpty()) {
            Text(it, style = Type.body, color = p.ink2, modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 20.dp))
        }
        Spacer(Modifier.height(24.dp))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp)
                .glass(null, Corners.card)
                .clip(Corners.card)
                .clickable(role = Role.Button, onClickLabel = "Change board") { picker = true }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_bookmark), null, tint = p.ink, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
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
        Spacer(Modifier.height(12.dp))
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            textStyle = Type.body.copy(color = p.ink),
            cursorBrush = SolidColor(p.ink),
            modifier = Modifier.fillMaxWidth().glass(null, Corners.card).clip(Corners.card).heightIn(min = 104.dp).padding(16.dp),
            decorationBox = { field ->
                Box {
                    if (text.isEmpty()) Text("Add a note", style = Type.body, color = p.ink3)
                    field()
                }
            },
        )
        Text(
            "Saved ${savedDate(pin.createdAt)}",
            style = Type.caption,
            color = p.ink3,
            modifier = Modifier.padding(start = 4.dp, top = 12.dp),
        )
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
        val zoomBackdrop = rememberLayerBackdrop { drawRect(Color.Black); drawContent() }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            ZoomableAsyncImage(
                model = fullModel,
                contentDescription = desc,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().layerBackdrop(zoomBackdrop),
            )
            IconBtn(
                R.drawable.ic_close,
                "Close",
                Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(16.dp),
                tint = Color.White,
                backdrop = zoomBackdrop,
            ) { zoom = false }
        }
    }
}

private const val SeekStepMs = 10_000L
private const val SeekWindowMs = 700L // taps this soon after a seek keep adding to it
private const val HideAfterMs = 2_500L
private val ScrubPad = 16.dp // inset of the track from the video's edges

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
    // Per-frame position while it is moving by itself; a drag or seek writes it directly.
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
                        style = Type.caption.copy(fontWeight = FontWeight.SemiBold),
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
            modifier = Modifier.size(10.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "${formatDuration(seconds * 1000)} / ${formatDuration(duration)}",
            style = Type.caption,
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
