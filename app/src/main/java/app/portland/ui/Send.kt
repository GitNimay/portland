package app.portland.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.portland.MainActivity
import app.portland.R
import app.portland.data.Pin
import app.portland.data.R2
import app.portland.store
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/**
 * The send dock: three icon-only buttons on a capsule of frosted glass (share, save to the gallery, copy), with
 * no header or captions, since the pin itself sits right above it. It rises from the bottom while a pin's
 * media is pushed up (a [MediaSwipe] in Send mode) and settles open on release, or opens from the Share
 * button. Each button answers in place: a spinner while the file is fetched, a check when it is done (then
 * the dock sinks away), a warning if it failed. Share hands the original file to the system share sheet.
 * Compose it last in the screen's Box so it sits over everything; it composes nothing while the dock is down.
 */
@Composable
fun BoxScope.SendShelf(swipe: MediaSwipe, pin: Pin?, backdrop: Backdrop?) {
    if (swipe.mode != SwipeMode.Send || pin == null) return
    val p = LocalPalette.current
    val context = LocalContext.current
    val haptics = LocalHaptics.current
    val scope = rememberCoroutineScope()
    var busy by remember(pin.id) { mutableStateOf<Action?>(null) }
    var done by remember(pin.id) { mutableStateOf<Action?>(null) }
    var failed by remember(pin.id) { mutableStateOf<Action?>(null) }
    val open = swipe.shelfOpen

    BackHandler(open) { swipe.closeShelf() }

    fun run(action: Action) {
        if (busy != null || done != null) return
        busy = action
        failed = null
        scope.launch {
            try {
                val file = shareableFile(context, pin)
                when (action) {
                    Action.Send -> context.startActivity(shareIntent(context, file, pin))
                    Action.Save -> saveToGallery(context, file, pin)
                    Action.Copy -> copyToClipboard(context, file, pin)
                }
                busy = null
                if (action != Action.Send) {
                    // The check is the confirmation: no toast. Let it land before the dock sinks.
                    done = action
                    haptics.play(Fx.Confirm)
                    delay(650)
                }
                swipe.closeShelf()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                busy = null
                failed = action
                haptics.play(Fx.Reject)
                // A failure is the one thing worth words: say what went wrong.
                toast(context, if (R2.enabled) "Couldn't get this pin. Check your connection." else "This pin's file is missing.")
                delay(1400)
                if (failed == action) failed = null
            }
        }
    }

    // Scrim: dims with the rise; once open, a tap closes and a drag moves the dock, as on the dock itself.
    Box(
        Modifier
            .matchParentSize()
            .graphicsLayer { alpha = swipe.sendProgress }
            .background(p.scrim)
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
            .padding(bottom = 24.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Row(
            Modifier
                .glass(backdrop, Corners.pill, frost = 1f)
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val actions = if (pin.video) listOf(Action.Send, Action.Save) else listOf(Action.Send, Action.Save, Action.Copy)
            actions.forEach { action ->
                DockButton(
                    action = action,
                    state = when (action) {
                        busy -> DockState.Busy
                        done -> DockState.Done
                        failed -> DockState.Failed
                        else -> DockState.Idle
                    },
                    enabled = open && busy == null && done == null,
                ) { run(action) }
            }
        }
    }
}

private enum class Action(@DrawableRes val icon: Int, val label: String) {
    Send(R.drawable.ic_share, "Share"),
    Save(R.drawable.ic_download, "Save to gallery"),
    Copy(R.drawable.ic_copy, "Copy image"),
}

private enum class DockState { Idle, Busy, Done, Failed }

/** One round button of the dock. Its icon gives way to a spinner, a check or a warning, each springing in. */
@Composable
private fun DockButton(action: Action, state: DockState, enabled: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    GlassIcon(action.icon, action.label, size = 56.dp, fill = p.fill, enabled = enabled, onClick = onClick, content = {
        AnimatedContent(
            targetState = state,
            transitionSpec = {
                (fadeIn(tween(160)) + scaleIn(Motion.bouncy(), initialScale = 0.5f)) togetherWith
                    (fadeOut(tween(100)) + scaleOut(Motion.dismiss(), targetScale = 0.5f)) using null
            },
            contentAlignment = Alignment.Center,
            label = "dock",
        ) { s ->
            when (s) {
                DockState.Idle -> Icon(painterResource(action.icon), contentDescription = action.label, tint = p.ink, modifier = Modifier.size(IconSize.large))
                DockState.Busy -> CircularProgressIndicator(
                    color = p.ink,
                    trackColor = Color.Transparent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(IconSize.medium).semantics { contentDescription = "Working" },
                )
                DockState.Done -> Icon(painterResource(R.drawable.ic_check), contentDescription = "Done", tint = p.ink, modifier = Modifier.size(IconSize.large))
                DockState.Failed -> Icon(painterResource(R.drawable.ic_warning), contentDescription = "Failed", tint = p.danger, modifier = Modifier.size(IconSize.large))
            }
        }
    })
}

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
            res.body.byteStream().use { input -> part.outputStream().buffered().use { input.copyTo(it) } }
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
        .setType(pin.mimeType)
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
        put(MediaStore.MediaColumns.MIME_TYPE, pin.mimeType)
        put(MediaStore.MediaColumns.RELATIVE_PATH, if (pin.video) "Movies/Portland" else "Pictures/Portland")
        put(MediaStore.MediaColumns.DATE_TAKEN, pin.createdAt)
        put(MediaStore.MediaColumns.IS_PENDING, 1) // hidden from other apps until it is fully written
    }
    val uri = resolver.insert(collection, values) ?: error("gallery insert failed")
    try {
        val out = resolver.openOutputStream(uri) ?: error("gallery stream unavailable")
        out.use { stream -> file.inputStream().buffered().use { it.copyTo(stream) } }
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
