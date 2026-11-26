package app.portland.ui

import app.portland.data.GridColumns
import app.portland.data.UserPrefs
import org.junit.Assert.assertEquals
import org.junit.Test

class GridPinchTest {
    @Test
    fun `the grid starts at two columns`() {
        assertEquals(2, UserPrefs().gridColumns)
        assertEquals(GridColumns.Default, UserPrefs().gridColumns)
    }

    @Test
    fun `pinching in shows more columns, spreading shows fewer`() {
        assertEquals(3, columnsAfterPinch(2, 1f / ColumnStep))
        assertEquals(4, columnsAfterPinch(3, 0.5f))
        assertEquals(3, columnsAfterPinch(4, ColumnStep))
        assertEquals(2, columnsAfterPinch(3, 2f))
    }

    @Test
    fun `a small pinch changes nothing`() {
        assertEquals(2, columnsAfterPinch(2, 1f))
        assertEquals(3, columnsAfterPinch(3, 1.2f))
        assertEquals(3, columnsAfterPinch(3, 0.85f))
    }

    @Test
    fun `the count stays between the fewest and the most columns`() {
        assertEquals(GridColumns.Min, columnsAfterPinch(GridColumns.Min, 3f))
        assertEquals(GridColumns.Max, columnsAfterPinch(GridColumns.Max, 0.2f))
        assertEquals(GridColumns.Min, GridColumns.clamp(0))
        assertEquals(GridColumns.Max, GridColumns.clamp(9)) // a stored value from a later version, say
    }

    @Test
    fun `a broken pinch frame changes nothing`() {
        assertEquals(3, columnsAfterPinch(3, Float.NaN))
        assertEquals(3, columnsAfterPinch(3, Float.POSITIVE_INFINITY))
        assertEquals(3, columnsAfterPinch(3, 0f))
        assertEquals(3, columnsAfterPinch(3, -1f))
    }
}
