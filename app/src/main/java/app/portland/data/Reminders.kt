package app.portland.data

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.portland.App
import app.portland.MainActivity
import app.portland.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** Persisted reminder budget, anti-repeat history, and user preference. */
data class ReminderSnapshot(
    val enabled: Boolean = true,
    val lastSentAt: Long = 0L,
    val lastAppActiveAt: Long = 0L,
    val sentTimestamps: List<Long> = emptyList(),
    val consecutiveIgnores: Int = 0,
    val pendingPinId: String? = null,
    val recentPinIds: List<String> = emptyList(),
    val nextScheduledAt: Long = 0L,
    val promptDismissed: Boolean = false,
)

enum class DeferReason { ActiveRecently, Cooldown, DailyCap, QuietHours }

sealed interface Eligibility {
    data object Disabled : Eligibility
    data object NotEnoughContent : Eligibility
    data class Defer(val deferUntilMs: Long, val effectiveIgnores: Int, val reason: DeferReason) : Eligibility
    data class Send(val effectiveIgnores: Int) : Eligibility
}

data class ReminderCopy(val title: String, val text: String)

/**
 * Pure, unit-testable Pinterest-style notification gatekeeper, candidate selector, and copy formatter.
 *
 * Follows Pinterest's "less is more" playbook:
 *  - Decouples candidate ranking from volume control so high-scoring pins never cause spam spikes.
 *  - Targets up to 2 randomized daytime reminders per day (never at night, 21:00–09:00).
 *  - Suppresses reminders when the user was recently active in the app, enforces minimum spacing
 *    between reminders, and applies gentle backoff when previous reminders are dismissed or ignored.
 */
object ReminderPolicy {
    const val MIN_PINS = 2
    const val MAX_PER_DAY = 2
    const val MAX_RECENT_HISTORY = 24
    const val QUIET_START_HOUR = 21
    const val QUIET_END_HOUR = 9
    const val QUIET_JITTER_MINUTES = 120

    const val MINUTE_MS = 60_000L
    const val HOUR_MS = 60 * MINUTE_MS
    const val DAY_MS = 24 * HOUR_MS
    const val MIN_DEFER_MS = 15 * MINUTE_MS
    const val MIN_PIN_AGE_MS = 12 * HOUR_MS
    const val ACTIVE_SUPPRESSION_MS = 1 * HOUR_MS

    /** How often the foreground time is persisted; far below [ACTIVE_SUPPRESSION_MS] so suppression stays accurate. */
    const val ACTIVE_RECORD_INTERVAL_MS = 5 * MINUTE_MS

    /** A request further out than this means the clock moved back (or the bookkeeping is stale). */
    const val MAX_SCHEDULE_AHEAD_MS = 3 * DAY_MS
    const val BASE_COOLDOWN_MS = 4 * HOUR_MS
    const val MAX_COOLDOWN_MS = 24 * HOUR_MS
    const val MIN_DELAY_MINUTES = 240L // 4 hours
    const val MAX_DELAY_MINUTES = 480L // 8 hours

    private val WS_REGEX = Regex("\\s+")

    /** True when a foreground at [nowMs] is worth persisting; a backwards clock always is. */
    fun shouldRecordActive(lastRecordedMs: Long, nowMs: Long): Boolean =
        nowMs - lastRecordedMs !in 0 until ACTIVE_RECORD_INTERVAL_MS

    /** Counts an unopened previous notification as ignored once the base cooldown has elapsed. */
    fun effectiveIgnores(state: ReminderSnapshot, nowMs: Long): Int =
        if (state.pendingPinId != null && state.lastSentAt > 0L && nowMs - state.lastSentAt >= BASE_COOLDOWN_MS) {
            state.consecutiveIgnores + 1
        } else {
            state.consecutiveIgnores
        }

    fun cooldownMs(ignores: Int): Long = when {
        ignores <= 0 -> BASE_COOLDOWN_MS
        ignores == 1 -> 6 * HOUR_MS
        ignores == 2 -> 9 * HOUR_MS
        else -> 12 * HOUR_MS
    }

