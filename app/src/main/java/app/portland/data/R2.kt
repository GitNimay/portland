package app.portland.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
private const val REGION = "auto"
private const val SERVICE = "s3"
private val amzDateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.US).withZone(ZoneOffset.UTC)

/**
 * The user's own R2 bucket. [apiToken] is the Cloudflare API token the S3 keys were derived from, when the user
 * connected with one (kept for Workers AI captions); [captions] says that token may run Workers AI.
 */
data class R2Config(
    val accountId: String,
    val bucket: String,
    val accessKeyId: String,
    val secretAccessKey: String,
    val apiToken: String? = null,
    val captions: Boolean = false,
) {
    /** Which bucket this is, without secrets: a different one means everything uploads again. */
    val identity get() = "$accountId/$bucket"

    fun toJson(): String = JSONObject()
        .put("account", accountId).put("bucket", bucket)
        .put("key", accessKeyId).put("secret", secretAccessKey)
        .put("token", apiToken ?: JSONObject.NULL).put("captions", captions)
        .toString()

    override fun toString() = "R2Config($identity)" // never log the keys

    companion object {
        fun fromJson(s: String): R2Config = JSONObject(s).run {
            R2Config(
                getString("account"), getString("bucket"), getString("key"), getString("secret"),
                if (isNull("token")) null else optString("token").ifEmpty { null },
                optBoolean("captions"),
            )
        }

        fun endpoint(config: R2Config): HttpUrl = "https://${config.accountId}.r2.cloudflarestorage.com/${enc(config.bucket)}".toHttpUrl()
    }
}

/** An S3 call that came back with an HTTP error; [code] tells a bad key (401/403) from the rest. */
class R2Exception(val code: Int, message: String) : IOException(message)

/**
 * One bucket's S3 API with hand-rolled AWS SigV4 signing. Every request that [http] sends to [endpoint]'s host is
 * signed; others pass through untouched.
 */
class R2Client(val config: R2Config, val endpoint: HttpUrl = R2Config.endpoint(config), base: OkHttpClient = R2.base) {
    val http: OkHttpClient = base.newBuilder().addInterceptor { chain -> chain.proceed(sign(chain.request())) }.build()

    private fun owns(url: HttpUrl) = url.host == endpoint.host && url.port == endpoint.port

    /** [request] with SigV4 headers when it is for this bucket's host. */
    fun sign(request: Request, now: Instant = Instant.now()): Request {
        if (!owns(request.url)) return request
        val amzDate = amzDateFormatter.format(now)
        val payloadHash = request.header("x-amz-content-sha256") ?: EMPTY_SHA256
        val url = request.url
        val headers = mapOf(
            "host" to if (url.port == HttpUrl.defaultPort(url.scheme)) url.host else "${url.host}:${url.port}",
            "x-amz-content-sha256" to payloadHash,
            "x-amz-date" to amzDate,
        )
        val auth = sign(
            method = request.method,
            path = url.encodedPath,
            query = url.queryParameterNames.associateWith { url.queryParameter(it).orEmpty() },
            headers = headers,
            payloadHash = payloadHash,
            amzDate = amzDate,
            accessKey = config.accessKeyId,
            secretKey = config.secretAccessKey,
            region = REGION,
            service = SERVICE,
        )
        return request.newBuilder()
            .header("x-amz-content-sha256", payloadHash)
            .header("x-amz-date", amzDate)
            .header("Authorization", auth)
            .build()
    }

    fun url(key: String): String = endpoint.toString().trimEnd('/') + "/" + key.split('/').joinToString("/") { enc(it) }

    suspend fun put(key: String, file: File, contentType: String) = withContext(Dispatchers.IO) {
        send(
            Request.Builder().url(url(key))
                .header("x-amz-content-sha256", sha256Hex(file))
                .put(file.asRequestBody(contentType.toMediaTypeOrNull()))
                .build(),
            "PUT", key,
        )
    }

    suspend fun putBytes(key: String, bytes: ByteArray, contentType: String) = withContext(Dispatchers.IO) {
        send(
            Request.Builder().url(url(key))
                .header("x-amz-content-sha256", sha256Hex(bytes))
                .put(bytes.toRequestBody(contentType.toMediaTypeOrNull()))
                .build(),
            "PUT", key,
        )
    }

