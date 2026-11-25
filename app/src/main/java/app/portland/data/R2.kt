package app.portland.data

import app.portland.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
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
private const val AMZ_DATE = "yyyyMMdd'T'HHmmss'Z'"

/** Cloudflare R2 client: S3 API with hand-rolled AWS SigV4 signing. */
object R2 {
    private const val ACCESS_KEY = BuildConfig.R2_ACCESS_KEY
    private const val SECRET_KEY = BuildConfig.R2_SECRET_KEY
    private val host = "${BuildConfig.R2_ACCOUNT_ID}.r2.cloudflarestorage.com"
    private val endpoint = "https://$host/${enc(BuildConfig.R2_BUCKET)}"
    private val amzDateFormatter = DateTimeFormatter.ofPattern(AMZ_DATE, Locale.US).withZone(ZoneOffset.UTC)

    val enabled: Boolean = BuildConfig.R2_ACCOUNT_ID.isNotEmpty() &&
        ACCESS_KEY.isNotEmpty() && SECRET_KEY.isNotEmpty() && BuildConfig.R2_BUCKET.isNotEmpty()

    /**
     * Signs in an interceptor rather than per call because Coil and ExoPlayer (Range GETs)
     * issue their own requests through this client.
     */
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(5, TimeUnit.MINUTES)
            .addInterceptor { chain ->
                val request = chain.request()
                if (request.url.host != host) {
                    chain.proceed(request)
                } else {
                    val amzDate = amzDateFormatter.format(Instant.now())
                    val payloadHash = request.header("x-amz-content-sha256") ?: EMPTY_SHA256
                    val headers = mapOf(
                        "host" to request.url.host,
                        "x-amz-content-sha256" to payloadHash,
                        "x-amz-date" to amzDate,
                    )
                    val auth = sign(
                        method = request.method,
                        path = request.url.encodedPath,
                        query = queryOf(request.url),
                        headers = headers,
                        payloadHash = payloadHash,
                        amzDate = amzDate,
                        accessKey = ACCESS_KEY,
                        secretKey = SECRET_KEY,
                        region = REGION,
                        service = SERVICE,
                    )
                    chain.proceed(
                        request.newBuilder()
                            .header("x-amz-content-sha256", payloadHash)
                            .header("x-amz-date", amzDate)
                            .header("Authorization", auth)
                            .build()
                    )
                }
            }
            .build()
    }

    fun url(key: String): String = "$endpoint/" + key.split('/').joinToString("/") { enc(it) }

    suspend fun put(key: String, file: File, contentType: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url(key))
            .header("x-amz-content-sha256", sha256Hex(file))
            .put(file.asRequestBody(contentType.toMediaTypeOrNull()))
            .build()
        send(request, "PUT", key)
    }

    suspend fun putBytes(key: String, bytes: ByteArray, contentType: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url(key))
            .header("x-amz-content-sha256", sha256Hex(bytes))
            .put(bytes.toRequestBody(contentType.toMediaTypeOrNull()))
            .build()
        send(request, "PUT", key)
    }

    suspend fun get(key: String): ByteArray? = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url(key)).build()).execute().use { response ->
            when {
                response.code == 404 -> null
                response.isSuccessful -> response.body.bytes()
                else -> throw IOException("GET $key -> ${response.code}")
            }
        }
    }

    suspend fun delete(key: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url(key)).delete().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 404) throw IOException("DELETE $key -> ${response.code}")
        }
    }

    private fun send(request: Request, method: String, key: String) {
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("$method $key -> ${response.code}")
        }
    }
}

private fun queryOf(url: HttpUrl): Map<String, String> =
    url.queryParameterNames.associateWith { url.queryParameter(it).orEmpty() }

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
        "$name:${value.trim().replace(Regex("\\s+"), " ")}\n"
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

private fun sha256Hex(file: File): String {
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

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