    fun backoffMultiplier(ignores: Int): Double = when {
        ignores <= 0 -> 1.0
        ignores == 1 -> 1.35
        ignores == 2 -> 1.75
        else -> 2.25
    }

    fun isQuietHour(epochMs: Long, zone: ZoneId): Boolean {
        val hour = Instant.ofEpochMilli(epochMs).atZone(zone).hour
        return hour >= QUIET_START_HOUR || hour < QUIET_END_HOUR
    }

    /** Advances [targetMs] out of quiet hours (21:00–09:00) with daytime jitter. */
    fun snapToDaytime(targetMs: Long, zone: ZoneId, random: Random = Random): Long {
        val zdt = Instant.ofEpochMilli(targetMs).atZone(zone)
        val hour = zdt.hour
        if (hour in QUIET_END_HOUR until QUIET_START_HOUR) return targetMs
        val morningDate = if (hour >= QUIET_START_HOUR) zdt.toLocalDate().plusDays(1) else zdt.toLocalDate()
        val jitterMinutes = random.nextInt(0, QUIET_JITTER_MINUTES).toLong()
        return morningDate.atTime(QUIET_END_HOUR, 0).atZone(zone).plusMinutes(jitterMinutes).toInstant().toEpochMilli()
    }

    private fun nextMorningMs(date: java.time.LocalDate, zone: ZoneId, random: Random): Long {
        val jitterMinutes = random.nextInt(0, QUIET_JITTER_MINUTES).toLong()
        return date.plusDays(1).atTime(QUIET_END_HOUR, 0).atZone(zone).plusMinutes(jitterMinutes).toInstant().toEpochMilli()
    }

    private fun countOnDate(timestamps: List<Long>, date: java.time.LocalDate, zone: ZoneId): Int =
        timestamps.count { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() == date }

    /**
     * Pulls timestamps from the future back to [nowMs]. After the clock is moved back they would otherwise keep
     * the cooldown and the foreground suppression alive for as long as the clock was moved. Persist the result so
     * the cooldown runs from the correction instead of being renewed on every evaluation.
     */
    fun sanitize(state: ReminderSnapshot, nowMs: Long): ReminderSnapshot {
        if (state.lastSentAt <= nowMs && state.lastAppActiveAt <= nowMs && state.sentTimestamps.none { it > nowMs }) return state
        return state.copy(
            lastSentAt = state.lastSentAt.coerceAtMost(nowMs),
            lastAppActiveAt = state.lastAppActiveAt.coerceAtMost(nowMs),
            sentTimestamps = state.sentTimestamps.map { it.coerceAtMost(nowMs) },
        )
    }

    fun evaluateEligibility(
        state: ReminderSnapshot,
        library: Library,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        random: Random = Random,
    ): Eligibility {
        if (!state.enabled) return Eligibility.Disabled
        if (library.pins.size < MIN_PINS) return Eligibility.NotEnoughContent

        val ignores = effectiveIgnores(state, nowMs)
        val cooldownUntil = if (state.lastSentAt > 0L) state.lastSentAt + cooldownMs(ignores) else 0L
        val activeUntil = if (state.lastAppActiveAt > 0L) state.lastAppActiveAt + ACTIVE_SUPPRESSION_MS else 0L
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val capUntil = if (countOnDate(state.sentTimestamps, today, zone) >= MAX_PER_DAY) {
            nextMorningMs(today, zone, random)
        } else {
            0L
        }

        val earliest = maxOf(cooldownUntil, activeUntil, capUntil)
        if (earliest > nowMs) {
            val reason = when (earliest) {
                capUntil -> DeferReason.DailyCap
                activeUntil -> DeferReason.ActiveRecently
                else -> DeferReason.Cooldown
            }
            val snapped = snapToDaytime(earliest.coerceAtLeast(nowMs + MIN_DEFER_MS), zone, random)
            return Eligibility.Defer(snapped, ignores, reason)
        }
        if (isQuietHour(nowMs, zone)) {
            val snapped = snapToDaytime(nowMs, zone, random).coerceAtLeast(nowMs + MIN_DEFER_MS)
            return Eligibility.Defer(snapped, ignores, DeferReason.QuietHours)
        }
        return Eligibility.Send(ignores)
    }