    /** Conditional PUT: succeeds if the object matches [etag] (or is absent when [etag] is null). Returns false on 412. */
    suspend fun putBytesIfMatch(key: String, bytes: ByteArray, contentType: String, etag: String?): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url(key))
            .header("x-amz-content-sha256", sha256Hex(bytes))
            .apply { if (etag != null) header("If-Match", etag) else header("If-None-Match", "*") }
            .put(bytes.toRequestBody(contentType.toMediaTypeOrNull()))
            .build()
        http.newCall(request).execute().use { response ->
            when {
                response.code == 412 -> false
                response.isSuccessful -> true
                else -> throw R2Exception(response.code, "PUT $key -> ${response.code}")
            }
        }
    }

    suspend fun get(key: String): ByteArray? = getWithEtag(key)?.first

    /**
     * Fetches [key] and its ETag header (or null when 404). Asks for the stored bytes as they are: if OkHttp's
     * transparent gzip is accepted, R2 compresses larger objects and answers with a weak ETag (W/"..."), which
     * never satisfies the If-Match of [putBytesIfMatch], so every conditional write would fail with 412.
     */
    suspend fun getWithEtag(key: String): Pair<ByteArray, String?>? = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url(key)).header("Accept-Encoding", "identity").build()).execute().use { response ->
            when {
                response.code == 404 -> null
                response.isSuccessful -> response.body.bytes() to response.header("ETag")
                else -> throw R2Exception(response.code, "GET $key -> ${response.code}")
            }
        }
    }

    /** Streams [key] into [into], written aside and renamed so a cut-off download never passes for a whole file. */
    suspend fun download(key: String, into: File) = withContext(Dispatchers.IO) {
        val part = File(into.path + ".part")
        try {
            http.newCall(Request.Builder().url(url(key)).build()).execute().use { response ->
                if (!response.isSuccessful) throw R2Exception(response.code, "GET $key -> ${response.code}")
                response.body.byteStream().use { input -> part.outputStream().buffered().use { input.copyTo(it) } }
            }
            if (!part.renameTo(into)) throw IOException("could not move $key into place")
        } finally {
            part.delete()
        }
    }

    suspend fun delete(key: String) = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url(key)).delete().build()).execute().use { response ->
            if (!response.isSuccessful && response.code != 404) throw R2Exception(response.code, "DELETE $key -> ${response.code}")
        }
    }

    /** Every object under [prefix] with its last-modified time (epoch ms), following pagination. */
    suspend fun list(prefix: String): List<Pair<String, Long>> = withContext(Dispatchers.IO) {
        val out = ArrayList<Pair<String, Long>>()
        var token: String? = null
        do {
            val url = endpoint.newBuilder()
                .addQueryParameter("list-type", "2")
                .addQueryParameter("prefix", prefix)
                .apply { token?.let { addQueryParameter("continuation-token", it) } }
                .build()
            val xml = http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) throw R2Exception(r.code, "LIST $prefix -> ${r.code}")
                r.body.string()
            }
            val page = parseList(xml)
            out += page.first
            token = page.second
        } while (token != null)
        out
    }

    private fun send(request: Request, method: String, key: String) {
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw R2Exception(response.code, "$method $key -> ${response.code}")
        }
    }
}

/**
 * The connected bucket, if any. [http] is the one client everything shares (Coil, ExoPlayer, sync, link
 * downloads): it signs requests to the connected bucket and leaves every other host alone, so switching buckets
 * needs no new clients.
 */
object R2 {
    /** Connection pool and timeouts shared by every client in the app. */
    internal val base: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // The grid and pager fire many thumbnail GETs at once; the default 5 per host would queue them.
            .dispatcher(Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 16 })
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(5, TimeUnit.MINUTES)
            .build()
    }

    @Volatile private var client: R2Client? = null

    val enabled: Boolean get() = client != null
    val config: R2Config? get() = client?.config

    fun configure(config: R2Config?) {
        client = config?.let { R2Client(it) }
    }

    val http: OkHttpClient by lazy {
        base.newBuilder().addInterceptor { chain ->
            val request = chain.request()
            chain.proceed(client?.sign(request) ?: request)
        }.build()
    }

    /** The object's URL, or one that fails fast (a reserved .invalid host) while no bucket is connected. */
    fun url(key: String): String = client?.url(key) ?: "https://r2.invalid/$key"

    private fun connected() = client ?: throw IOException("no R2 bucket connected")

    suspend fun put(key: String, file: File, contentType: String) = connected().put(key, file, contentType)
    suspend fun putBytes(key: String, bytes: ByteArray, contentType: String) = connected().putBytes(key, bytes, contentType)
    suspend fun putBytesIfMatch(key: String, bytes: ByteArray, contentType: String, etag: String?) =
        connected().putBytesIfMatch(key, bytes, contentType, etag)
    suspend fun get(key: String): ByteArray? = connected().get(key)
    suspend fun getWithEtag(key: String) = connected().getWithEtag(key)
    suspend fun download(key: String, into: File) = connected().download(key, into)
    suspend fun delete(key: String) = connected().delete(key)
    suspend fun list(prefix: String) = connected().list(prefix)
}

