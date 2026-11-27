package app.portland.data

import android.content.Context
import android.content.SharedPreferences
import androidx.work.WorkManager
import app.portland.store
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * Where pins live, as Google Photos does it:
 * - not connected: on this phone only (the default; nothing leaves the device);
 * - connected, [keepLocal] off: R2 is home. Each pin uploads, then its phone copy goes; the viewer streams it
 *   back on demand (with a cache). Thumbnails stay so the grid opens at once, even offline;
 * - connected, [keepLocal] on: backup. Every pin stays on the phone and a copy goes to R2, and pins added on
 *   another install download here too.
 */
data class CloudState(
    val config: R2Config? = null,
    val keepLocal: Boolean = false,
    val nudgeNever: Boolean = false,
    val nudgeAfter: Long = 0L,
    /** Pins downloading to this phone before it stops using R2. */
    val moving: Transfer? = null,
) {
    val connected get() = config != null
}

data class Transfer(val done: Int, val total: Int)

/** The rules, free of Android so they are unit-testable. */
object CloudPolicy {
    const val SNOOZE_MS = 7 * 24 * 60 * 60 * 1000L

    /** The small "back up to R2" card: until connected, unless told never, and not while snoozed. */
    fun showNudge(state: CloudState, now: Long) = !state.connected && !state.nudgeNever && now >= state.nudgeAfter

    /** Pins safely in R2 whose phone copy can go (cloud mode). */
    fun toEvict(pins: List<Pin>, hasLocal: (Pin) -> Boolean) = pins.filter { it.synced && hasLocal(it) }

    /** Pins that live only in R2 and must come to this phone (backup mode, or before leaving R2). */
    fun toFetch(pins: List<Pin>, hasLocal: (Pin) -> Boolean) = pins.filter { it.synced && !hasLocal(it) }

    /**
     * A different bucket than last time: every pin this phone holds uploads to it, and deletions queued for
     * the old bucket are dropped. Pins with no phone copy keep their flag; they are only in the old bucket.
     */
    fun forNewBucket(lib: Library, hasLocal: (Pin) -> Boolean): Library = lib.copy(
        pins = lib.pins.map { if (it.synced && hasLocal(it)) it.copy(synced = false) else it },
        purge = emptyList(),
    )
}

object Cloud {
    private const val FILE = "portland_cloud"
    private const val KEY_CONFIG = "config"
    private const val KEY_KEEP_LOCAL = "keep_local"
    private const val KEY_NUDGE_NEVER = "nudge_never"
    private const val KEY_NUDGE_AFTER = "nudge_after"
    private const val KEY_LAST_BUCKET = "last_bucket"

    private val lock = Any()
    @Volatile private var prefs: SharedPreferences? = null
    private val _state = MutableStateFlow(CloudState())

    val state: StateFlow<CloudState> = _state.asStateFlow()

    /**
     * Reads the saved bucket and points [R2] at it. Idempotent and cheap after the first call. The store calls it
     * on its loading thread, so it is done before any pin's URL is made; workers call it as they start.
     */
    fun load(context: Context) {
        if (prefs != null) return
        synchronized(lock) {
            if (prefs != null) return
            val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            val config = p.getString(KEY_CONFIG, null)
                ?.let(Vault::open)
                ?.let { runCatching { R2Config.fromJson(it) }.getOrNull() }
            R2.configure(config)
            _state.value = CloudState(
                config = config,
                keepLocal = p.getBoolean(KEY_KEEP_LOCAL, false),
                nudgeNever = p.getBoolean(KEY_NUDGE_NEVER, false),
                nudgeAfter = p.getLong(KEY_NUDGE_AFTER, 0L),
            )
            prefs = p // published last: non-null means the state above is loaded
        }
    }

    private fun prefs(context: Context): SharedPreferences {
        load(context)
        return checkNotNull(prefs)
    }

    /** Saves a bucket that [R2Setup] proved works and starts using it. */
    suspend fun connect(context: Context, config: R2Config, keepLocal: Boolean) {
        val app = context.applicationContext
        val store = app.store
        val p = prefs(app)
        val sameBucket = p.getString(KEY_LAST_BUCKET, null) == config.identity
        withContext(Dispatchers.IO) {
            p.edit()
                .putString(KEY_CONFIG, Vault.seal(config.toJson()))
                .putBoolean(KEY_KEEP_LOCAL, keepLocal)
                .putString(KEY_LAST_BUCKET, config.identity)
                .commit()
        }
        R2.configure(config)
        _state.update { it.copy(config = config, keepLocal = keepLocal) }
        if (!sameBucket) {
            val local = withContext(Dispatchers.IO) { store.library.value.pins.filter { store.mediaFile(it).exists() }.mapTo(HashSet()) { it.id } }
            store.update(sync = false) { lib -> CloudPolicy.forNewBucket(lib) { it.id in local } }
        }
        store.forgetModels() // pins without a phone copy now load from this bucket
        Sync.schedule(app)
        Captions.schedule(app)
    }

    fun setKeepLocal(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_KEEP_LOCAL, on).apply()
        _state.update { it.copy(keepLocal = on) }
        Sync.schedule(context) // downloads everything (on) or frees the phone copies (off)
    }

    /** How many pins live only in R2 right now: what switching to this phone would download. */
    suspend fun remoteOnly(context: Context): List<Pin> = withContext(Dispatchers.IO) {
        val store = context.store
        CloudPolicy.toFetch(store.library.value.pins) { store.mediaFile(it).exists() }
    }

    /**
     * Downloads every pin that lives only in R2, then stops using R2 (the bucket is left as it is). Returns how
     * many pins could not be downloaded; when that is not 0 nothing changes, unless [force] (the bucket is gone
     * or the keys no longer work), which disconnects without them.
     */
    suspend fun useThisPhoneOnly(context: Context, force: Boolean = false): Int {
        val app = context.applicationContext
        val store = app.store
        if (!force) {
            val todo = remoteOnly(app)
            var failed = 0
            _state.update { it.copy(moving = Transfer(0, todo.size)) }
            try {
                todo.forEachIndexed { i, pin ->
                    try {
                        store.fetchLocal(pin)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed++
                    }
                    _state.update { it.copy(moving = Transfer(i + 1, todo.size)) }
                }
            } finally {
                _state.update { it.copy(moving = null) }
            }
            if (failed > 0) return failed
        }
        WorkManager.getInstance(app).cancelUniqueWork(Sync.WORK)
        // The last bucket is remembered: reconnecting the same one later uploads only what is new.
        withContext(Dispatchers.IO) { prefs(app).edit().remove(KEY_CONFIG).commit() }
        R2.configure(null)
        _state.update { it.copy(config = null) }
        store.forgetModels()
        return 0
    }

    fun snoozeNudge(context: Context, now: Long = System.currentTimeMillis()) {
        val after = now + CloudPolicy.SNOOZE_MS
        prefs(context).edit().putLong(KEY_NUDGE_AFTER, after).apply()
        _state.update { it.copy(nudgeAfter = after) }
    }

    fun neverNudge(context: Context) {
        prefs(context).edit().putBoolean(KEY_NUDGE_NEVER, true).apply()
        _state.update { it.copy(nudgeNever = true) }
    }
}