    /** Randomized next delay in ms (~2x/day during daytime), stretched by fatigue and capped at 2/day. */
    fun nextRandomDelayMs(
        nowMs: Long,
        state: ReminderSnapshot,
        zone: ZoneId = ZoneId.systemDefault(),
        random: Random = Random,
    ): Long {
        val baseMinutes = random.nextLong(MIN_DELAY_MINUTES, MAX_DELAY_MINUTES + 1L)
        val scaledMs = (baseMinutes * MINUTE_MS.toDouble() * backoffMultiplier(state.consecutiveIgnores))
            .toLong()
            .coerceAtMost(MAX_COOLDOWN_MS)
        var target = snapToDaytime(nowMs + scaledMs, zone, random)
        val targetDate = Instant.ofEpochMilli(target).atZone(zone).toLocalDate()
        if (countOnDate(state.sentTimestamps, targetDate, zone) >= MAX_PER_DAY) {
            target = maxOf(target, nextMorningMs(targetDate, zone, random))
        }
        return (target - nowMs).coerceAtLeast(MIN_DEFER_MS)
    }

    /**
     * Picks a pin to resurface using anti-repeat history, age preference, and quality-weighted randomness.
     * Returns null only when [pins] is empty.
     */
    fun pickPin(
        pins: List<Pin>,
        recentPinIds: List<String>,
        nowMs: Long,
        random: Random = Random,
    ): Pin? {
        if (pins.isEmpty()) return null
        val recentSet = recentPinIds.toHashSet()
        val nonRecent = pins.filter { it.id !in recentSet }
        val pool = when {
            nonRecent.isNotEmpty() -> nonRecent
            pins.size > 1 -> pins.filter { it.id != recentPinIds.firstOrNull() }.ifEmpty { pins }
            else -> pins
        }
        val mature = pool.filter { nowMs - it.createdAt >= MIN_PIN_AGE_MS }
        val candidates = mature.ifEmpty { pool }
        if (candidates.size == 1) return candidates.first()

        val weights = candidates.map { scorePin(it, nowMs) }
        val total = weights.sum()
        var draw = random.nextDouble() * total
        for (i in candidates.indices) {
            draw -= weights[i]
            if (draw <= 0.0) return candidates[i]
        }
        return candidates.last()
    }

    internal fun scorePin(pin: Pin, nowMs: Long): Double {
        var score = 1.0
        if (pin.note.isNotBlank()) score += 2.5
        if (!pin.caption.isNullOrBlank()) score += 1.5
        if (pin.boardId != null) score += 1.0
        val ageDays = ((nowMs - pin.createdAt).coerceAtLeast(0L)) / DAY_MS
        score += when {
            ageDays >= 30L -> 1.5
            ageDays >= 14L -> 1.0
            ageDays >= 3L -> 0.5
            else -> 0.0
        }
        return score
    }

    /** Formats a calm, short one-liner notification matching Portland's voice. */
    fun buildCopy(pin: Pin, boardName: String?, nowMs: Long): ReminderCopy {
        val note = cleanOneLine(pin.note, 44)
        val caption = cleanOneLine(pin.caption.orEmpty(), 44)
        val board = boardName?.let { cleanOneLine(it, 32) }?.takeIf { it.isNotEmpty() }
        val age = relativeAge(pin.createdAt, nowMs)
        return when {
            note.isNotEmpty() -> ReminderCopy(title = board ?: age, text = note)
            caption.isNotEmpty() -> ReminderCopy(title = board ?: age, text = caption)
            board != null -> ReminderCopy(title = board, text = "$age · take another look")
            else -> ReminderCopy(title = "Portland", text = "$age · take another look")
        }
    }

    internal fun relativeAge(createdAt: Long, nowMs: Long): String {
        val days = ((nowMs - createdAt).coerceAtLeast(0L)) / DAY_MS
        return when {
            days == 0L -> "Saved today"
            days == 1L -> "Saved yesterday"
            days < 7L -> "Saved $days days ago"
            days < 14L -> "Saved last week"
            days < 28L -> "Saved ${days / 7} weeks ago"
            days < 60L -> "Saved last month"
            days < 365L -> "Saved ${days / 30} months ago"
            days < 730L -> "Saved a year ago"
            else -> "Saved ${days / 365} years ago"
        }
    }

