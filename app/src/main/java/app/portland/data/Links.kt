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
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.portland.App
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Saves the image or video behind a pasted link, in the background. A link straight to a media file is
 * downloaded as is; for a web page, the media it advertises to link previews (og:video, og:image, …) is used.
 * shortcut: sites that hide media behind scripts or logins (YouTube, Instagram, TikTok) are not supported;
 * bundle an extractor such as yt-dlp if they are ever needed.
 */
object Links {
    private const val TAG = "link"
    private const val UA = "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"

    /** The first http(s) URL in [text] (shared text often wraps the link in a sentence), or null. */
    fun find(text: String): String? =
        Regex("""https?://[^\s<>"']+""").find(text)?.value?.trimEnd('.', ',', ')', ']')?.takeIf { it.toHttpUrlOrNull() != null }

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
        WorkManager.getInstance(context).getWorkInfosByTagFlow(TAG).map { list -> list.count { !it.state.isFinished } }

    /** Downloads the media behind [url] into [dir]. Returns the file and its MIME type. */
    fun download(url: String, dir: File): Pair<File, String> {
        get(url).use { r ->
            mediaType(r)?.let { return save(r, it, dir) }
            if (r.body.contentType()?.subtype != "html") throw NoMedia()
            // Page: try what it advertises, videos first. A candidate that turns out not to be media is skipped.
            val html = r.body.source().apply { request(MAX_PAGE) }.buffer.clone().readUtf8()
            for (candidate in mediaCandidates(html, r.request.url.toString())) {
                runCatching {
                    get(candidate).use { m -> mediaType(m)?.let { return save(m, it, dir) } }
                }.onFailure { Log.w("Links", "candidate failed: $candidate", it) }
            }
        }
        throw NoMedia()
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
        val f = File.createTempFile("link", null, dir)
        try {
            r.body.byteStream().use { i -> f.outputStream().use { i.copyTo(it) } }
        } catch (e: Exception) {
            f.delete()
            throw e
        }
        return f to mime
    }

    class NoMedia : Exception("no image or video at that link")

    private const val MAX_PAGE = 2L * 1024 * 1024
}

/**
 * Media URLs a page advertises for link previews, best first: videos, then images.
 * Relative URLs are resolved against [base]; HTML entities in attributes are decoded.
 */
internal fun mediaCandidates(html: String, base: String): List<String> {
    val baseUrl = base.toHttpUrlOrNull() ?: return emptyList()
    val meta = HashMap<String, String>()
    for (tag in Regex("""<meta\s[^>]*>""", RegexOption.IGNORE_CASE).findAll(html)) {
        val attrs = Regex("""([\w:-]+)\s*=\s*("([^"]*)"|'([^']*)')""").findAll(tag.value)
            .associate { it.groupValues[1].lowercase() to it.groupValues[3].ifEmpty { it.groupValues[4] } }
        val key = (attrs["property"] ?: attrs["name"] ?: attrs["itemprop"])?.lowercase() ?: continue
        val content = attrs["content"]?.takeIf { it.isNotBlank() } ?: continue
        meta.putIfAbsent(key, unescape(content.trim())) // the first of repeated tags is the main one
    }
    val sources = Regex("""<(?:video|source)\s[^>]*\bsrc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        .findAll(html).map { unescape(it.groupValues[1]) }
    val keys = listOf(
        "og:video:secure_url", "og:video:url", "og:video", "twitter:player:stream", "contenturl",
    )
    val images = listOf("og:image:secure_url", "og:image:url", "og:image", "twitter:image", "twitter:image:src", "image")
    return (keys.mapNotNull(meta::get) + sources + images.mapNotNull(meta::get))
        .mapNotNull { baseUrl.resolve(it)?.toString() }
        .distinct()
}

private fun unescape(s: String): String = s
    .replace(Regex("&#(x[0-9a-fA-F]+|\\d+);")) { m ->
        val n = m.groupValues[1]
        val code = if (n.startsWith("x")) n.drop(1).toInt(16) else n.toInt()
        String(Character.toChars(code))
    }
    .replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

class LinkWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val url = inputData.getString("url") ?: return Result.failure()
        val store = (applicationContext as App).store
        return try {
            val (file, mime) = Links.download(url, applicationContext.cacheDir)
            try {
                store.addFile(file, mime, inputData.getString("board"))
            } finally {
                file.delete() // gone already when addFile moved it
            }
            Result.success()
        } catch (e: IOException) {
            Log.w("Links", "will retry $url (attempt $runAttemptCount)", e)
            if (runAttemptCount < 4) Result.retry() else fail()
        } catch (e: Exception) {
            Log.w("Links", "giving up on $url", e)
            fail()
        }
    }

    private fun fail(): Result {
        val msg = "Couldn't find an image or video at that link"
        Handler(Looper.getMainLooper()).post { Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show() }
        return Result.failure()
    }
}

