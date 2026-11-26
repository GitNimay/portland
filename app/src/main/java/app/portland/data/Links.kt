package app.portland.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.portland.App
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Saves the image or video behind a pasted link, in the background: a link straight to a media file, a video on
 * YouTube, X, Instagram, TikTok and the like (via yt-dlp, see [Video]), or the media a page advertises to link
 * previews (og:video, og:image, …).
 */
object Links {
    private const val TAG = "link"
    private const val UA = "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"

    /** The first http(s) URL in [text] (shared text often wraps the link in a sentence), or null. */
    fun find(text: String): String? =
        URL_REGEX.find(text)?.value?.trimEnd('.', ',', ')', ']')?.takeIf { it.toHttpUrlOrNull() != null }

    fun enqueue(context: Context, url: String, boardId: String?) {
        val work = OneTimeWorkRequestBuilder<LinkWorker>()
            .setInputData(workDataOf("url" to url, "board" to boardId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueue(work)
    }

    /** Links still waiting or downloading. Lives in WorkManager, so it survives the app being closed. */
    fun pending(context: Context): Flow<Int> =
        WorkManager.getInstance(context).getWorkInfosFlow(
            WorkQuery.Builder.fromTags(listOf(TAG))
                .addStates(listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED))
                .build(),
        ).map { it.size }

    /**
     * Downloads the media behind [url] into [dir] (a folder of its own, kept across retries so a long video
     * resumes). Returns the file and its MIME type. In order: a link straight to an image or video; the video on
     * a page, via yt-dlp (YouTube, X, Instagram, TikTok…); the media the page advertises for link previews.
     */
    suspend fun download(context: Context, url: String, dir: File): Downloaded {
        dir.mkdirs()
        // Platforms often answer a plain fetch with 4xx (login or bot walls) yet still work through yt-dlp.
        var direct: Downloaded? = null
        val page: Pair<String, String>? = try { // (html, final url after redirects)
            withContext(Dispatchers.IO) {
                get(url).use { r ->
                    val type = mediaType(r)
                    when {
                        type != null -> null.also { direct = save(r, type, dir).let { (f, m) -> Downloaded(f, m) } }
                        r.body.contentType()?.subtype != "html" -> null
                        else -> r.body.source().apply { request(MAX_PAGE) }.buffer.clone().readUtf8() to r.request.url.toString()
                    }
                }
            }
        } catch (e: NoMedia) {
            null
        }
        direct?.let { return it }
        Video.download(context, url, File(dir, "video"))?.let { return it }
        val (html, pageUrl) = page ?: throw NoMedia()
        // Page: try what it advertises, videos first. A candidate that turns out not to be media is skipped.
        return withContext(Dispatchers.IO) {
            for (candidate in mediaCandidates(html, pageUrl)) {
                runCatching {
                    get(candidate).use { m -> mediaType(m)?.let { return@withContext save(m, it, dir).let { (f, t) -> Downloaded(f, t) } } }
                }.onFailure { Log.w("Links", "candidate failed: $candidate", it) }
            }
            throw NoMedia()
        }
    }

    private fun get(url: String): Response {
        val r = R2.http.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute()
        if (!r.isSuccessful) {
            r.close()
            // 4xx will not fix itself; anything else is worth another try later.
            if (r.code in 400..499) throw NoMedia() else throw IOException("GET $url -> ${r.code}")
        }
        return r
    }

    private fun mediaType(r: Response): String? {
        val declared = r.body.contentType()?.let { "${it.type}/${it.subtype}" }
        // Some CDNs serve media as octet-stream; trust the file extension then.
        val mime = if (declared == null || declared.endsWith("/octet-stream")) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(r.request.url.encodedPath.substringAfterLast('.', "").lowercase())
        } else {
            declared
        }
        return mime?.takeIf { it.startsWith("image/") || it.startsWith("video/") }
    }

