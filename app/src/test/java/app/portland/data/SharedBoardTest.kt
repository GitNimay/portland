package app.portland.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SharedBoardTest {
    private fun pin(id: String, boardId: String? = null) =
        Pin(id = id, video = false, ext = "jpg", w = 10, h = 20, boardId = boardId, createdAt = 1)

    private val lib = Library(pins = listOf(pin("a", "x"), pin("b", "x"), pin("c"), pin("d")))

    @Test
    fun `same board is shared`() = assertEquals(true to "x", lib.sharedBoard(setOf("a", "b")))

    @Test
    fun `all without a board share null`() = assertEquals(true to null, lib.sharedBoard(setOf("c", "d")))

    @Test
    fun `mixed boards are unknown`() {
        assertEquals(false to null, lib.sharedBoard(setOf("a", "c")))
    }

    @Test
    fun `empty selection is unknown`() = assertEquals(false to null, lib.sharedBoard(emptySet()))
}
