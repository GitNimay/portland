package app.portland.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/**
 * The bucket Portland makes in a user's account. Not "portland": that is where the release bridge lives for older
 * builds, whose built-in key can reach any bucket of that name in its owner's account (see the publish job in CI).
 */
const val DEFAULT_BUCKET = "portland-library"

/**
 * Opens Cloudflare's "Create API token" page with Portland's permissions already filled in, so the user only
 * presses Create and copies the token: R2 (make the bucket, read and write it), account settings read (lets the
 * app find the account by itself) and Workers AI read (pin descriptions). Keys the dashboard does not know are
 * dropped silently, so a renamed one costs a feature, never the token.
 */
val TOKEN_TEMPLATE_URL: String = run {
    val permissions = JSONArray()
        .put(JSONObject().put("key", "workers_r2").put("type", "edit"))
        .put(JSONObject().put("key", "account_settings").put("type", "read"))
        .put(JSONObject().put("key", "workers_ai").put("type", "read"))
    "https://dash.cloudflare.com/profile/api-tokens?permissionGroupKeys=" + URLEncoder.encode(permissions.toString(), "UTF-8") +
        "&accountId=*&zoneId=all&name=Portland"
}

/** Where a user turns R2 on (a one-time free-tier signup Cloudflare requires before the first bucket). */
const val R2_DASHBOARD_URL = "https://dash.cloudflare.com/?to=/:account/r2/overview"

data class CloudflareAccount(val id: String, val name: String)

/** A Cloudflare API call that failed: the HTTP status and the error codes in the reply. */
class CloudflareException(val status: Int, val codes: List<Int>, message: String) : IOException(message) {
    val unauthorized get() = status == 401 || status == 403 || 1000 in codes || 9109 in codes || 10000 in codes
    /** R2 was never turned on for this account. */
    val r2Off get() = 10042 in codes
    /** The account id does not exist or the token cannot see it. */
    val noSuchAccount get() = 7003 in codes || 7000 in codes
}