    private fun save(r: Response, mime: String, dir: File): Pair<File, String> {
        val length = r.body.contentLength()
        if (length > MAX_MEDIA_BYTES) throw IOException("media too large ($length bytes)")
        val f = File.createTempFile("link", null, dir)
        try {
            r.body.byteStream().use { input ->
                f.outputStream().buffered().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        total += n
                        if (total > MAX_MEDIA_BYTES) throw IOException("media exceeded $MAX_MEDIA_BYTES bytes")
                        output.write(buf, 0, n)
                    }
                }
            }
        } catch (e: Exception) {
            f.delete()
            throw e
        }
        return f to mime
    }

    class NoMedia : Exception("no image or video at that link")

    private const val MAX_PAGE = 2L * 1024 * 1024
    private const val MAX_MEDIA_BYTES = 512L * 1024 * 1024
    private val URL_REGEX = Regex("""https?://[^\s<>"']+""")
}

private val META_TAG_REGEX = Regex("""<meta\s[^>]*>""", RegexOption.IGNORE_CASE)
private val ATTR_REGEX = Regex("""([\w:-]+)\s*=\s*("([^"]*)"|'([^']*)')""")
private val SOURCE_REGEX = Regex("""<(?:video|source)\s[^>]*\bsrc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
private val ENTITY_REGEX = Regex("&#(x[0-9a-fA-F]+|\\d+);")

/**
 * Media URLs a page advertises for link previews, best first: videos, then images.
 * Relative URLs are resolved against [base]; HTML entities in attributes are decoded.
 */
internal fun mediaCandidates(html: String, base: String): List<String> {
    val baseUrl = base.toHttpUrlOrNull() ?: return emptyList()
    val meta = HashMap<String, String>()
    for (tag in META_TAG_REGEX.findAll(html)) {
        val attrs = ATTR_REGEX.findAll(tag.value)
            .associate { it.groupValues[1].lowercase() to it.groupValues[3].ifEmpty { it.groupValues[4] } }
        val key = (attrs["property"] ?: attrs["name"] ?: attrs["itemprop"])?.lowercase() ?: continue
        val content = attrs["content"]?.takeIf { it.isNotBlank() } ?: continue
        meta.putIfAbsent(key, unescape(content.trim())) // the first of repeated tags is the main one
    }
    val sources = SOURCE_REGEX.findAll(html).map { unescape(it.groupValues[1]) }
    val keys = listOf(
        "og:video:secure_url", "og:video:url", "og:video", "twitter:player:stream", "contenturl",
    )
    val images = listOf("og:image:secure_url", "og:image:url", "og:image", "twitter:image", "twitter:image:src", "image")
    return (keys.mapNotNull(meta::get) + sources + images.mapNotNull(meta::get))
        .mapNotNull { baseUrl.resolve(it)?.toString() }
        .distinct()
}

private fun unescape(s: String): String = s
    .replace(ENTITY_REGEX) { m ->
        val n = m.groupValues[1]
        val code = if (n.startsWith("x")) n.drop(1).toIntOrNull(16) else n.toIntOrNull()
        if (code != null && Character.isValidCodePoint(code)) String(Character.toChars(code)) else m.value
    }
    .replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

class LinkWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val url = inputData.getString("url") ?: return Result.failure()
        val store = (applicationContext as App).store
        // One folder per link that outlives retries, so a half-downloaded video resumes instead of starting over.
        val dir = File(applicationContext.cacheDir, "links/$id")
        return try {
            val got = Links.download(applicationContext, url, dir)
            store.addFile(got.file, got.mime, inputData.getString("board"), got.cover)
            dir.deleteRecursively()
            Result.success()
        } catch (e: CancellationException) {
            throw e // stopped by the system; WorkManager reruns it and the folder lets it resume
        } catch (e: Video.Refused) {
            Log.w("Links", "video refused: $url", e)
            fail(dir, e.message)
        } catch (e: IOException) {
            Log.w("Links", "will retry $url (attempt $runAttemptCount)", e)
            if (runAttemptCount < 4) Result.retry() else fail(dir)
        } catch (e: Exception) {
            Log.w("Links", "giving up on $url", e)
            fail(dir)
        }
    }

    private fun fail(dir: File, reason: String? = null): Result {
        dir.deleteRecursively()
        val msg = reason ?: "Couldn't find an image or video at that link"
        Handler(Looper.getMainLooper()).post { Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show() }
        return Result.failure()
    }
}