private val CONTENTS_REGEX = Regex("<Contents>(.*?)</Contents>", RegexOption.DOT_MATCHES_ALL)
private val KEY_REGEX = Regex("<Key>([^<]*)</Key>")
private val LAST_MODIFIED_REGEX = Regex("<LastModified>([^<]*)</LastModified>")
private val TRUNCATED_REGEX = Regex("<IsTruncated>([^<]*)</IsTruncated>")
private val NEXT_TOKEN_REGEX = Regex("<NextContinuationToken>([^<]*)</NextContinuationToken>")
private val WS_REGEX = Regex("\\s+")

/** One ListObjectsV2 page: (key, lastModified ms) pairs, and the continuation token when there are more. */
internal fun parseList(xml: String): Pair<List<Pair<String, Long>>, String?> {
    val items = CONTENTS_REGEX.findAll(xml).mapNotNull { m ->
        val body = m.groupValues[1]
        val key = KEY_REGEX.find(body)?.groupValues?.get(1)?.xmlUnescape() ?: return@mapNotNull null
        key to (LAST_MODIFIED_REGEX.find(body)?.groupValues?.get(1)?.let { Instant.parse(it).toEpochMilli() } ?: 0L)
    }.toList()
    val more = TRUNCATED_REGEX.find(xml)?.groupValues?.get(1) == "true"
    return items to if (more) NEXT_TOKEN_REGEX.find(xml)?.groupValues?.get(1)?.xmlUnescape() else null
}

private fun String.xmlUnescape() =
    replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

/** Pure SigV4 math, kept free of Android so it is unit-testable. Returns the Authorization header value. */
internal fun sign(
    method: String,
    path: String,
    query: Map<String, String>,
    headers: Map<String, String>,
    payloadHash: String,
    amzDate: String,
    accessKey: String,
    secretKey: String,
    region: String,
    service: String,
): String {
    val signedNames = headers.keys.map { it.lowercase(Locale.US) }.sorted()
    val canonicalHeaders = signedNames.joinToString("") { name ->
        val value = headers.entries.first { it.key.equals(name, ignoreCase = true) }.value
        "$name:${value.trim().replace(WS_REGEX, " ")}\n"
    }
    val signedHeaders = signedNames.joinToString(";")
    val canonicalQuery = query.entries.sortedBy { it.key }
        .joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
    val canonicalRequest = "$method\n$path\n$canonicalQuery\n$canonicalHeaders\n$signedHeaders\n$payloadHash"

    val date = amzDate.take(8)
    val scope = "$date/$region/$service/aws4_request"
    val stringToSign = "AWS4-HMAC-SHA256\n$amzDate\n$scope\n${sha256Hex(canonicalRequest.toByteArray())}"
    val signature = hmac(
        hmac(hmac(hmac("AWS4$secretKey".toByteArray(), date), region), service),
        "aws4_request",
    ).let { hmac(it, stringToSign) }.toHex()

    return "AWS4-HMAC-SHA256 Credential=$accessKey/$scope, SignedHeaders=$signedHeaders, Signature=$signature"
}

/** RFC 3986 percent-encoding (S3 canonical form; spaces are %20, not +). */
private fun enc(s: String): String = buildString {
    for (b in s.toByteArray(Charsets.UTF_8)) {
        val c = b.toInt() and 0xFF
        val unreserved = c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code ||
            c in '0'.code..'9'.code || c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code
        if (unreserved) {
            append(c.toChar())
        } else {
            append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
        }
    }
}

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

internal fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().toHex()
}

private fun hmac(key: ByteArray, data: String): ByteArray =
    Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data.toByteArray())

private fun ByteArray.toHex(): String {
    val hex = "0123456789abcdef"
    return buildString(size * 2) { for (b in this@toHex) { append(hex[b.toInt() shr 4 and 15]); append(hex[b.toInt() and 15]) } }
}
