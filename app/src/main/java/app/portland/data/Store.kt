package app.portland.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.AtomicFile
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.compose.runtime.Stable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Single source of truth. The library is held in memory, persisted as one JSON file,
 * and mirrored to R2 by [Sync]. Media and thumbnails live in filesDir/{media,thumbs}.
 */
@Stable
class Store(private val app: Context) {
    private val file = AtomicFile(File(app.filesDir, "library.json"))
    private val mediaDir = File(app.filesDir, "media").apply { mkdirs() }
    private val thumbDir = File(app.filesDir, "thumbs").apply { mkdirs() }
    private val lock = Mutex()
    private val state = MutableStateFlow(load())
    private val _importing = MutableStateFlow(0)
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, e -> Log.e("Store", "operation failed", e) },
    )

    val library: StateFlow<Library> = state

    /** Runs a store operation that must outlive the screen that started it. */
    fun launch(block: suspend Store.() -> Unit) {
        scope.launch { block() }
    }

    /** Number of items on their way in: being copied in, or behind a pasted link that is still downloading. */
    val importing: StateFlow<Int> = combine(
        _importing,
        // Initialize WorkManager off the main thread so Application.onCreate() stays fast.
        flow { emitAll(withContext(Dispatchers.IO) { Links.pending(app) }) },
    ) { copying, links -> copying + links }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    fun mediaFile(p: Pin) = File(mediaDir, "${p.id}.${p.ext}")
    fun thumbFile(p: Pin) = File(thumbDir, "${p.id}.webp")

    /** Image/video model for Coil or ExoPlayer: the local file when present, else the R2 object URL. */
    fun media(p: Pin): Any = models.getOrPut("m${p.id}") { mediaFile(p).takeIf { it.exists() } ?: R2.url(p.mediaKey) }
    fun thumb(p: Pin): Any = models.getOrPut("t${p.id}") { thumbFile(p).takeIf { it.exists() } ?: R2.url(p.thumbKey) }

    // The UI asks for these on every composition; checking the disk each time is main-thread IO. A pin's files
    // only change when it is created (before it is listed) or deleted, and both paths reset its entries.
    private val models = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private fun forget(id: String) { models.remove("m$id"); models.remove("t$id") }

    /** Applies [change] atomically, persists it and, unless [sync] is false, schedules a sync. */
    suspend fun update(sync: Boolean = true, change: (Library) -> Library) {
        // The state lock covers only the in-memory swap, so the next change never waits behind a disk write.
        val mine = lock.withLock {
            val next = change(state.value)
            if (next == state.value) return@withLock 0L
            state.value = next
            version.incrementAndGet() // after the swap: a save that reads this version also reads this state
        }
        if (mine == 0L) return
        persist(mine)
        if (sync) Sync.schedule(app)
    }

    // Group commit: saves run one at a time and always write the newest state, so changes that pile up while a
    // save is in flight (a burst of captions, an upload pass) share the next one instead of rewriting the file each.
    private val saveLock = Mutex()
    private val version = AtomicLong()
    private var savedVersion = 0L // guarded by saveLock

    /** Returns once [mine] (a version from [update]) is on disk, or a save has been tried and failed. */
    private suspend fun persist(mine: Long) = saveLock.withLock {
        if (savedVersion >= mine) return@withLock // a save that began after this change already wrote it
        val covered = version.get() // read before the state: the save below includes at least this much
        val lib = state.value
        // A full disk must not kill the caller; the change stays in memory and the next save retries.
        val ok = withContext(Dispatchers.IO) {
            try {
                save(lib)
                true
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                Log.e("Store", "save failed", e)
                false
            }
        }
        if (ok) savedVersion = covered
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
            if (pins.isNotEmpty()) {
                update { it.copy(pins = pins + it.pins) }
                Captions.schedule(app)
            }
        } finally {
            _importing.update { it - uris.size }
        }
    }

    suspend fun setNote(id: String, note: String) = update { lib ->
        lib.copy(pins = lib.pins.map { if (it.id == id && it.note != note) it.copy(note = note, updatedAt = System.currentTimeMillis()) else it })
    }

    /** Sets the AI description once; a pin that already has one keeps it. */
    suspend fun setCaption(id: String, caption: String) = update { lib ->
        lib.copy(pins = lib.pins.map { if (it.id == id && it.caption == null) it.copy(caption = caption, updatedAt = System.currentTimeMillis()) else it })
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
        gone.forEach { forget(it.id) }
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
        runCatching { file.baseFile.copyTo(File(app.filesDir, "library.broken.json"), overwrite = true) }
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

    /**
     * Adds a file downloaded elsewhere (a pasted link) as a new pin on top. The file is moved, not copied.
     * Throws when it is not a readable image or video.
     */
    suspend fun addFile(file: File, mime: String, boardId: String?, cover: File? = null) {
        val pin = withContext(Dispatchers.IO) {
            import(mime, boardId, System.currentTimeMillis(), cover) { media ->
                if (!file.renameTo(media)) file.copyTo(media, overwrite = true).also { file.delete() }
            }
        }
        update { it.copy(pins = listOf(pin) + it.pins) }
        Captions.schedule(app)
    }

    // One pass at a time: the app start and every sync both ask.
    private val colorLock = Mutex()

    /** Tags pins that have no colours yet (older pins, pins from another install). Safe to call any time. */
    suspend fun indexColors() = colorLock.withLock {
        val todo = state.value.pins.filter { it.colors == null }
        if (todo.isEmpty()) return@withLock
        val found = withContext(Dispatchers.IO) {
            todo.mapNotNull { pin ->
                runCatching {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                    val local = thumbFile(pin).takeIf { it.exists() }
                    val bmp = if (local != null) {
                        BitmapFactory.decodeFile(local.path, opts)
                    } else {
                        val bytes = (if (R2.enabled && pin.synced) R2.get(pin.thumbKey) else null)
                            ?: return@mapNotNull null // not available yet; a later pass picks it up
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                    }
                    checkNotNull(bmp) { "undecodable" }
                    pin.id to colorsOf(bmp).also { bmp.recycle() }
                }.onFailure { Log.w("Store", "colours failed for ${pin.id}", it) }.getOrNull()
            }.toMap()
        }
        if (found.isNotEmpty()) update(sync = false) { lib ->
            lib.copy(pins = lib.pins.map { p -> found[p.id]?.takeIf { p.colors == null }?.let { p.copy(colors = it) } ?: p })
        }
    }

    private fun import(uri: Uri, boardId: String?, createdAt: Long): Pin {
        val cr = app.contentResolver
        return import(cr.getType(uri).orEmpty(), boardId, createdAt) { media ->
            checkNotNull(cr.openInputStream(uri)) { "no stream" }.use { i -> media.outputStream().use { i.copyTo(it) } }
        }
    }

    /**
     * Makes a pin: [write] fills the media file, then the thumbnail and colours are derived from it. A video's
     * thumbnail is its [cover] (the poster the site chose) when there is one, else a frame from early on.
     */
    private fun import(mime: String, boardId: String?, createdAt: Long, cover: File? = null, write: (File) -> Unit): Pin {
        val video = mime.startsWith("video/")
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: if (video) "mp4" else "jpg"
        val id = UUID.randomUUID().toString()
        val media = File(mediaDir, "$id.$ext")
        val thumbFile = File(thumbDir, "$id.webp")
        try {
            write(media)
            var duration = 0L
            var w: Int
            var h: Int
            val thumb = if (video) {
                var coverBmp: Bitmap? = null
                val frame = MediaMetadataRetriever().use { r ->
                    r.setDataSource(media.path)
                    duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                    val mw = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    val mh = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    val rotated = rot == 90 || rot == 270
                    w = if (rotated) mh else mw
                    h = if (rotated) mw else mh
                    coverBmp = cover?.takeIf { w > 0 && h > 0 }?.let { runCatching { decodeScaled(it) }.getOrNull() }
                    if (coverBmp != null) {
                        null
                    } else {
                        // A tenth of the way in (at most 3s): videos often open on black or a title card.
                        val at = (duration * 100).coerceAtMost(3_000_000L) // µs
                        val f = r.getScaledFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, THUMB_MAX, THUMB_MAX)
                            ?: r.getScaledFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST, THUMB_MAX, THUMB_MAX)
                        checkNotNull(f) { "no frame" }.also {
                            if (w <= 0 || h <= 0) { w = it.width; h = it.height }
                        }
                    }
                }
                coverBmp ?: cover?.let { runCatching { decodeScaled(it) }.getOrNull() }?.also { frame?.recycle() } ?: checkNotNull(frame)
            } else {
                decodeScaled(media).also { w = it.width; h = it.height }
            }
            thumbFile.outputStream().buffered().use { thumb.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, it) }
            val pin = Pin(id, video, ext, w, h, duration, boardId = boardId, createdAt = createdAt, colors = colorsOf(thumb))
            thumb.recycle()
            return pin
        } catch (e: Throwable) {
            media.delete()
            thumbFile.delete()
            throw e
        }
    }

    /** An image scaled so its short side is at most THUMB_SHORT, in software memory (compress and getPixels need it). */
    private fun decodeScaled(file: File): Bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { d, info, _ ->
        // Scale by the short side so the math holds before and after EXIF rotation.
        val s = minOf(1f, THUMB_SHORT.toFloat() / minOf(info.size.width, info.size.height))
        d.setTargetSize((info.size.width * s).toInt().coerceAtLeast(1), (info.size.height * s).toInt().coerceAtLeast(1))
        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }

    /** Removes the local files of pins that are no longer in the library (deleted on another install). */
    suspend fun dropFiles(ids: Collection<String>) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        (mediaDir.listFiles().orEmpty().asList() + thumbDir.listFiles().orEmpty())
            .filter { it.nameWithoutExtension in ids }
            .forEach { it.delete() }
        ids.forEach(::forget)
    }

    private fun colorsOf(bmp: Bitmap): List<String> {
        // ~2k pixels is plenty to judge coverage and keeps this well under a millisecond.
        val small = Bitmap.createScaledBitmap(bmp, 48, (48f * bmp.height / bmp.width).toInt().coerceIn(1, 96), true)
        val px = IntArray(small.width * small.height)
        small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
        if (small !== bmp) small.recycle()
        return Colors.of(px)
    }

    private companion object {
        const val THUMB_SHORT = 560
        const val THUMB_MAX = 1120
    }
}
