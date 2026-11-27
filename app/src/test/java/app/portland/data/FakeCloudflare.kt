package app.portland.data

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cloudflare's REST API (under /client/v4/) and R2's S3 API (under /<bucket>/) on one MockWebServer. S3 requests
 * are only accepted when their SigV4 signature checks out against the secret Cloudflare would derive for the
 * token (SHA-256 of its value), so the tests prove the whole key derivation, not just that a header was sent.
 */
class FakeCloudflare : Dispatcher() {
    data class Token(
        val id: String,
        val value: String,
        val user: Boolean = true,
        val seesAccounts: Boolean = true,
        val r2Admin: Boolean = true,
        val ai: Boolean = true,
        /** S3 requests refused with 403 this many times first, as a token that is still spreading. */
        var coldStarts: Int = 0,
    )

    val server = MockWebServer()
    val accounts = mutableListOf(CloudflareAccount(ACCOUNT, "Main"))
    val tokens = mutableListOf<Token>()
    val buckets = mutableSetOf<String>()
    val objects = mutableMapOf<String, ByteArray>() // "bucket/key" -> bytes
    /** Extra S3 keys (access key id -> secret) that are not derived from a token. */
    val s3Keys = mutableMapOf<String, String>()
    var r2Enabled = true
    var bucketsCreated = 0

    init {
        server.dispatcher = this
        server.start()
    }

    val api: HttpUrl get() = server.url("/client/v4/")
    fun endpoint(config: R2Config): HttpUrl = server.url("/${config.bucket}")

    fun stop() = server.close()

    override fun dispatch(request: RecordedRequest): MockResponse {
        val url = request.url
        val path = url.encodedPath
        return if (path.startsWith("/client/v4/")) api(request, path.removePrefix("/client/v4/")) else s3(request)
    }

    private fun api(request: RecordedRequest, path: String): MockResponse {
        val bearer = request.headers["Authorization"]?.removePrefix("Bearer ")
        val token = tokens.firstOrNull { it.value == bearer } ?: return error(401, 1000, "Invalid API Token")
        val method = request.method
        return when {
            path == "user/tokens/verify" ->
                if (token.user) ok(JSONObject().put("id", token.id).put("status", "active")) else error(401, 1000, "Invalid API Token")
            path.startsWith("accounts/") && path.endsWith("/tokens/verify") ->
                if (!token.user) ok(JSONObject().put("id", token.id).put("status", "active")) else error(401, 1000, "Invalid API Token")
            path == "accounts" ->
                ok(JSONArray().apply { if (token.seesAccounts) accounts.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) } })
            path.matches(Regex("accounts/[^/]+/r2/buckets")) -> {
                val account = path.split('/')[1]
                when {
                    accounts.none { it.id == account } -> error(403, 7003, "Could not route")
                    !token.r2Admin -> error(403, 10000, "Authentication error")
                    !r2Enabled -> error(403, 10042, "Please enable R2 through the Cloudflare Dashboard.")
                    method == "GET" -> ok(JSONObject().put("buckets", JSONArray().apply { buckets.forEach { put(JSONObject().put("name", it)) } }))
                    method == "POST" -> {
                        val name = JSONObject(request.body!!.utf8()).getString("name")
                        buckets += name
                        bucketsCreated++
                        ok(JSONObject().put("name", name))
                    }
                    else -> error(405, 0, "no")
                }
            }
            path.matches(Regex("accounts/[^/]+/ai/models/search")) ->
                if (token.ai) ok(JSONArray()) else error(403, 10000, "Authentication error")
            else -> error(404, 7000, "No route for that URI")
        }
    }

    private fun s3(request: RecordedRequest): MockResponse {
        val auth = request.headers["Authorization"] ?: return xml(403, "AccessDenied")
        val keyId = Regex("Credential=([^/]+)/").find(auth)?.groupValues?.get(1)
        val token = tokens.firstOrNull { it.id == keyId }
        val secret = token?.let { sha256Hex(it.value.toByteArray()) } ?: s3Keys[keyId] ?: return xml(403, "InvalidAccessKeyId")
        val url = request.url
        val expected = sign(
            method = request.method!!,
            path = url.encodedPath,
            query = url.queryParameterNames.associateWith { url.queryParameter(it).orEmpty() },
            headers = mapOf(
                "host" to request.headers["Host"]!!,
                "x-amz-content-sha256" to request.headers["x-amz-content-sha256"]!!,
                "x-amz-date" to request.headers["x-amz-date"]!!,
            ),
            payloadHash = request.headers["x-amz-content-sha256"]!!,
            amzDate = request.headers["x-amz-date"]!!,
            accessKey = keyId!!,
            secretKey = secret,
            region = "auto",
            service = "s3",
        )
        if (expected != auth) return xml(403, "SignatureDoesNotMatch")
        if (token != null && token.coldStarts > 0) {
            token.coldStarts--
            return xml(403, "AccessDenied")
        }
        val segments = url.pathSegments
        val bucket = segments.first()
        if (bucket !in buckets) return xml(404, "NoSuchBucket")
        val key = segments.drop(1).joinToString("/")
        val id = "$bucket/$key"
        return when (request.method) {
            "PUT" -> {
                val body = request.body?.toByteArray() ?: ByteArray(0)
                if (sha256Hex(body) != request.headers["x-amz-content-sha256"]) return xml(400, "XAmzContentSHA256Mismatch")
                objects[id] = body
                MockResponse.Builder().code(200).addHeader("ETag", "\"${sha256Hex(body).take(8)}\"").build()
            }
            "GET" -> objects[id]?.let { MockResponse.Builder().code(200).body(okio.Buffer().write(it)).build() } ?: xml(404, "NoSuchKey")
            "DELETE" -> {
                objects.remove(id)
                MockResponse.Builder().code(204).build()
            }
            else -> xml(405, "MethodNotAllowed")
        }
    }

    private fun ok(result: Any) = MockResponse.Builder().code(200)
        .body(JSONObject().put("success", true).put("errors", JSONArray()).put("messages", JSONArray()).put("result", result).toString())
        .build()

    private fun error(status: Int, code: Int, message: String) = MockResponse.Builder().code(status)
        .body(
            JSONObject().put("success", false).put("messages", JSONArray()).put("result", JSONObject.NULL)
                .put("errors", JSONArray().put(JSONObject().put("code", code).put("message", message)))
                .toString(),
        )
        .build()

    private fun xml(status: Int, code: String) =
        MockResponse.Builder().code(status).body("<Error><Code>$code</Code></Error>").build()

    companion object {
        const val ACCOUNT = "0123456789abcdef0123456789abcdef"
    }
}
