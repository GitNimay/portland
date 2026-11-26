package app.portland.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.portland.MainActivity
import app.portland.R
import app.portland.data.Pin
import app.portland.data.R2
import app.portland.store
import coil3.compose.AsyncImage
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/**
 * The send shelf: rises from the bottom while a pin's media is pushed up (a [MediaSwipe] in Send mode) and
 * settles open on release, or opens from the Share button. "Send…" hands the original file to the system
 * share sheet; saving to the gallery and copying stay one tap away. Compose it last in the screen's Box so
 * it sits over everything; it composes nothing while the shelf is down.
 */
@Composable
fun BoxScope.SendShelf(swipe: MediaSwipe, pin: Pin?, backdrop: Backdrop?) {
    if (swipe.mode != SwipeMode.Send || pin == null) return
    val p = LocalPalette.current
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var busy by remember(pin.id) { mutableStateOf<Action?>(null) }
    val open = swipe.shelfOpen

    BackHandler(open) { swipe.closeShelf() }

    fun run(action: Action) {
        if (busy != null) return
        busy = action
        scope.launch {
            try {
                val file = shareableFile(context, pin)
                when (action) {
                    Action.Send -> context.startActivity(shareIntent(context, file, pin))
                    Action.Save -> {
                        saveToGallery(context, file, pin)
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        toast(context, if (pin.video) "Video saved to your gallery" else "Image saved to your gallery")
                    }
                    Action.Copy -> {
                        copyToClipboard(context, file, pin)
                        // Android 13+ confirms a copy itself.
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) toast(context, "Image copied")
                    }
                }
                swipe.closeShelf()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                haptics.performHapticFeedback(HapticFeedbackType.Reject)
                toast(context, if (R2.enabled) "Couldn't get this pin. Check your connection." else "This pin's file is missing.")
            } finally {
                busy = null
            }
        }
    }

    // Scrim: dims with the rise; once open, a tap closes and a drag moves the shelf, as on the shelf itself.
    Box(
        Modifier
            .matchParentSize()
            .graphicsLayer { alpha = swipe.sendProgress }
            .background(Color.Black.copy(alpha = if (p.dark) 0.45f else 0.22f))
            .then(
                if (open) {
                    Modifier
                        .shelfSwipe(swipe)
                        .clickable(interactionSource = null, indication = null, onClickLabel = "Close") { swipe.closeShelf() }
                } else {
                    Modifier
                },
            ),
    )

    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .onSizeChanged { swipe.shelfPx = it.height.toFloat() }
            // Parked just below the screen, it rises exactly as far as the finger has pushed.
            .graphicsLayer { translationY = size.height - swipe.shelfRise }
            .shelfSwipe(swipe)
            .navigationBarsPadding()
            .padding(12.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                // Denser film than the add panel: the shelf sits over text (caption, note) that would ghost through.
                .glass(backdrop, Corners.sheet, tint = p.sheet.copy(alpha = if (p.dark) 0.92f else 0.94f))
                .clip(Corners.sheet)
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
        ) {
            ShelfHeader(pin, enabled = open) { swipe.closeShelf() }
            Spacer(Modifier.height(14.dp))
            Option(
                R.drawable.ic_share,
                "Send…",
                if (busy == Action.Send) "Getting it ready…" else "To an app or a person",
                enabled = open && busy == null,
            ) { run(Action.Send) }
            Spacer(Modifier.height(10.dp))
            Option(
                R.drawable.ic_download,
                "Save to gallery",
                if (busy == Action.Save) "Saving…" else if (pin.video) "Adds a copy to Movies/Portland" else "Adds a copy to Pictures/Portland",
                enabled = open && busy == null,
            ) { run(Action.Save) }
            if (!pin.video) {
                Spacer(Modifier.height(10.dp))
                Option(
                    R.drawable.ic_copy,
                    "Copy image",
                    if (busy == Action.Copy) "Copying…" else "Paste it into any app",
                    enabled = open && busy == null,
                ) { run(Action.Copy) }
            }
        }
    }
}

private enum class Action { Send, Save, Copy }