    internal fun cleanOneLine(raw: String, maxChars: Int): String {
        val collapsed = raw.replace(WS_REGEX, " ").trim().trim('"', '“', '”', '\'')
        if (collapsed.length <= maxChars) return collapsed
        val cut = collapsed.take(maxChars - 1)
        val lastSpace = cut.lastIndexOf(' ')
        val base = if (lastSpace >= maxChars / 2) cut.substring(0, lastSpace) else cut
        return base.trimEnd('.', ',', ';', ':', ' ') + "\u2026"
    }
}


/**
 * Schedules, builds, and tracks Pinterest-inspired resurfacing notifications for Portland.
 * One WorkManager chain schedules randomized daytime windows without exact alarms or spam.
 *
 * Threading: prefs and WorkManager are only touched on [io], a single background thread, so calls apply in order and the
 * main thread never waits on disk. The snapshot itself is loaded once ([warm] does it at app start).
 *
 * The chain: each [ReminderWorker] run decides, maybe posts, and always appends the next run
 * ([ExistingWorkPolicy.APPEND_OR_REPLACE], so it never cancels the run that is enqueuing it). [ensureScheduled] only
 * arms a new run when none is alive, or when asked to replace it.
 */
object Reminders {
    const val CHANNEL_ID = "pin_reminders"
    const val NOTIFICATION_ID = 1001
    const val ACTION_OPEN_PIN = "app.portland.ACTION_OPEN_PIN"
    const val EXTRA_PIN_ID = "pin_id"

    private const val TAG = "Reminders"
    private const val WORK_NAME = "portland_pin_reminder"
    private const val PREFS_NAME = "portland_reminders"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LAST_SENT = "last_sent"
    private const val KEY_LAST_ACTIVE = "last_active"
    private const val KEY_SENT_HISTORY = "sent_history"
    private const val KEY_IGNORES = "ignores"
    private const val KEY_PENDING_PIN = "pending_pin"
    private const val KEY_RECENT_PINS = "recent_pins"
    private const val KEY_NEXT_SCHEDULED = "next_scheduled"
    private const val KEY_PROMPT_DISMISSED = "prompt_dismissed"
    private const val MAX_BITMAP_PX = 640
    private const val MAX_THUMB_BYTES = 2L * 1024 * 1024
    private const val THUMB_FETCH_TIMEOUT_S = 10L

    private val lock = Any()
    private val _state = MutableStateFlow(ReminderSnapshot())
    private val stateFlow = _state.asStateFlow()

    @Volatile private var loaded = false

    /** Last foreground time handed to [recordAppActive]; lets the common case return without any work. */
    @Volatile private var lastActiveRecordedAt = 0L

    private val io = CoroutineScope(
        SupervisorJob() + Dispatchers.IO.limitedParallelism(1) +
            CoroutineExceptionHandler { _, e -> Log.e(TAG, "background reminder work failed", e) },
    )

    /** Reads the persisted snapshot on a background thread so the first UI read does not hit the disk. */
    fun warm(context: Context) {
        val app = context.applicationContext
        io.launch { ensureLoaded(app) }
    }

    fun state(context: Context): StateFlow<ReminderSnapshot> {
        ensureLoaded(context)
        return stateFlow
    }

    fun snapshot(context: Context): ReminderSnapshot = ensureLoaded(context)

