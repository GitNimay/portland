package app.portland.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.random.Random

class RemindersTest {
    private val zone = ZoneOffset.UTC

    private fun epoch(year: Int = 2026, month: Int = 11, day: Int = 26, hour: Int = 14, minute: Int = 0): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun pin(
        id: String,
        createdAt: Long = epoch(day = 1),
        note: String = "",
        caption: String? = null,
        boardId: String? = null,
    ) = Pin(
        id = id,
        video = false,
        ext = "jpg",
        w = 800,
        h = 1200,
        note = note,
        boardId = boardId,
        createdAt = createdAt,
        caption = caption,
    )

    private fun libraryOf(vararg pins: Pin) = Library(pins = pins.toList())

    @Test
    fun `disabled or tiny library never sends`() {
        val now = epoch(hour = 14)
        val lib = libraryOf(pin("a"), pin("b"))
        assertEquals(Eligibility.Disabled, ReminderPolicy.evaluateEligibility(ReminderSnapshot(enabled = false), lib, now, zone))
        assertEquals(Eligibility.NotEnoughContent, ReminderPolicy.evaluateEligibility(ReminderSnapshot(enabled = true), libraryOf(pin("a")), now, zone))
        assertEquals(Eligibility.NotEnoughContent, ReminderPolicy.evaluateEligibility(ReminderSnapshot(enabled = true), Library(), now, zone))
    }

    @Test
    fun `active user suppression defers when user opened app within 1 hour`() {
        val now = epoch(hour = 14)
        val lib = libraryOf(pin("a"), pin("b"))
        val state = ReminderSnapshot(enabled = true, lastAppActiveAt = now - 20 * ReminderPolicy.MINUTE_MS)

        val res = ReminderPolicy.evaluateEligibility(state, lib, now, zone, Random(0)) as Eligibility.Defer
        assertEquals(DeferReason.ActiveRecently, res.reason)
        assertTrue(res.deferUntilMs >= now + 40 * ReminderPolicy.MINUTE_MS)
        assertFalse(ReminderPolicy.isQuietHour(res.deferUntilMs, zone))
    }

    @Test
    fun `quiet hours defer to next morning daytime window with jitter`() {
        val lib = libraryOf(pin("a"), pin("b"))
        val lateNight = epoch(day = 26, hour = 23, minute = 30)
        val earlyMorning = epoch(day = 27, hour = 5, minute = 15)

        val fromNight = ReminderPolicy.evaluateEligibility(ReminderSnapshot(), lib, lateNight, zone, Random(42)) as Eligibility.Defer
        assertEquals(DeferReason.QuietHours, fromNight.reason)
        val nightTarget = Instant.ofEpochMilli(fromNight.deferUntilMs).atZone(zone)
        assertEquals(27, nightTarget.dayOfMonth)
        assertTrue(nightTarget.hour in 9..10)

        val fromMorning = ReminderPolicy.evaluateEligibility(ReminderSnapshot(), lib, earlyMorning, zone, Random(42)) as Eligibility.Defer
        assertEquals(DeferReason.QuietHours, fromMorning.reason)
        val morningTarget = Instant.ofEpochMilli(fromMorning.deferUntilMs).atZone(zone)
        assertEquals(27, morningTarget.dayOfMonth)
        assertTrue(morningTarget.hour in 9..10)
    }

    @Test
    fun `daily cap allows two reminders per day and defers a third to tomorrow morning`() {
        val morning = epoch(day = 26, hour = 9, minute = 30)
        val afternoon = epoch(day = 26, hour = 15, minute = 0)
        val evening = epoch(day = 26, hour = 19, minute = 30)
        val lib = libraryOf(pin("a"), pin("b"))

        // Second reminder same day after >= 4h cooldown is allowed.
        val afterFirst = ReminderSnapshot(
            enabled = true,
            lastSentAt = morning,
            sentTimestamps = listOf(morning),
        )
        assertTrue(ReminderPolicy.evaluateEligibility(afterFirst, lib, afternoon, zone, Random(1)) is Eligibility.Send)

        // Third reminder on the same day is deferred to tomorrow morning.
        val afterSecond = ReminderSnapshot(
            enabled = true,
            lastSentAt = afternoon,
            sentTimestamps = listOf(morning, afternoon),
        )
        val res = ReminderPolicy.evaluateEligibility(afterSecond, lib, evening, zone, Random(1)) as Eligibility.Defer
        assertEquals(DeferReason.DailyCap, res.reason)
        val target = Instant.ofEpochMilli(res.deferUntilMs).atZone(zone)
        assertEquals(27, target.dayOfMonth)
        assertTrue(target.hour in 9..10)
    }