@Composable
private fun ShelfHeader(pin: Pin, enabled: Boolean, onClose: () -> Unit) {
    val p = LocalPalette.current
    val store = LocalContext.current.store
    Row(verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(
            model = remember(pin.id) { store.thumb(pin) },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(44.dp).clip(Corners.small).background(p.fill),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                pin.note.ifBlank { pin.caption.orEmpty() }.ifBlank { if (pin.video) "Video" else "Image" },
                style = Type.body.copy(fontWeight = FontWeight.SemiBold),
                color = p.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(pin.ext.uppercase())
                    if (pin.w > 0 && pin.h > 0) append(" · ${pin.w}×${pin.h}")
                    if (pin.video && pin.durationMs > 0) append(" · ${formatDuration(pin.durationMs)}")
                },
                style = Type.caption,
                color = p.ink3,
            )
        }
        Box(
            Modifier
                .padding(start = 8.dp)
                .size(32.dp)
                .clip(CircleShape)
                .background(p.fill)
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = "Close", onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_close), contentDescription = "Close", tint = p.ink2, modifier = Modifier.size(16.dp))
        }
    }
}

private fun mime(pin: Pin): String =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(pin.ext.lowercase())
        ?: if (pin.video) "video/mp4" else "image/jpeg"

/** [pin]'s original file: the local copy, else downloaded once from R2 into the cache. */
private suspend fun shareableFile(context: Context, pin: Pin): File = withContext(Dispatchers.IO) {
    val local = context.store.mediaFile(pin)
    if (local.exists()) return@withContext local
    val out = File(File(context.cacheDir, "shared").apply { mkdirs() }, "${pin.id}.${pin.ext}")
    if (out.length() > 0) return@withContext out
    // Written aside and renamed, so an interrupted download never passes for a whole file.
    val part = File(out.path + ".part")
    try {
        R2.http.newCall(Request.Builder().url(R2.url(pin.mediaKey)).build()).execute().use { res ->
            check(res.isSuccessful) { "download failed: ${res.code}" }
            res.body.byteStream().use { input -> part.outputStream().use { input.copyTo(it) } }
        }
        check(part.renameTo(out)) { "could not keep the download" }
    } finally {
        part.delete()
    }
    out
}

private fun uriFor(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.files", file)

/** The system share sheet for [file], with its preview, and without Portland's own "Save to Portland". */
private fun shareIntent(context: Context, file: File, pin: Pin): Intent {
    val uri = uriFor(context, file)
    val send = Intent(Intent.ACTION_SEND)
        .setType(mime(pin))
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // ClipData is what the sheet previews, and it carries the read grant to whichever app is picked.
    send.clipData = ClipData.newUri(context.contentResolver, if (pin.video) "Video" else "Image", uri)
    return Intent.createChooser(send, null)
        .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(context, MainActivity::class.java)))
}

/** Adds a copy to the shared gallery (Pictures/Portland or Movies/Portland). Needs no permission on API 29+. */
private suspend fun saveToGallery(context: Context, file: File, pin: Pin) = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val collection = if (pin.video) {
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else {
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    }
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "Portland ${pin.id}.${pin.ext}")
        put(MediaStore.MediaColumns.MIME_TYPE, mime(pin))
        put(MediaStore.MediaColumns.RELATIVE_PATH, if (pin.video) "Movies/Portland" else "Pictures/Portland")
        put(MediaStore.MediaColumns.DATE_TAKEN, pin.createdAt)
        put(MediaStore.MediaColumns.IS_PENDING, 1) // hidden from other apps until it is fully written
    }
    val uri = resolver.insert(collection, values) ?: error("gallery insert failed")
    try {
        val out = resolver.openOutputStream(uri) ?: error("gallery stream unavailable")
        out.use { stream -> file.inputStream().use { it.copyTo(stream) } }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    } catch (e: Throwable) {
        resolver.delete(uri, null, null) // never leave a half-written, pending entry behind
        throw e
    }
}

private fun copyToClipboard(context: Context, file: File, pin: Pin) {
    val clip = ClipData.newUri(context.contentResolver, if (pin.video) "Video" else "Image", uriFor(context, file))
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
}

private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