    fun hasNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun notificationsEnabledInSystem(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, "Pin reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Occasional reminders of pins you saved"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    /**
     * Makes sure exactly one randomized run is pending when reminders are on (cancels it when they are off).
     * A live pending run is left alone, however overdue: it is waiting for the OS (Doze, standby), not lost.
     */
    fun ensureScheduled(context: Context, forceReplace: Boolean = false) {
        val app = context.applicationContext
        io.launch { schedule(app, forceReplace) }
    }

    private fun schedule(app: Context, forceReplace: Boolean) {
        val snap = ensureLoaded(app)
        val wm = WorkManager.getInstance(app)
        if (!snap.enabled || !hasNotificationPermission(app)) {
            wm.cancelUniqueWork(WORK_NAME)
            if (snap.nextScheduledAt != 0L) updateState(app) { it.copy(nextScheduledAt = 0L) }
            return
        }
        val now = System.currentTimeMillis()
        // Far in the future = the clock moved back since it was armed (WorkManager compares wall-clock time).
        val plausible = snap.nextScheduledAt > 0L && snap.nextScheduledAt - now <= ReminderPolicy.MAX_SCHEDULE_AHEAD_MS
        if (!forceReplace && plausible && chainAlive(wm)) return
        arm(app, now + ReminderPolicy.nextRandomDelayMs(now, snap), ExistingWorkPolicy.REPLACE)
    }

    private fun chainAlive(wm: WorkManager): Boolean =
        runCatching { wm.getWorkInfosForUniqueWork(WORK_NAME).get().any { !it.state.isFinished } }.getOrDefault(false)

    /** Enqueues the next run at [targetEpochMs] (at least [ReminderPolicy.MIN_DEFER_MS] away) and remembers when. */
    private fun arm(app: Context, targetEpochMs: Long, policy: ExistingWorkPolicy) {
        val now = System.currentTimeMillis()
        val delayMs = (targetEpochMs - now).coerceAtLeast(ReminderPolicy.MIN_DEFER_MS)
        val work = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(WORK_NAME, policy, work)
        updateState(app) { it.copy(nextScheduledAt = now + delayMs) }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        updateState(app) { it.copy(enabled = enabled, promptDismissed = true) }
        ensureScheduled(app, forceReplace = enabled)
    }

    fun onPermissionResult(context: Context, granted: Boolean) {
        val app = context.applicationContext
        updateState(app) { it.copy(enabled = granted, promptDismissed = true) }
        ensureScheduled(app, forceReplace = granted)
    }

    /** Called on every foreground; persisted at most every [ReminderPolicy.ACTIVE_RECORD_INTERVAL_MS], off the main thread. */
    fun recordAppActive(context: Context, nowMs: Long = System.currentTimeMillis()) {
        if (!ReminderPolicy.shouldRecordActive(lastActiveRecordedAt, nowMs)) return
        lastActiveRecordedAt = nowMs
        val app = context.applicationContext
        io.launch { markActive(app, nowMs) }
    }

    internal fun markActive(app: Context, nowMs: Long) {
        updateState(app) { s ->
            if (ReminderPolicy.shouldRecordActive(s.lastAppActiveAt, nowMs)) s.copy(lastAppActiveAt = nowMs) else s
        }
    }

    fun recordOpened(context: Context, pinId: String?) {
        val app = context.applicationContext
        io.launch {
            updateState(app) { s ->
                s.copy(
                    consecutiveIgnores = 0,
                    pendingPinId = if (pinId == null || s.pendingPinId == pinId) null else s.pendingPinId,
                )
            }
            NotificationManagerCompat.from(app).cancel(NOTIFICATION_ID)
        }
    }

    /** [onDone] runs once the state is saved (a receiver uses it to finish its `goAsync`). */
    fun recordDismissed(context: Context, pinId: String?, onDone: () -> Unit = {}) {
        val app = context.applicationContext
        io.launch {
            try {
                updateState(app) { s ->
                    val ours = pinId == null || s.pendingPinId == pinId
                    s.copy(
                        consecutiveIgnores = if (ours) s.consecutiveIgnores + 1 else s.consecutiveIgnores,
                        pendingPinId = if (ours) null else s.pendingPinId,
                    )
                }
            } finally {
                onDone()
            }
        }
    }

    /**
     * One due run: decide, maybe post, and return when the next run should happen (null = pick a random delay from the
     * updated state). Failures in here are the caller's to survive; it must still re-arm.
     */
    internal suspend fun runDue(app: App, snap: ReminderSnapshot, nowMs: Long): Long? {
        val lib = app.store.library.value
        return when (val decision = ReminderPolicy.evaluateEligibility(snap, lib, nowMs)) {
            is Eligibility.Disabled, is Eligibility.NotEnoughContent -> null
            is Eligibility.Defer -> {
                updateIgnores(app, decision.effectiveIgnores)
                decision.deferUntilMs
            }
            is Eligibility.Send -> {
                val pin = ReminderPolicy.pickPin(lib.pins, snap.recentPinIds, nowMs)
                if (pin != null && postForPin(app, app.store, lib, pin, nowMs)) {
                    recordSent(app, pin.id, nowMs, decision.effectiveIgnores)
                } else {
                    updateIgnores(app, decision.effectiveIgnores)
                }
                null
            }
        }
    }

    /** Appends the next run after the one that is executing now (or replaces a dead chain). */
    internal fun armNext(app: Context, nextAtMs: Long?, nowMs: Long) {
        val target = nextAtMs ?: (nowMs + ReminderPolicy.nextRandomDelayMs(nowMs, snapshot(app)))
        arm(app, target, ExistingWorkPolicy.APPEND_OR_REPLACE)
    }

    /** Persists [ReminderPolicy.sanitize] (a clock moved back leaves timestamps in the future) and returns the result. */
    internal fun repairClock(app: Context, nowMs: Long): ReminderSnapshot =
        updateState(app) { ReminderPolicy.sanitize(it, nowMs) }

    internal fun disarm(app: Context) {
        if (snapshot(app).nextScheduledAt != 0L) updateState(app) { it.copy(nextScheduledAt = 0L) }
    }

    internal suspend fun postForPin(
        context: Context,
        store: Store,
        lib: Library,
        pin: Pin,
        nowMs: Long,
    ): Boolean {
        if (!hasNotificationPermission(context)) return false
        ensureChannel(context)
        // Blocked app or channel: nothing would be shown, so do not count it as sent.
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return false
        if (nm.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        val boardName = lib.boards.firstOrNull { it.id == pin.boardId }?.name
        val copy = ReminderPolicy.buildCopy(pin, boardName, nowMs)
        val bitmap = loadNotificationBitmap(store, pin)
        val notification = try {
            buildNotification(context, pin, copy, bitmap)
        } catch (e: Exception) {
            Log.w(TAG, "notification with picture failed for ${pin.id}; posting text only", e)
            buildNotification(context, pin, copy, null)
        }
        return try {
            nm.notify(NOTIFICATION_ID, notification)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "POST_NOTIFICATIONS denied at notify time", e)
            false
        }
    }

    internal fun buildNotification(
        context: Context,
        pin: Pin,
        copy: ReminderCopy,
        bitmap: Bitmap?,
    ): Notification {
        // One notification id, so one PendingIntent per kind: UPDATE_CURRENT keeps its extras on the latest pin.
        val openIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_PIN
            putExtra(EXTRA_PIN_ID, pin.id)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPending = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val dismissIntent = Intent(context, ReminderDismissReceiver::class.java).apply {
            putExtra(EXTRA_PIN_ID, pin.id)
        }
        val dismissPending = PendingIntent.getBroadcast(
            context,
            NOTIFICATION_ID,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // A new reminder replaces the previous one (same id) and must still alert, so no setOnlyAlertOnce. The category
        // is RECOMMENDATION, not REMINDER: reminders can break through Do Not Disturb, a resurfaced pin should not.
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(copy.title)
            .setContentText(copy.text)
            .setContentIntent(openPending)
            .setDeleteIntent(dismissPending)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)

        if (bitmap != null) {
            builder.setLargeIcon(roundThumb(bitmap, context))
            val style = NotificationCompat.BigPictureStyle()
                .bigPicture(bitmap)
                .bigLargeIcon(null as Bitmap?)
                .setBigContentTitle(copy.title)
                .setSummaryText(copy.text)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                style.showBigPictureWhenCollapsed(true)
            }
            builder.setStyle(style)
        }
        return builder.build()
    }

