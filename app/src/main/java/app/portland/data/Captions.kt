package app.portland.data

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.portland.App
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Describes pins with Cloudflare Workers AI, from the small thumbnail so each call stays around a second. */
object Captions {
    // Benchmarked against llama-4-scout, gemma-4 and moondream 3.1: fastest (~1.2s) and the most concrete wording.
    private const val MODEL = "@cf/mistralai/mistral-small-3.1-24b-instruct"
    private const val PROMPT = "Caption this saved inspiration image in under 12 words: what it shows and its visual style. " +
        "Plain sentence fragment, no preamble, no quotes, no trailing period."

    /** Runs on the user’s own account, with the token they connected R2 with, when that token may use Workers AI. */
    val enabled: Boolean get() = R2.config?.let { it.captions && it.apiToken != null } == true

    /** Captions every pin that has none yet. Chained, so runs never overlap and nothing queued is dropped. */
    fun schedule(context: Context) {
        Cloud.load(context)
        if (!enabled) return
        val work = OneTimeWorkRequestBuilder<CaptionWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("caption", ExistingWorkPolicy.APPEND_OR_REPLACE, work)
    }

    private val http by lazy { R2.http.newBuilder().callTimeout(20, TimeUnit.SECONDS).build() }

    /** Returns the caption ("" when the model gave nothing usable). Throws IOException when worth retrying. */
    suspend fun describe(webp: ByteArray): String = withContext(Dispatchers.IO) {
        val config = R2.config?.takeIf { it.captions } ?: throw IOException("no Workers AI token connected")
        val image = "data:image/webp;base64," + Base64.encodeToString(webp, Base64.NO_WRAP)
        val body = JSONObject()
            .put("max_tokens", 40)
            .put("temperature", 0.2)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", JSONArray()
                .put(JSONObject().put("type", "text").put("text", PROMPT))
                .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", image))))))
        val request = Request.Builder()
            .url("https://api.cloudflare.com/client/v4/accounts/${config.accountId}/ai/run/$MODEL")
            .header("Authorization", "Bearer ${config.apiToken}")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        // Shares R2's connection pool with a bounded call timeout; the signing interceptor ignores non-R2 hosts.
        http.newCall(request).execute().use { r ->
            when {
                r.isSuccessful -> parseCaption(r.body.string())
                r.code in PERMANENT -> "" // this image will never work; don't retry it forever
                else -> throw IOException("Workers AI ${r.code}")
            }
        }
    }

    private val PERMANENT = setOf(400, 413, 415, 422)
}

private val WS_REGEX = Regex("\\s+")

/** Pulls `result.response` out of a Workers AI reply and tidies it into a short caption. */
internal fun parseCaption(json: String): String =
    cleanCaption(JSONObject(json).optJSONObject("result")?.optString("response").orEmpty())

internal fun cleanCaption(s: String): String =
    s.replace(WS_REGEX, " ").trim().trim('"', '“', '”', '\'').trimEnd('.', ' ').take(140).trimEnd()

class CaptionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Cloud.load(applicationContext)
        if (!Captions.enabled) return Result.success() // disconnected since this run was queued
        val store = (applicationContext as App).store
        val todo = store.library.value.pins.filter { it.caption == null }
        val retry = AtomicBoolean(false)
        val gate = Semaphore(4)
        coroutineScope {
            for (pin in todo) launch {
                gate.withPermit {
                    try {
                        val thumb = store.thumbFile(pin).takeIf { it.exists() }?.readBytes()
                            ?: (if (R2.enabled && pin.synced) R2.get(pin.thumbKey) else null)
                            ?: return@withPermit // thumb not available yet; a later run picks it up
                        store.setCaption(pin.id, Captions.describe(thumb))
                    } catch (e: CancellationException) {
                        throw e // stopped by the system: not a verdict on this pin
                    } catch (e: IOException) {
                        Log.w("Captions", "will retry ${pin.id}", e)
                        retry.set(true)
                    } catch (e: Exception) {
                        Log.w("Captions", "giving up on ${pin.id}", e)
                        store.setCaption(pin.id, "")
                    }
                }
            }
        }
        return if (retry.get() && runAttemptCount < 5) Result.retry() else Result.success()
    }
}