    @Test
    fun `unopened pending reminder counts as ignore after base cooldown and stretches backoff`() {
        val lib = libraryOf(pin("a"), pin("b"))
        val sentAt = epoch(day = 26, hour = 10, minute = 0)
        val state = ReminderSnapshot(
            enabled = true,
            lastSentAt = sentAt,
            sentTimestamps = listOf(sentAt),
            consecutiveIgnores = 0,
            pendingPinId = "a",
        )

        // 5h later (15:00): base 4h elapsed, so effectiveIgnores bumps 0 -> 1 (6h cooldown), deferring until >= 16:00.
        val fiveHoursLater = sentAt + 5 * ReminderPolicy.HOUR_MS
        val defer = ReminderPolicy.evaluateEligibility(state, lib, fiveHoursLater, zone, Random(7)) as Eligibility.Defer
        assertEquals(DeferReason.Cooldown, defer.reason)
        assertEquals(1, defer.effectiveIgnores)
        assertTrue(defer.deferUntilMs >= sentAt + 6 * ReminderPolicy.HOUR_MS)

        // 7h later (17:00): both 6h cooldown and daytime window are clear, preserving effectiveIgnores = 1.
        val sevenHoursLater = sentAt + 7 * ReminderPolicy.HOUR_MS
        val send = ReminderPolicy.evaluateEligibility(state, lib, sevenHoursLater, zone, Random(7))
        assertTrue(send is Eligibility.Send)
        assertEquals(1, (send as Eligibility.Send).effectiveIgnores)
    }

    @Test
    fun `nextRandomDelayMs schedules twice daily in daytime and advances to next morning at daily cap`() {
        val morning = epoch(day = 26, hour = 9, minute = 30)
        val firstState = ReminderSnapshot(sentTimestamps = listOf(morning), consecutiveIgnores = 0)
        repeat(25) { seed ->
            val delay = ReminderPolicy.nextRandomDelayMs(morning, firstState, zone, Random(seed))
            assertTrue("delay >= 4h", delay >= 4 * ReminderPolicy.HOUR_MS)
            assertFalse("never in quiet hours", ReminderPolicy.isQuietHour(morning + delay, zone))
        }

        // After two sends on day 26, next delay must land on day 27 morning (or later, never day 26).
        val afternoon = epoch(day = 26, hour = 15, minute = 0)
        val cappedState = ReminderSnapshot(sentTimestamps = listOf(morning, afternoon), consecutiveIgnores = 0)
        val nextDelay = ReminderPolicy.nextRandomDelayMs(afternoon, cappedState, zone, Random(3))
        val target = Instant.ofEpochMilli(afternoon + nextDelay).atZone(zone)
        assertTrue(target.dayOfMonth >= 27)
        assertFalse(ReminderPolicy.isQuietHour(afternoon + nextDelay, zone))
    }

    @Test
    fun `foreground timestamp is persisted at most every few minutes and always when the clock goes back`() {
        val last = epoch(hour = 14)
        assertFalse(ReminderPolicy.shouldRecordActive(last, last))
        assertFalse(ReminderPolicy.shouldRecordActive(last, last + ReminderPolicy.ACTIVE_RECORD_INTERVAL_MS - 1))
        assertTrue(ReminderPolicy.shouldRecordActive(last, last + ReminderPolicy.ACTIVE_RECORD_INTERVAL_MS))
        assertTrue(ReminderPolicy.shouldRecordActive(0L, last))
        assertTrue(ReminderPolicy.shouldRecordActive(last, last - 1))
        // The throttle must stay well inside the suppression window or suppression would visibly lag.
        assertTrue(ReminderPolicy.ACTIVE_RECORD_INTERVAL_MS * 4 <= ReminderPolicy.ACTIVE_SUPPRESSION_MS)
    }