    internal fun recordSent(context: Context, pinId: String, nowMs: Long, ignores: Int) {
        updateState(context.applicationContext) { s ->
            val history = (s.sentTimestamps + nowMs).filter { nowMs - it < 2 * ReminderPolicy.DAY_MS }
            val recent = (listOf(pinId) + s.recentPinIds.filterNot { it == pinId }).take(ReminderPolicy.MAX_RECENT_HISTORY)
            s.copy(
                lastSentAt = nowMs,
                sentTimestamps = history,
                consecutiveIgnores = ignores,
                pendingPinId = pinId,
                recentPinIds = recent,
            )
        }
    }

    internal fun updateIgnores(context: Context, ignores: Int) {
        updateState(context.applicationContext) { s ->
            if (ignores > s.consecutiveIgnores) {
                s.copy(consecutiveIgnores = ignores, pendingPinId = null)
            } else {
                s
            }
        }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun ensureLoaded(context: Context): ReminderSnapshot {
        if (loaded) return _state.value
        synchronized(lock) {
            if (!loaded) {
                _state.value = readFromPrefs(prefs(context))
                loaded = true
            }
            return _state.value
        }
    }

    private fun updateState(context: Context, transform: (ReminderSnapshot) -> ReminderSnapshot): ReminderSnapshot {
        synchronized(lock) {
            val current = ensureLoaded(context)
            val next = transform(current)
            if (next != current) {
                writeToPrefs(prefs(context), current, next)
                _state.value = next
            }
            return next
        }
    }

    private fun readFromPrefs(p: SharedPreferences): ReminderSnapshot {
        val sent = p.getString(KEY_SENT_HISTORY, "")
            .orEmpty()
            .split(',')
            .mapNotNull { it.toLongOrNull() }
        val recent = p.getString(KEY_RECENT_PINS, "")
            .orEmpty()
            .split(',')
            .filter { it.isNotBlank() }
        return ReminderSnapshot(
            enabled = p.getBoolean(KEY_ENABLED, true),
            lastSentAt = p.getLong(KEY_LAST_SENT, 0L),
            lastAppActiveAt = p.getLong(KEY_LAST_ACTIVE, 0L),
            sentTimestamps = sent,
            consecutiveIgnores = p.getInt(KEY_IGNORES, 0),
            pendingPinId = p.getString(KEY_PENDING_PIN, null),
            recentPinIds = recent,
            nextScheduledAt = p.getLong(KEY_NEXT_SCHEDULED, 0L),
            promptDismissed = p.getBoolean(KEY_PROMPT_DISMISSED, false),
        )
    }

    /** Writes only what changed, so a foreground timestamp does not rewrite the whole file's worth of keys. */
    private fun writeToPrefs(p: SharedPreferences, old: ReminderSnapshot, s: ReminderSnapshot) {
        val e = p.edit()
        if (old.enabled != s.enabled) e.putBoolean(KEY_ENABLED, s.enabled)
        if (old.lastSentAt != s.lastSentAt) e.putLong(KEY_LAST_SENT, s.lastSentAt)
        if (old.lastAppActiveAt != s.lastAppActiveAt) e.putLong(KEY_LAST_ACTIVE, s.lastAppActiveAt)
        if (old.sentTimestamps != s.sentTimestamps) e.putString(KEY_SENT_HISTORY, s.sentTimestamps.joinToString(","))
        if (old.consecutiveIgnores != s.consecutiveIgnores) e.putInt(KEY_IGNORES, s.consecutiveIgnores)
        if (old.pendingPinId != s.pendingPinId) e.putString(KEY_PENDING_PIN, s.pendingPinId)
        if (old.recentPinIds != s.recentPinIds) e.putString(KEY_RECENT_PINS, s.recentPinIds.joinToString(","))
        if (old.nextScheduledAt != s.nextScheduledAt) e.putLong(KEY_NEXT_SCHEDULED, s.nextScheduledAt)
        if (old.promptDismissed != s.promptDismissed) e.putBoolean(KEY_PROMPT_DISMISSED, s.promptDismissed)
        e.apply()
    }

    /** The pin's thumbnail, from disk or (bounded by a timeout) R2; null when unavailable, in which case the text goes alone. */
    private suspend fun loadNotificationBitmap(store: Store, pin: Pin): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val local = store.thumbFile(pin).takeIf { it.exists() }
            when {
                local != null -> decodeBounded(local.path, null)
                R2.enabled && pin.synced -> fetchThumb(pin)?.let { decodeBounded(null, it) }
                else -> null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) { // includes OutOfMemoryError: a missing picture must not cost the reminder
            Log.w(TAG, "thumbnail unavailable for ${pin.id}", e)
            null
        }
    }

