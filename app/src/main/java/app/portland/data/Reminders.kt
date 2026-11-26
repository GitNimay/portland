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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** Persisted reminder budget, anti-repeat history, and user preference. */
data class ReminderSnapshot(
    val enabled: Boolean = true,
    val lastSentAt: Long = 0L,
    val lastOpenedAt: Long = 0L,
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
    const val BASE_COOLDOWN_MS = 4 * HOUR_MS
    const val MAX_COOLDOWN_MS = 24 * HOUR_MS
    const val MIN_DELAY_MINUTES = 240L // 4 hours
    const val MAX_DELAY_MINUTES = 480L // 8 hours

    private val WS_REGEX = Regex("\\s+")

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
 */
object Reminders {
    const val CHANNEL_ID = "pin_reminders"
    const val NOTIFICATION_ID = 1001
    const val ACTION_OPEN_PIN = "app.portland.ACTION_OPEN_PIN"
    const val EXTRA_PIN_ID = "pin_id"

    private const val WORK_NAME = "portland_pin_reminder"
    private const val PREFS_NAME = "portland_reminders"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LAST_SENT = "last_sent"
    private const val KEY_LAST_OPENED = "last_opened"
    private const val KEY_LAST_ACTIVE = "last_active"
    private const val KEY_SENT_HISTORY = "sent_history"
    private const val KEY_IGNORES = "ignores"
    private const val KEY_PENDING_PIN = "pending_pin"
    private const val KEY_RECENT_PINS = "recent_pins"
    private const val KEY_NEXT_SCHEDULED = "next_scheduled"
    private const val KEY_PROMPT_DISMISSED = "prompt_dismissed"
    private const val MAX_BITMAP_PX = 640

    private val lock = Any()
    private val _state = MutableStateFlow<ReminderSnapshot?>(null)

    fun state(context: Context): StateFlow<ReminderSnapshot> {
        ensureLoaded(context)
        @Suppress("UNCHECKED_CAST")
        return _state as StateFlow<ReminderSnapshot>
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

    /** Ensures the randomized WorkManager chain is armed when enabled, or cancels it when disabled. */
    fun ensureScheduled(context: Context, forceReplace: Boolean = false) {
        val app = context.applicationContext
        val snap = ensureLoaded(app)
        if (!snap.enabled || !hasNotificationPermission(app)) {
            WorkManager.getInstance(app).cancelUniqueWork(WORK_NAME)
            if (snap.nextScheduledAt != 0L) updateState(app) { it.copy(nextScheduledAt = 0L) }
            return
        }
        val now = System.currentTimeMillis()
        if (!forceReplace && snap.nextScheduledAt > now && snap.nextScheduledAt - now <= ReminderPolicy.MAX_COOLDOWN_MS) {
            // Keep the existing WorkManager request if one is already pending within the current cadence window.
            enqueue(app, (snap.nextScheduledAt - now).coerceAtLeast(ReminderPolicy.MIN_DEFER_MS), ExistingWorkPolicy.KEEP)
            return
        }
        val delayMs = ReminderPolicy.nextRandomDelayMs(now, snap)
        scheduleAt(app, now + delayMs, forceReplace = true)
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

    fun recordAppActive(context: Context, nowMs: Long = System.currentTimeMillis()) {
        updateState(context.applicationContext) { it.copy(lastAppActiveAt = nowMs) }
    }

    fun recordOpened(context: Context, pinId: String?, nowMs: Long = System.currentTimeMillis()) {
        val app = context.applicationContext
        updateState(app) { s ->
            s.copy(
                lastOpenedAt = nowMs,
                consecutiveIgnores = 0,
                pendingPinId = if (pinId == null || s.pendingPinId == pinId) null else s.pendingPinId,
            )
        }
        NotificationManagerCompat.from(app).cancel(NOTIFICATION_ID)
    }

    fun recordDismissed(context: Context, pinId: String?) {
        val app = context.applicationContext
        updateState(app) { s ->
            val bumped = if (pinId == null || s.pendingPinId == pinId) s.consecutiveIgnores + 1 else s.consecutiveIgnores
            s.copy(
                consecutiveIgnores = bumped,
                pendingPinId = if (pinId == null || s.pendingPinId == pinId) null else s.pendingPinId,
            )
        }
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
        val boardName = lib.boards.firstOrNull { it.id == pin.boardId }?.name
        val copy = ReminderPolicy.buildCopy(pin, boardName, nowMs)
        val bitmap = withContext(Dispatchers.IO) { loadNotificationBitmap(store, pin) }
        val notification = buildNotification(context, pin, copy, bitmap)
        return try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            true
        } catch (e: SecurityException) {
            Log.w("Reminders", "POST_NOTIFICATIONS denied at notify time", e)
            false
        }
    }

    internal fun buildNotification(
        context: Context,
        pin: Pin,
        copy: ReminderCopy,
        bitmap: Bitmap?,
    ): Notification {
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

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(copy.title)
            .setContentText(copy.text)
            .setContentIntent(openPending)
            .setDeleteIntent(dismissPending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        if (bitmap != null) {
            val rounded = roundThumb(bitmap, context)
            builder.setLargeIcon(rounded)
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

    internal fun scheduleAt(context: Context, targetEpochMs: Long, forceReplace: Boolean = true) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val delayMs = (targetEpochMs - now).coerceAtLeast(ReminderPolicy.MIN_DEFER_MS)
        val policy = if (forceReplace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        enqueue(app, delayMs, policy)
        updateState(app) { it.copy(nextScheduledAt = now + delayMs) }
    }

    private fun enqueue(context: Context, delayMs: Long, policy: ExistingWorkPolicy) {
        val work = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, work)
    }

    internal fun recordSent(
        context: Context,
        pinId: String,
        nowMs: Long,
        ignores: Int,
        updateDailyHistory: Boolean = true,
    ) {
        updateState(context.applicationContext) { s ->
            val history = if (updateDailyHistory) {
                (s.sentTimestamps + nowMs).filter { nowMs - it < 2 * ReminderPolicy.DAY_MS }
            } else {
                s.sentTimestamps
            }
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
        _state.value?.let { return it }
        synchronized(lock) {
            _state.value?.let { return it }
            val loaded = readFromPrefs(prefs(context))
            _state.value = loaded
            return loaded
        }
    }

    private fun updateState(context: Context, transform: (ReminderSnapshot) -> ReminderSnapshot): ReminderSnapshot {
        synchronized(lock) {
            val current = ensureLoaded(context)
            val next = transform(current)
            if (next != current) {
                writeToPrefs(prefs(context), next)
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
            lastOpenedAt = p.getLong(KEY_LAST_OPENED, 0L),
            lastAppActiveAt = p.getLong(KEY_LAST_ACTIVE, 0L),
            sentTimestamps = sent,
            consecutiveIgnores = p.getInt(KEY_IGNORES, 0),
            pendingPinId = p.getString(KEY_PENDING_PIN, null),
            recentPinIds = recent,
            nextScheduledAt = p.getLong(KEY_NEXT_SCHEDULED, 0L),
            promptDismissed = p.getBoolean(KEY_PROMPT_DISMISSED, false),
        )
    }

    private fun writeToPrefs(p: SharedPreferences, s: ReminderSnapshot) {
        p.edit()
            .putBoolean(KEY_ENABLED, s.enabled)
            .putLong(KEY_LAST_SENT, s.lastSentAt)
            .putLong(KEY_LAST_OPENED, s.lastOpenedAt)
            .putLong(KEY_LAST_ACTIVE, s.lastAppActiveAt)
            .putString(KEY_SENT_HISTORY, s.sentTimestamps.joinToString(","))
            .putInt(KEY_IGNORES, s.consecutiveIgnores)
            .putString(KEY_PENDING_PIN, s.pendingPinId)
            .putString(KEY_RECENT_PINS, s.recentPinIds.joinToString(","))
            .putLong(KEY_NEXT_SCHEDULED, s.nextScheduledAt)
            .putBoolean(KEY_PROMPT_DISMISSED, s.promptDismissed)
            .apply()
    }

    private suspend fun loadNotificationBitmap(store: Store, pin: Pin): Bitmap? = runCatching {
        val local = store.thumbFile(pin).takeIf { it.exists() }
        val raw = if (local != null) {
            decodeBounded(local.path, null)
        } else if (R2.enabled && pin.synced) {
            val bytes = R2.get(pin.thumbKey) ?: return@runCatching null
            decodeBounded(null, bytes)
        } else {
            return@runCatching null
        }
        raw ?: return@runCatching null
    }.onFailure { Log.w("Reminders", "thumbnail decode failed for ${pin.id}", it) }.getOrNull()

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

    /** Center-cropped rounded square matching Portland's card radius for collapsed API 30 fallback. */
    private fun roundThumb(src: Bitmap, context: Context): Bitmap {
        val density = context.resources.displayMetrics.density
        val sizePx = (64f * density).toInt().coerceIn(96, 256)
        val radiusPx = 12f * density
        val minSide = minOf(src.width, src.height)
        val x = (src.width - minSide) / 2
        val y = (src.height - minSide) / 2
        val square = Bitmap.createBitmap(src, x, y, minSide, minSide)
        val scaled = if (square.width != sizePx) {
            Bitmap.createScaledBitmap(square, sizePx, sizePx, true).also { if (it !== square) square.recycle() }
        } else {
            square
        }
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        canvas.drawRoundRect(RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat()), radiusPx, radiusPx, paint)
        scaled.recycle()
        return out
    }
}

class ReminderWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as App
        val snap = Reminders.snapshot(app)
        if (!snap.enabled || !Reminders.hasNotificationPermission(app)) {
            return Result.success()
        }
        val now = System.currentTimeMillis()
        val lib = app.store.library.value
        when (val decision = ReminderPolicy.evaluateEligibility(snap, lib, now)) {
            is Eligibility.Disabled -> return Result.success()
            is Eligibility.NotEnoughContent -> {
                val nextAt = now + ReminderPolicy.nextRandomDelayMs(now, snap)
                Reminders.scheduleAt(app, nextAt)
            }
            is Eligibility.Defer -> {
                Reminders.updateIgnores(app, decision.effectiveIgnores)
                Reminders.scheduleAt(app, decision.deferUntilMs)
            }
            is Eligibility.Send -> {
                val pin = ReminderPolicy.pickPin(lib.pins, snap.recentPinIds, now)
                if (pin != null && Reminders.postForPin(app, app.store, lib, pin, now)) {
                    Reminders.recordSent(app, pin.id, now, decision.effectiveIgnores)
                } else {
                    Reminders.updateIgnores(app, decision.effectiveIgnores)
                }
                val updated = Reminders.snapshot(app)
                val nextAt = now + ReminderPolicy.nextRandomDelayMs(now, updated)
                Reminders.scheduleAt(app, nextAt)
            }
        }
        return Result.success()
    }
}

class ReminderDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Reminders.recordDismissed(context, intent.getStringExtra(Reminders.EXTRA_PIN_ID))
    }
}
