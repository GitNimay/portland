package app.portland.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SeekTest {
    @Test
    fun `seeking moves by the delta`() {
        assertEquals(15_000L, seekTarget(5_000, 10_000, 60_000))
        assertEquals(5_000L, seekTarget(15_000, -10_000, 60_000))
    }

    @Test
    fun `seeking clamps at both ends`() {
        assertEquals(0L, seekTarget(3_000, -10_000, 60_000))
        assertEquals(60_000L, seekTarget(55_000, 10_000, 60_000))
        assertEquals(60_000L, seekTarget(90_000, 10_000, 60_000)) // stale position past the end
        assertEquals(0L, seekTarget(-5_000, 0, 60_000))
    }

    @Test
    fun `unknown duration always lands on zero`() {
        assertEquals(0L, seekTarget(5_000, 10_000, 0))
        assertEquals(0L, seekTarget(5_000, -10_000, -1))
        assertEquals(0L, fractionToPosition(0.5f, 0))
        assertEquals(0f, positionToFraction(5_000, 0), 0f)
    }

    @Test
    fun `fractions map onto the clip and clamp`() {
        assertEquals(0L, fractionToPosition(0f, 40_000))
        assertEquals(20_000L, fractionToPosition(0.5f, 40_000))
        assertEquals(40_000L, fractionToPosition(1f, 40_000))
        assertEquals(0L, fractionToPosition(-0.3f, 40_000))
        assertEquals(40_000L, fractionToPosition(1.7f, 40_000))
        assertEquals(0L, fractionToPosition(Float.NaN, 40_000))
    }

    @Test
    fun `positions map back to fractions and clamp`() {
        assertEquals(0.25f, positionToFraction(10_000, 40_000), 1e-6f)
        assertEquals(1f, positionToFraction(50_000, 40_000), 0f)
        assertEquals(0f, positionToFraction(-1, 40_000), 0f)
    }
}