    /** Blocking GET with a hard call timeout (the shared client allows minutes) and a size cap. */
    private fun fetchThumb(pin: Pin): ByteArray? {
        val client = R2.http.newBuilder().callTimeout(THUMB_FETCH_TIMEOUT_S, TimeUnit.SECONDS).build()
        client.newCall(Request.Builder().url(R2.url(pin.thumbKey)).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            val source = response.body.source()
            source.request(MAX_THUMB_BYTES + 1)
            return if (source.buffer.size > MAX_THUMB_BYTES) null else source.readByteArray()
        }
    }

    private fun decodeBounded(path: String?, bytes: ByteArray?): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (path != null) BitmapFactory.decodeFile(path, bounds) else BitmapFactory.decodeByteArray(bytes!!, 0, bytes.size, bounds)
        val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
        if (maxSide <= 0) return null
        var sample = 1
        while (maxSide / (sample * 2) >= MAX_BITMAP_PX) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = if (path != null) BitmapFactory.decodeFile(path, opts) else BitmapFactory.decodeByteArray(bytes!!, 0, bytes.size, opts)
        decoded ?: return null
        val curMax = maxOf(decoded.width, decoded.height)
        if (curMax <= MAX_BITMAP_PX) return decoded
        val scale = MAX_BITMAP_PX.toFloat() / curMax
        val w = (decoded.width * scale).toInt().coerceAtLeast(1)
        val h = (decoded.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(decoded, w, h, true).also {
            if (it !== decoded) decoded.recycle()
        }
    }

