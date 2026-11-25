package app.portland.data

import android.content.Context
import android.util.Log
import android.webkit.MimeTypeMap
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Downloads the video behind a page link (YouTube, X, Instagram, TikTok, Reddit, Vimeo… anything yt-dlp knows)
 * as one mp4 with sound, on the phone itself. yt-dlp is bundled with its own Python and ffmpeg, and refreshes
 * itself daily because sites change faster than app releases.
 */
object Video {
    private val setup = Mutex()
    private var ready = false
    private const val UPDATE_EVERY_MS = 24 * 60 * 60 * 1000L
    // Up to 1080p, H.264 + AAC first: every phone decodes it in hardware (YouTube otherwise picks AV1 or VP9).
    private const val SORT = "res:1080,vcodec:h264,acodec:aac,ext:mp4:m4a"

    /**
     * Downloads [url] into [dir], or returns null when there is no video there (an image-only post, a plain
     * article), so the caller may fall back to the page's preview image. Throws [Refused] when the site has a
     * video but would not hand it over (bot check, login wall). [dir] should be stable per link: a retried job
     * resumes partial files.
     */
    suspend fun download(context: Context, url: String, dir: File): Downloaded? {
        prepare(context)
        dir.mkdirs()
        // Best video + audio merged by ffmpeg into one mp4. Then, for a YouTube bot check, the android_vr client,
        // which YouTube checks less (360p at worst). Last, the best single file, which needs no ffmpeg (the
        // bundled ffmpeg is broken on some devices). A failed attempt can leave a silent video-only stream, so
        // each retry starts from an empty folder.
        var error = fetch(url, dir, "bv*+ba/b", merge = true).let { (got, err) -> got?.let { return it }; err }
        if ("not a bot" in error) {
            dir.listFiles().orEmpty().forEach { it.delete() }
            error = fetch(url, dir, "bv*+ba/b", merge = true, "youtube:player_client=android_vr")
                .let { (got, err) -> got?.let { return it }; err }
        }
        if (videoError(error) != null && "not a bot" !in error) {
            dir.listFiles().orEmpty().forEach { it.delete() }
            error = fetch(url, dir, "b[ext=mp4]/b", merge = false).let { (got, err) -> got?.let { return it }; err }
        }
        throw Refused(videoError(error) ?: return null)
    }

    /** The site has a video but would not give it; [message] says why, for the person who shared it. */
    class Refused(message: String) : Exception(message)

    /** Runs yt-dlp once. Returns what it downloaded, or null and yt-dlp's last error line. */
    private suspend fun fetch(
        url: String,
        dir: File,
        format: String,
        merge: Boolean,
        extractorArgs: String? = null,
    ): Pair<Downloaded?, String> {
        val request = YoutubeDLRequest(url).apply {
            addOption("--no-playlist")
            addOption("-f", format)
            addOption("-S", SORT)
            if (merge) addOption("--merge-output-format", "mp4")
            extractorArgs?.let { addOption("--extractor-args", it) }
            addOption("--write-thumbnail") // the site's own cover, used as the pin's preview
            addOption("--no-mtime")
            addOption("-o", File(dir, "%(id).80s.%(ext)s").path)
        }
        val id = dir.name
        val error = coroutineScope {
            // execute() blocks until yt-dlp exits; await() keeps this cancellable, and a cancel kills the process.
            val job = async(Dispatchers.IO) {
                runCatching { YoutubeDL.getInstance().execute(request, id, null) }.exceptionOrNull()
                    ?.let { e -> e.message?.lineSequence()?.lastOrNull { it.startsWith("ERROR") } ?: e.message.orEmpty() }
            }
            try {
                job.await()
            } catch (e: CancellationException) {
                YoutubeDL.getInstance().destroyProcessById(id)
                throw e
            }
        }
        if (error != null) {
            Log.i("Video", "yt-dlp: $error")
            return null to error
        }
        val files = dir.listFiles().orEmpty()
            .filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
            .mapNotNull { f -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase())?.let { f to it } }
        val videos = files.filter { it.second.startsWith("video/") }
        val images = files.filter { it.second.startsWith("image/") }
        // A video with its cover; or, from a post that holds only a picture, that picture.
        val (file, mime) = (videos.ifEmpty { images }).maxByOrNull { it.first.length() } ?: return null to "ERROR: no file"
        val cover = images.map { it.first }.firstOrNull { it != file }
        return Downloaded(file, mime, cover) to ""
    }

    /** Unpacks Python and ffmpeg on first use, and updates yt-dlp at most once a day. */
    private suspend fun prepare(context: Context) = setup.withLock {
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            if (!ready) {
                YoutubeDL.getInstance().init(app)
                FFmpeg.getInstance().init(app)
                ready = true
            }
            val stamp = File(app.noBackupFilesDir, "ytdlp-updated")
            if (System.currentTimeMillis() - stamp.lastModified() > UPDATE_EVERY_MS) {
                // A failed update keeps the copy we have; the next link tries again.
                runCatching { YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE) }
                    .onSuccess { stamp.writeText(it?.name.orEmpty()) }
                    .onFailure { Log.w("Video", "yt-dlp update failed", it) }
            }
        }
    }
}

/**
 * What to tell the person when yt-dlp failed with [error] (its last ERROR line), or null when the page simply has
 * no video (not a video site, an image-only post), in which case its preview image is the right thing to save.
 */
internal fun videoError(error: String): String? {
    val e = error.lowercase()
    if ("unsupported url" in e || "[generic]" in e || "no video" in e || "there's no video" in e) return null
    val site = Regex("""\[([a-z0-9]+)(?::[^\]]*)?]""").find(e)?.groupValues?.get(1)
        ?.let { SITES[it] ?: it.replaceFirstChar(Char::uppercase) } ?: "That site"
    return when {
        "not a bot" in e -> "$site blocked the download for now. Try again later."
        "sign in" in e || "log in" in e || "logged-in" in e || "login" in e || "cookies" in e -> "$site needs you to be signed in for this video"
        "private" in e -> "That $site video is private"
        "unavailable" in e || "removed" in e || "404" in e -> "That $site video is no longer available"
        else -> "Couldn't download the video from $site"
    }
}

private val SITES = mapOf(
    "youtube" to "YouTube", "twitter" to "X", "instagram" to "Instagram", "tiktok" to "TikTok", "reddit" to "Reddit",
    "vimeo" to "Vimeo", "facebook" to "Facebook", "pinterest" to "Pinterest", "threads" to "Threads",
)

/** A downloaded image or video ([mime]), and for a video the cover image its site chose, if any. */
data class Downloaded(val file: File, val mime: String, val cover: File? = null)