    @Test
    fun `timestamps from the future after a clock change are pulled back to now`() {
        val now = epoch(day = 26, hour = 14)
        val lib = libraryOf(pin("a"), pin("b"))
        val weekAhead = now + 7 * ReminderPolicy.DAY_MS

        // Clock was a week ahead when the last reminder was sent and the app last opened, then got corrected.
        val state = ReminderSnapshot(
            lastSentAt = weekAhead,
            lastAppActiveAt = weekAhead,
            sentTimestamps = listOf(weekAhead),
        )
        val clean = ReminderPolicy.sanitize(state, now)
        assertEquals(now, clean.lastSentAt)
        assertEquals(now, clean.lastAppActiveAt)
        assertEquals(listOf(now), clean.sentTimestamps)
        assertTrue(ReminderPolicy.sanitize(clean, now) === clean)

        // Persisted once, the cooldown then simply runs out: no week-long silence, no renewal on each evaluation.
        val res = ReminderPolicy.evaluateEligibility(clean, lib, now, zone, Random(0)) as Eligibility.Defer
        assertEquals(DeferReason.Cooldown, res.reason)
        assertTrue(res.deferUntilMs <= now + 4 * ReminderPolicy.HOUR_MS + ReminderPolicy.MINUTE_MS)
        val later = now + 5 * ReminderPolicy.HOUR_MS
        assertTrue(ReminderPolicy.evaluateEligibility(clean, lib, later, zone, Random(0)) is Eligibility.Send)
    }

    @Test
    fun `reminder windows follow local time across a DST change`() {
        val ny = java.time.ZoneId.of("America/New_York")
        // 2026-03-08 02:00 -> 03:00 in New York; 23:00 the evening before defers to the morning after the jump.
        val lateNight = ZonedDateTime.of(2026, 3, 7, 23, 0, 0, 0, ny).toInstant().toEpochMilli()
        val lib = libraryOf(pin("a"), pin("b"))
        val res = ReminderPolicy.evaluateEligibility(ReminderSnapshot(), lib, lateNight, ny, Random(3)) as Eligibility.Defer
        val target = Instant.ofEpochMilli(res.deferUntilMs).atZone(ny)
        assertEquals(8, target.dayOfMonth)
        assertTrue(target.hour in 9..10)
        assertFalse(ReminderPolicy.isQuietHour(res.deferUntilMs, ny))
    }

    @Test
    fun `pickPin skips recent history and falls back gracefully without immediate repeat`() {
        val now = epoch(day = 26, hour = 14)
        val oldRich = pin("old", createdAt = now - 20 * ReminderPolicy.DAY_MS, note = "Staircase", boardId = "b1")
        val fresh = pin("fresh", createdAt = now - ReminderPolicy.HOUR_MS)
        val second = pin("second", createdAt = now - 10 * ReminderPolicy.DAY_MS)

        val picked = ReminderPolicy.pickPin(listOf(oldRich, fresh, second), listOf("second"), now, Random(5))
        assertNotNull(picked)
        assertNotEquals("second", picked!!.id)

        // When every pin is in recentPinIds, never repeat the most recent pin when alternatives exist.
        val cycled = ReminderPolicy.pickPin(listOf(oldRich, second), listOf("old", "second"), now, Random(1))
        assertEquals("second", cycled?.id)

        assertNull(ReminderPolicy.pickPin(emptyList(), emptyList(), now, Random(1)))
    }

    @Test
    fun `buildCopy crafts minimal single-line copy with note, caption, board, and relative age`() {
        val now = epoch(day = 26, hour = 14)
        val withNote = pin(
            "1",
            createdAt = now - 14 * ReminderPolicy.DAY_MS,
            note = "  \"Brutalist\nconcrete   staircase\"  ",
            caption = "Stairs in grey concrete",
            boardId = "arch",
        )
        val noteCopy = ReminderPolicy.buildCopy(withNote, "Architecture", now)
        assertEquals("Architecture", noteCopy.title)
        assertEquals("Brutalist concrete staircase", noteCopy.text)
        assertFalse(noteCopy.text.contains('\n'))

        val withCaption = pin("2", createdAt = now - 5 * ReminderPolicy.DAY_MS, caption = "Warm linen curtains in morning sun")
        val captionCopy = ReminderPolicy.buildCopy(withCaption, null, now)
        assertEquals("Saved 5 days ago", captionCopy.title)
        assertEquals("Warm linen curtains in morning sun", captionCopy.text)

        val bare = pin("3", createdAt = now - 35 * ReminderPolicy.DAY_MS, boardId = "arch")
        val bareCopy = ReminderPolicy.buildCopy(bare, "Interiors", now)
        assertEquals("Interiors", bareCopy.title)
        assertEquals("Saved last month · take another look", bareCopy.text)

        val longNote = pin("4", createdAt = now - 2 * ReminderPolicy.DAY_MS, note = "A".repeat(120))
        val clamped = ReminderPolicy.buildCopy(longNote, null, now)
        assertTrue(clamped.text.length <= 44)
        assertTrue(clamped.text.endsWith("\u2026"))
    }
}
