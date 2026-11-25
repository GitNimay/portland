package app.portland.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.AtomicFile
import android.util.Log
import android.webkit.MimeTypeMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Single source of truth. The library is held in memory, persisted as one JSON file,
 * and mirrored to R2 by [Sync]. Media and thumbnails live in filesDir/{media,thumbs}.
 */
class Store(private val app: Context) {
    private val file = AtomicFile(File(app.filesDir, "library.json"))
    private val mediaDir = File(app.filesDir, "media").apply { mkdirs() }
    private val thumbDir = File(app.filesDir, "thumbs").apply { mkdirs() }
    private val lock = Mutex()
    private val state = MutableStateFlow(load())
    private val _importing = MutableStateFlow(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val library: StateFlow<Library> = state

    /** Runs a store operation that must outlive the screen that started it. */
    fun launch(block: suspend Store.() -> Unit) {
        scope.launch { block() }
    }

    /** Number of items currently being copied in. */
    val importing: StateFlow<Int> = _importing

    fun mediaFile(p: Pin) = File(mediaDir, "${p.id}.${p.ext}")
    fun thumbFile(p: Pin) = File(thumbDir, "${p.id}.webp")

    /** Image/video model for Coil or ExoPlayer: the local file when present, else the R2 object URL. */
    fun media(p: Pin): Any = mediaFile(p).takeIf { it.exists() } ?: R2.url(p.mediaKey)
    fun thumb(p: Pin): Any = thumbFile(p).takeIf { it.exists() } ?: R2.url(p.thumbKey)

    /** Applies [change] atomically, persists it and, unless [sync] is false, schedules a sync. */
    suspend fun update(sync: Boolean = true, change: (Library) -> Library) {
        val changed = lock.withLock {
            val next = change(state.value)
            if (next == state.value) return@withLock false
            state.value = next
            withContext(Dispatchers.IO) { save(next) }
            true
        }
        if (changed && sync) Sync.schedule(app)
    }

    suspend fun add(uris: List<Uri>, boardId: String?) {
        if (uris.isEmpty()) return
        _importing.update { it + uris.size }
        try {
            val now = System.currentTimeMillis()
            // Newest-first ordering by createdAt; the first picked item lands on top.
            val pins = withContext(Dispatchers.IO) {
                uris.mapIndexedNotNull { i, uri ->
                    runCatching { import(uri, boardId, now - i) }
                        .onFailure { Log.w("Store", "import failed: $uri", it) }
                        .getOrNull()
                }
            }
            if (pins.isNotEmpty()) update { it.copy(pins = pins + it.pins) }
        } finally {
            _importing.update { it - uris.size }
        }
    }

    suspend fun setNote(id: String, note: String) = update { lib ->
        lib.copy(pins = lib.pins.map { if (it.id == id && it.note != note) it.copy(note = note, updatedAt = System.currentTimeMillis()) else it })
    }

    suspend fun setBoard(ids: Collection<String>, boardId: String?) = update { lib ->
        val now = System.currentTimeMillis()
        lib.copy(pins = lib.pins.map { if (it.id in ids && it.boardId != boardId) it.copy(boardId = boardId, updatedAt = now) else it })
    }

    suspend fun deletePins(ids: Collection<String>) {
        val gone = state.value.pins.filter { it.id in ids }
        update { lib ->
            val now = System.currentTimeMillis()
            lib.copy(
                pins = lib.pins.filterNot { it.id in ids },
                deleted = lib.deleted + ids.associateWith { now },
                purge = lib.purge + gone.filter { it.synced }.flatMap { listOf(it.mediaKey, it.thumbKey) },
            )
        }
        withContext(Dispatchers.IO) { gone.forEach { mediaFile(it).delete(); thumbFile(it).delete() } }
    }

    /** Returns the new board's id. */
    suspend fun createBoard(name: String): String {
        val b = Board(UUID.randomUUID().toString(), name.trim(), System.currentTimeMillis())
        update { it.copy(boards = listOf(b) + it.boards) }
        return b.id
    }

    suspend fun renameBoard(id: String, name: String) = update { lib ->
        lib.copy(boards = lib.boards.map { if (it.id == id) it.copy(name = name.trim(), updatedAt = System.currentTimeMillis()) else it })
    }

    /** Deletes the board only; its pins stay in the library. */
    suspend fun deleteBoard(id: String) = update { lib ->
        val now = System.currentTimeMillis()
        lib.copy(
            boards = lib.boards.filterNot { it.id == id },
            pins = lib.pins.map { if (it.boardId == id) it.copy(boardId = null, updatedAt = now) else it },
            deleted = lib.deleted + (id to now),
        )
    }

    private fun load(): Library = runCatching {
        if (file.baseFile.exists()) Library.fromJson(file.readFully().decodeToString()) else Library()
    }.getOrElse {
        // Keep the unreadable file for inspection rather than overwriting it on the next save.
        Log.e("Store", "library.json unreadable, starting empty", it)
        file.baseFile.copyTo(File(app.filesDir, "library.broken.json"), overwrite = true)
        Library()
    }

    private fun save(lib: Library) {
        val out = file.startWrite()
        try {
            out.write(lib.toJson().encodeToByteArray())
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    private fun import(uri: Uri, boardId: String?, createdAt: Long): Pin {
        val cr = app.contentResolver
        val mime = cr.getType(uri).orEmpty()
        val video = mime.startsWith("video/")
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: if (video) "mp4" else "jpg"
        val id = UUID.randomUUID().toString()
        val media = File(mediaDir, "$id.$ext")
        val thumbFile = File(thumbDir, "$id.webp")
        try {
            checkNotNull(cr.openInputStream(uri)) { "no stream" }.use { i -> media.outputStream().use { i.copyTo(it) } }
            var duration = 0L
            val thumb = if (video) {
                MediaMetadataRetriever().use { r ->
                    r.setDataSource(media.path)
                    duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                    checkNotNull(r.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, THUMB_MAX, THUMB_MAX)) { "no frame" }
                }
            } else {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(media)) { d, info, _ ->
                    // Scale by the short side so the math holds before and after EXIF rotation.
                    val s = minOf(1f, THUMB_SHORT.toFloat() / minOf(info.size.width, info.size.height))
                    d.setTargetSize((info.size.width * s).toInt().coerceAtLeast(1), (info.size.height * s).toInt().coerceAtLeast(1))
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            }
            thumbFile.outputStream().use { thumb.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, it) }
            val pin = Pin(id, video, ext, thumb.width, thumb.height, duration, boardId = boardId, createdAt = createdAt)
            thumb.recycle()
            return pin
        } catch (e: Exception) {
            media.delete()
            thumbFile.delete()
            throw e
        }
    }

    private companion object {
        const val THUMB_SHORT = 560
        const val THUMB_MAX = 1120
    }
}
