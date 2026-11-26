package app.portland.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.floor
import kotlin.math.max

/**
 * Posts that hold more than one image or video (an X post with four photos, a Pinterest carousel or story pin),
 * read from the JSON the sites serve their own embeds and web app. [Links] turns each item into a download of its
 * own. Everything here is pure (no Android, no network) so it is unit tested on the JVM.
 */
internal object Posts {
    /** At most this many items are saved from one post: a long story pin should not flood the feed. */
    const val MAX_ITEMS = 20

    private val TWEET_HOSTS = setOf(
        "x.com", "twitter.com", "mobile.x.com", "mobile.twitter.com",
        "fxtwitter.com", "vxtwitter.com", "fixupx.com", "fixvx.com",
    )
    private val TWEET_PATH = Regex("""^/(?:i/web|i|[^/]+)/status(?:es)?/(\d{1,25})(?:/|$)""")
    private val PINTEREST_HOST = Regex("""(?:^|\.)pinterest\.(?:com|[a-z]{2,3}(?:\.[a-z]{2})?)$""")
    private val PIN_PATH = Regex("""^/pin/(?:[^/]*--)?(\d{1,25})(?:/|$)""")

    /** The id of the X (Twitter) post at [url], or null when it is not a link to one. */
    fun tweetId(url: String): String? {
        val u = url.toHttpUrlOrNull() ?: return null
        if (u.host.removePrefix("www.") !in TWEET_HOSTS) return null
        return TWEET_PATH.find(u.encodedPath)?.groupValues?.get(1)
    }

    /** The id of the Pinterest pin at [url], or null when it is not a link to one (pin.it links are resolved first). */
    fun pinId(url: String): String? {
        val u = url.toHttpUrlOrNull() ?: return null
        if (!PINTEREST_HOST.containsMatchIn(u.host)) return null
        return PIN_PATH.find(u.encodedPath)?.groupValues?.get(1)
    }

    /** A pin.it short link, which redirects to the pin it stands for. */
    fun isPinShortLink(url: String): Boolean = url.toHttpUrlOrNull()?.host?.removePrefix("www.") == "pin.it"

    /** X's embed endpoint, the one its own embedded-post widget reads: public, no sign-in, no API key. */
    fun tweetApi(id: String): HttpUrl = HttpUrl.Builder()
        .scheme("https")
        .host("cdn.syndication.twimg.com")
        .addPathSegment("tweet-result")
        .addQueryParameter("id", id)
        .addQueryParameter("lang", "en")
        .addQueryParameter("token", syndicationToken(id))
        .build()

    /** Pinterest's web app endpoint for one pin, with every field (carousel slots, story pages, videos). */
    fun pinApi(id: String): HttpUrl = HttpUrl.Builder()
        .scheme("https")
        .host("www.pinterest.com")
        .addPathSegments("resource/PinResource/get/")
        .addQueryParameter("source_url", "/pin/$id/")
        .addQueryParameter(
            "data",
            JSONObject()
                .put("options", JSONObject().put("id", id).put("field_set_key", "detailed"))
                .put("context", JSONObject())
                .toString(),
        )
        .build()

    /**
     * The token X's embed widget sends with [id]: `((id / 1e15) * π).toString(36)` with zeros and the dot taken
     * out. The widget computes it in JavaScript, so this follows JavaScript's number-to-base-36 rules exactly.
     */
    fun syndicationToken(id: String): String {
        val value = (id.toDoubleOrNull() ?: 0.0) / 1e15 * Math.PI
        return jsRadix36(value).replace(Regex("(0+|\\.)"), "")
    }