    /**
     * Center-cropped rounded square matching Portland's card radius for the collapsed large icon.
     * Draws [src] through a shader and never recycles it: it is also the big picture, and `Bitmap.createBitmap`
     * hands back the very same instance for a square source, so recycling "the intermediate" would kill the picture.
     */
    private fun roundThumb(src: Bitmap, context: Context): Bitmap {
        val density = context.resources.displayMetrics.density
        val sizePx = (64f * density).toInt().coerceIn(96, 256)
        val radiusPx = 12f * density
        val scale = sizePx.toFloat() / minOf(src.width, src.height)
        val shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(
                Matrix().apply {
                    postScale(scale, scale)
                    postTranslate((sizePx - src.width * scale) / 2f, (sizePx - src.height * scale) / 2f)
                },
            )
        }
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader }
        Canvas(out).drawRoundRect(RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat()), radiusPx, radiusPx, paint)
        return out
    }
}

class ReminderWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    /**
     * Never leaves the chain dead: whatever goes wrong while deciding or posting, the next run is still armed.
     * Cancellation (the system stopping us, or the app replacing this run) is rethrown untouched: WorkManager
     * reschedules a stopped run itself, and a replaced run already has its successor.
     */
    override suspend fun doWork(): Result {
        val app = applicationContext as App
        val now = System.currentTimeMillis()
        var nextAt: Long? = null
        try {
            // Foregrounded for longer than the suppression window since onStart? Still counts as active.
            if (Updater.foreground) Reminders.markActive(app, now)
            val snap = Reminders.repairClock(app, now)
            if (!snap.enabled || !Reminders.hasNotificationPermission(app)) {
                // Chain ends here; turning reminders on or granting the permission re-arms it.
                Reminders.disarm(app)
                return Result.success()
            }
            nextAt = Reminders.runDue(app, snap, now)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("Reminders", "reminder run failed; arming the next one anyway", e)
        }
        return try {
            Reminders.armNext(app, nextAt, now)
            Result.success()
        } catch (e: Exception) {
            Log.e("Reminders", "could not arm the next reminder; retrying this run", e)
            Result.retry()
        }
    }
}

class ReminderDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Reminders.recordDismissed(context, intent.getStringExtra(Reminders.EXTRA_PIN_ID)) { pending.finish() }
    }
}