/** The few REST endpoints setup needs. Blocking; call off the main thread. */
class CloudflareApi(
    private val http: OkHttpClient,
    private val base: HttpUrl = "https://api.cloudflare.com/client/v4/".toHttpUrl(),
) {
    /** The token's id when it is a valid user token, else null (it may still be an account-owned token). */
    fun verifyUserToken(token: String): String? = verify(token, "user/tokens/verify")

    fun verifyAccountToken(token: String, accountId: String): String? = verify(token, "accounts/$accountId/tokens/verify")

    private fun verify(token: String, path: String): String? = try {
        val result = call(token, "GET", path).getJSONObject("result")
        result.getString("id").takeIf { result.optString("status") == "active" }
    } catch (e: CloudflareException) {
        if (e.unauthorized || e.status == 404) null else throw e
    }

    fun accounts(token: String): List<CloudflareAccount> {
        val result = call(token, "GET", "accounts?per_page=50").getJSONArray("result")
        return (0 until result.length()).map { i -> result.getJSONObject(i).let { CloudflareAccount(it.getString("id"), it.optString("name")) } }
    }

    fun buckets(token: String, accountId: String): List<String> {
        val result = call(token, "GET", "accounts/$accountId/r2/buckets?per_page=1000").getJSONObject("result")
        val list = result.optJSONArray("buckets") ?: JSONArray()
        return (0 until list.length()).map { list.getJSONObject(it).getString("name") }
    }

    fun createBucket(token: String, accountId: String, name: String) {
        call(token, "POST", "accounts/$accountId/r2/buckets", JSONObject().put("name", name))
    }

    /** Whether the token may run Workers AI: a free model listing, so it costs nothing to ask. */
    fun canUseAi(token: String, accountId: String): Boolean = try {
        call(token, "GET", "accounts/$accountId/ai/models/search?per_page=1")
        true
    } catch (e: CloudflareException) {
        false
    }

    private fun call(token: String, method: String, path: String, body: JSONObject? = null): JSONObject {
        val request = Request.Builder()
            .url(base.toString() + path)
            .header("Authorization", "Bearer $token")
            .method(method, body?.toString()?.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { r ->
            val text = r.body.string()
            val json = runCatching { JSONObject(text) }.getOrNull()
            if (r.isSuccessful && json?.optBoolean("success") == true) return json
            val errors = json?.optJSONArray("errors") ?: JSONArray()
            val codes = (0 until errors.length()).mapNotNull { errors.optJSONObject(it)?.optInt("code") }
            val message = (0 until errors.length()).joinToString("; ") { errors.optJSONObject(it)?.optString("message").orEmpty() }
            throw CloudflareException(r.code, codes, "$method $path -> ${r.code} $codes $message")
        }
    }
}

/** Why connecting failed, in words for the user. */
enum class SetupProblem(val message: String) {
    BadToken("That token didn't work. Copy it again from Cloudflare."),
    NoPermission("This token can't use R2. Create one with the button above."),
    R2Off("R2 isn't turned on in this Cloudflare account yet."),
    NoSuchAccount("Couldn't find that account. Check the account ID."),
    BucketUnreachable("Couldn't read and write the bucket. Check the keys and the bucket name."),
    Offline("No connection. Try again when you're online."),
}

sealed interface SetupResult {
    data class Connected(val config: R2Config) : SetupResult
    /** The token sees several accounts: the user picks one, then setup runs again with it. */
    data class PickAccount(val accounts: List<CloudflareAccount>) : SetupResult
    /** The token cannot list accounts (no account settings permission): the user types the account ID. */
    data object NeedAccountId : SetupResult
    data class Failed(val problem: SetupProblem) : SetupResult
}

/**
 * Connects a bucket from what the user pasted, then proves it works with a write, read and delete before saying
 * yes. Nothing is saved here; the caller stores the config it returns.
 */
class R2Setup(
    private val api: CloudflareApi,
    private val base: OkHttpClient,
    private val endpointFor: (R2Config) -> HttpUrl = R2Config::endpoint,
    private val retryDelayMs: Long = 2000,
) {
    /**
     * One Cloudflare API token does it all: its id and the SHA-256 of its value are S3 keys
     * (developers.cloudflare.com/r2/api/tokens), and the same token finds the account and makes the bucket.
     */
    suspend fun withToken(rawToken: String, accountId: String? = null, bucket: String = DEFAULT_BUCKET): SetupResult = guard {
        val token = rawToken.trim()
        if (token.isEmpty() || token.any { it.isWhitespace() }) return@guard SetupResult.Failed(SetupProblem.BadToken)
        var tokenId = api.verifyUserToken(token)
        val account = accountId ?: run {
            val accounts = try {
                api.accounts(token)
            } catch (e: CloudflareException) {
                // Refused outright and not a user token either: the token itself is wrong.
                if (e.unauthorized && tokenId == null) return@guard SetupResult.Failed(SetupProblem.BadToken)
                if (e.unauthorized) emptyList() else throw e
            }
            when (accounts.size) {
                1 -> accounts.single().id
                0 -> return@guard SetupResult.NeedAccountId // a valid token without account settings read
                else -> return@guard SetupResult.PickAccount(accounts)
            }
        }
        if (tokenId == null) tokenId = api.verifyAccountToken(token, account) ?: return@guard SetupResult.Failed(SetupProblem.BadToken)

        // A token limited to one bucket's objects cannot list buckets; it can still be used if that bucket exists.
        val listed = try {
            api.buckets(token, account)
        } catch (e: CloudflareException) {
            when {
                e.r2Off -> return@guard SetupResult.Failed(SetupProblem.R2Off)
                e.noSuchAccount -> return@guard SetupResult.Failed(SetupProblem.NoSuchAccount)
                e.unauthorized -> null
                else -> throw e
            }
        }
        if (listed != null && bucket !in listed) {
            try {
                api.createBucket(token, account, bucket)
            } catch (e: CloudflareException) {
                when {
                    e.r2Off -> return@guard SetupResult.Failed(SetupProblem.R2Off)
                    e.unauthorized -> return@guard SetupResult.Failed(SetupProblem.NoPermission)
                    else -> throw e
                }
            }
        }
        val config = R2Config(account, bucket, tokenId, sha256Hex(token.toByteArray()), apiToken = token)
        if (!works(config)) return@guard SetupResult.Failed(if (listed == null) SetupProblem.NoPermission else SetupProblem.BucketUnreachable)
        SetupResult.Connected(config.copy(captions = api.canUseAi(token, account)))
    }

    /** For people who already have R2 S3 keys (R2 > Manage API tokens in the dashboard). */
    suspend fun withKeys(accountId: String, bucket: String, accessKeyId: String, secretAccessKey: String): SetupResult = guard {
        val config = R2Config(accountId.trim(), bucket.trim(), accessKeyId.trim(), secretAccessKey.trim())
        if (!ACCOUNT_ID.matches(config.accountId)) return@guard SetupResult.Failed(SetupProblem.NoSuchAccount)
        if (config.bucket.isEmpty() || config.accessKeyId.isEmpty() || config.secretAccessKey.isEmpty()) {
            return@guard SetupResult.Failed(SetupProblem.BucketUnreachable)
        }
        if (works(config)) SetupResult.Connected(config) else SetupResult.Failed(SetupProblem.BucketUnreachable)
    }

    /**
     * Writes, reads back and deletes a small object. A token made seconds ago can be refused for a little while
     * as it spreads across Cloudflare, so refusals are retried a few times before they count.
     */
    internal suspend fun works(config: R2Config): Boolean {
        val client = R2Client(config, endpointFor(config), base)
        val probe = "portland-probe/${System.nanoTime()}".encodeToByteArray()
        repeat(PROBE_ATTEMPTS) { attempt ->
            try {
                client.putBytes(PROBE_KEY, probe, "text/plain")
                val back = client.get(PROBE_KEY)
                client.delete(PROBE_KEY)
                return back != null && back.contentEquals(probe)
            } catch (e: R2Exception) {
                if (e.code != 401 && e.code != 403) return false
                if (attempt < PROBE_ATTEMPTS - 1) delay(retryDelayMs)
            }
        }
        return false
    }

    private suspend fun guard(block: suspend () -> SetupResult): SetupResult = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: CloudflareException) {
            SetupResult.Failed(if (e.unauthorized) SetupProblem.BadToken else SetupProblem.Offline)
        } catch (e: IOException) {
            SetupResult.Failed(SetupProblem.Offline)
        }
    }

    companion object {
        const val PROBE_KEY = ".portland/probe"
        const val PROBE_ATTEMPTS = 6
        private val ACCOUNT_ID = Regex("[0-9a-f]{32}")

        /** The 32-hex account ID in whatever was pasted: the ID itself, a dashboard URL or the S3 endpoint. */
        fun accountIdIn(text: String): String? = Regex("(?<![0-9a-f])[0-9a-f]{32}(?![0-9a-f])").find(text.lowercase())?.value
    }
}