    /** Photos, videos and GIFs of an X post, in the order the post shows them. */
    fun tweetMedia(json: String): List<PostMedia> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val details = root.optJSONArray("mediaDetails")
        val media = ArrayList<PostMedia>()
        if (details != null) {
            for (m in details.objects()) {
                when (m.optString("type")) {
                    "photo" -> m.optString("media_url_https").takeIf { it.isNotBlank() }
                        ?.let { media += PostMedia(originalPhoto(it), video = false) }
                    "video", "animated_gif" -> bestMp4(m.optJSONObject("video_info")?.optJSONArray("variants"))
                        ?.let { media += PostMedia(it, video = true) }
                }
            }
        } else {
            // Older answers list photos on their own.
            for (p in root.optJSONArray("photos").objects()) {
                p.optString("url").takeIf { it.isNotBlank() }?.let { media += PostMedia(originalPhoto(it), video = false) }
            }
        }
        return media.distinctBy { it.url }.take(MAX_ITEMS)
    }

    /**
     * Images and videos of a Pinterest pin: every page of a story pin, every slot of a carousel, or the pin's own
     * video or image. [json] is the web app's answer (`resource_response.data` holds the pin) or the pin itself.
     */
    fun pinMedia(json: String): List<PostMedia> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val pin = root.optJSONObject("resource_response")?.optJSONObject("data") ?: root
        val media = ArrayList<PostMedia>()

        pin.optJSONObject("story_pin_data")?.optJSONArray("pages")?.objects()?.forEach { page ->
            for (block in page.optJSONArray("blocks").objects()) {
                val video = block.optJSONObject("video")?.optJSONObject("video_list")?.let(::bestPinVideo)
                val image = block.optJSONObject("image")?.optJSONObject("images")?.let(::bestPinImage)
                (video?.let { PostMedia(it, video = true) } ?: image?.let { PostMedia(it, video = false) })?.let(media::add)
            }
        }
        if (media.isEmpty()) {
            pin.optJSONObject("carousel_data")?.optJSONArray("carousel_slots")?.objects()?.forEach { slot ->
                slot.optJSONObject("images")?.let(::bestPinImage)?.let { media += PostMedia(it, video = false) }
            }
        }
        if (media.isEmpty()) {
            pin.optJSONObject("videos")?.optJSONObject("video_list")?.let(::bestPinVideo)?.let { media += PostMedia(it, video = true) }
                ?: pin.optJSONObject("images")?.let(::bestPinImage)?.let { media += PostMedia(it, video = false) }
        }
        return media.distinctBy { it.url }.take(MAX_ITEMS)
    }

    /** The full-size photo: X serves a smaller one unless asked for the original. */
    private fun originalPhoto(url: String): String {
        val u = url.toHttpUrlOrNull() ?: return url
        if (u.host != "pbs.twimg.com") return url
        return u.newBuilder().removeAllQueryParameters("name").addQueryParameter("name", "orig").build().toString()
    }

    /** The highest-bitrate MP4 of an X video (the HLS playlist entry has no bitrate and is skipped). */
    private fun bestMp4(variants: JSONArray?): String? = variants.objects()
        .filter { it.optString("content_type") == "video/mp4" && it.optString("url").isNotBlank() }
        .maxByOrNull { it.optInt("bitrate", 0) }
        ?.optString("url")

    /** The original upload when Pinterest lists it, otherwise its widest size. */
    private fun bestPinImage(images: JSONObject): String? {
        for (key in listOf("originals", "orig")) {
            images.optJSONObject(key)?.optString("url")?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return images.keys().asSequence()
            .mapNotNull { images.optJSONObject(it) }
            .filter { it.optString("url").isNotBlank() }
            .maxByOrNull { it.optInt("width", 0) }
            ?.optString("url")
    }

    /** A Pinterest video as one MP4 when there is one (widest first), else its HLS playlist (yt-dlp reads those). */
    private fun bestPinVideo(list: JSONObject): String? {
        val formats = list.keys().asSequence().mapNotNull { list.optJSONObject(it) }.filter { it.optString("url").isNotBlank() }.toList()
        val mp4 = formats.filter { it.optString("url").substringBefore('?').endsWith(".mp4") }.maxByOrNull { it.optInt("width", 0) }
        return (mp4 ?: formats.firstOrNull { ".m3u8" in it.optString("url") } ?: formats.firstOrNull())?.optString("url")
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
}

/** One image or video of a post, at [url]. */
internal data class PostMedia(val url: String, val video: Boolean)

/**
 * JavaScript's `Number.prototype.toString(36)` for a finite, non-negative [value], digit for digit: V8's
 * DoubleToRadixCString, which prints the shortest fraction that reads back as the same double.
 */
internal fun jsRadix36(value: Double): String {
    require(value.isFinite() && value >= 0.0)
    val chars = "0123456789abcdefghijklmnopqrstuvwxyz"
    val radix = 36
    var integer = floor(value)
    var fraction = value - integer
    // Half the distance to the next double: digits past that precision are noise.
    var delta = max(Math.nextUp(0.0), 0.5 * (Math.nextUp(value) - value))
    val digits = StringBuilder()
    if (fraction >= delta) {
        do {
            fraction *= radix
            delta *= radix
            val digit = fraction.toInt()
            digits.append(chars[digit])
            fraction -= digit
            if (fraction > 0.5 || (fraction == 0.5 && (digit and 1) == 1)) {
                if (fraction + delta > 1) {
                    // Round up, carrying into earlier digits and, past the first one, into the integer part.
                    while (true) {
                        if (digits.isEmpty()) {
                            integer += 1
                            break
                        }
                        val d = chars.indexOf(digits.last())
                        digits.setLength(digits.length - 1)
                        if (d + 1 < radix) {
                            digits.append(chars[d + 1])
                            break
                        }
                    }
                    break
                }
            }
        } while (fraction >= delta)
    }
    val whole = StringBuilder()
    do {
        val remainder = integer % radix
        whole.append(chars[remainder.toInt()])
        integer = (integer - remainder) / radix
    } while (integer > 0)
    whole.reverse()
    return if (digits.isEmpty()) whole.toString() else "$whole.$digits"
}
