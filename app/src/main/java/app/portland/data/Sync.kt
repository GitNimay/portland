package app.portland.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Mirrors the library to the connected R2 bucket, then settles what stays on the phone (see [CloudState]).
 * Bursts of edits are debounced in-process, then runs are chained (never replaced) so a big video upload in
 * flight is not cancelled by a later edit.
 */
object Sync {
    const val WORK = "sync"
    private val main = Handler(Looper.getMainLooper())
    private val token = Any()

    fun schedule(context: Context) {
        Cloud.load(context)
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
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, work)
    }
}

class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        Cloud.load(applicationContext)
        if (!R2.enabled) return Result.success() // disconnected since this run was queued
        val store = (applicationContext as App).store
        return try {
            uploadPins(store)
            purgeDeleted(store)
            // Only a library known to be complete may decide what is an orphan, so no sweep without a readable index.
            if (syncIndex(store)) {
                runCatching { sweepOrphans(store) }.onFailure { if (it is CancellationException) throw it; Log.w("Sync", "orphan sweep failed", it) } // housekeeping; never fails the sync
            }
            settleLocal(store)
            store.indexColors() // pins that just arrived from another install
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Sync", "sync failed (attempt $runAttemptCount)", e)
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }

    private suspend fun uploadPins(store: Store) {
        for (pin in store.library.value.pins.filter { !it.synced }) {
            val media = store.mediaFile(pin)
            if (!media.exists()) continue // gone (or still importing); a later run retries
            val type = pin.mimeType
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

    /** Backup mode brings every pin onto the phone; cloud mode frees the phone copies that are safely in R2. */
    private suspend fun settleLocal(store: Store) {
        val pins = store.library.value.pins
        val local = withContext(Dispatchers.IO) { pins.filter { store.mediaFile(it).exists() }.mapTo(HashSet()) { it.id } }
        if (Cloud.state.value.keepLocal) {
            // One pin at a time, so one bad download never holds up the rest; the run retries if any failed.
            var failed = 0
            for (pin in CloudPolicy.toFetch(pins) { it.id in local }) {
                try {
                    if (!store.fetchLocal(pin)) Log.w("Sync", "${pin.mediaKey} is missing from R2; nothing to download")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed++
                    Log.w("Sync", "could not download ${pin.id}", e)
                }
            }
            if (failed > 0) throw java.io.IOException("$failed pins did not download")
        } else {
            val freed = CloudPolicy.toEvict(pins) { it.id in local }
            for (pin in freed) store.evictLocal(pin)
            if (freed.isNotEmpty()) Log.i("Sync", "freed ${freed.size} phone copies now in R2")
        }
    }

    /** Returns true when the remote index was read, so the merged library is the whole truth. */
    private suspend fun syncIndex(store: Store): Boolean {
        repeat(3) { attempt ->
            val fetched = R2.getWithEtag("index.json")
            val read = fetched?.first?.let { bytes ->
                runCatching { Library.fromJson(bytes.decodeToString()) }
                    .onFailure { Log.w("Sync", "remote index unreadable, ignoring", it) }
                    .getOrNull()
            }
            val remote = read ?: Library()
            val before = store.library.value.pins.mapTo(HashSet()) { it.id }
            store.update(sync = false) { Library.merge(it, remote) }
            // Pins deleted on another install: their local copies go too.
            store.dropFiles(before - store.library.value.pins.mapTo(HashSet()) { it.id })
            val json = store.library.value.toJson(forRemote = true).encodeToByteArray()
            if (R2.putBytesIfMatch("index.json", json, "application/json", fetched?.second)) {
                return read != null
            }
            Log.i("Sync", "index.json changed concurrently, retrying merge (${attempt + 1}/3)")
        }
        throw java.io.IOException("PUT index.json -> 412 conflict after retries")
    }

    /**
     * Makes R2 hold exactly the live library: deletes any media or thumbnail that no pin uses. That catches
     * what [purgeDeleted] cannot see, e.g. an upload that finished just before the app was killed and so never
     * got marked synced, then was deleted. Objects of deleted pins go at once; other unknown objects only after
     * a grace period, since another install may be uploading a pin its index does not list yet.
     */
    private suspend fun sweepOrphans(store: Store) {
        val lib = store.library.value
        val stale = orphans(lib, R2.list("media/") + R2.list("thumbs/"), System.currentTimeMillis())
        for (key in stale) R2.delete(key)
        if (stale.isNotEmpty()) Log.i("Sync", "swept ${stale.size} orphaned objects")
    }
}

private const val ORPHAN_GRACE_MS = 24 * 60 * 60 * 1000L

/** R2 keys in [objects] (key, lastModified) that no pin in [lib] uses and that are safe to delete [now]. */
internal fun orphans(lib: Library, objects: List<Pair<String, Long>>, now: Long): List<String> {
    val live = lib.pins.flatMapTo(HashSet()) { listOf(it.mediaKey, it.thumbKey) }
    return objects.filter { (key, modified) ->
        val id = key.substringAfterLast('/').substringBeforeLast('.')
        key !in live && (id in lib.deleted || now - modified > ORPHAN_GRACE_MS)
    }.map { it.first }
}
