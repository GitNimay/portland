package app.portland.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import app.portland.App
import java.util.concurrent.TimeUnit

/**
 * Mirrors the local library to R2. Bursts of edits are debounced in-process, then runs are chained
 * (never replaced) so a big video upload in flight is not cancelled by a later edit.
 */
object Sync {
    private val main = Handler(Looper.getMainLooper())
    private val token = Any()

    fun schedule(context: Context) {
        if (!R2.enabled) return
        val app = context.applicationContext
        main.removeCallbacksAndMessages(token)
        main.postDelayed({ enqueue(app) }, token, 2000)
    }

    private fun enqueue(context: Context) {
        val work = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("sync", ExistingWorkPolicy.APPEND_OR_REPLACE, work)
    }
}

class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val store = (applicationContext as App).store
        return try {
            uploadPins(store)
            purgeDeleted(store)
            syncIndex(store)
            Result.success()
        } catch (e: Exception) {
            Log.w("Sync", "sync failed (attempt $runAttemptCount)", e)
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }

    private suspend fun uploadPins(store: Store) {
        for (pin in store.library.value.pins.filter { !it.synced }) {
            val media = store.mediaFile(pin)
            if (!media.exists()) continue // gone (or still importing); a later run retries
            val type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(pin.ext.lowercase())
                ?: "application/octet-stream"
            R2.put(pin.mediaKey, media, type)
            store.thumbFile(pin).takeIf { it.exists() }?.let { R2.put(pin.thumbKey, it, "image/webp") }

            var gone = false
            store.update(sync = false) { lib ->
                if (lib.pins.none { it.id == pin.id }) {
                    gone = true
                    lib
                } else {
                    lib.copy(pins = lib.pins.map { if (it.id == pin.id) it.copy(synced = true) else it })
                }
            }
            if (gone) {
                R2.delete(pin.mediaKey)
                R2.delete(pin.thumbKey)
            }
        }
    }

    private suspend fun purgeDeleted(store: Store) {
        for (key in store.library.value.purge) {
            R2.delete(key)
            store.update(sync = false) { it.copy(purge = it.purge - key) }
        }
    }

    private suspend fun syncIndex(store: Store) {
        val remote = R2.get("index.json")?.let { bytes ->
            runCatching { Library.fromJson(bytes.decodeToString()) }
                .onFailure { Log.w("Sync", "remote index unreadable, ignoring", it) }
                .getOrNull()
        } ?: Library()
        store.update(sync = false) { Library.merge(it, remote) }
        val json = store.library.value.toJson(forRemote = true).encodeToByteArray()
        R2.putBytes("index.json", json, "application/json")
    }
}
