package app.portland.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryTest {
    private fun pin(
        id: String,
        updatedAt: Long = 100,
        note: String = "",
        boardId: String? = null,
        synced: Boolean = false,
        createdAt: Long = 100,
    ) = Pin(
        id = id, video = false, ext = "jpg", w = 10, h = 20,
        note = note, boardId = boardId, createdAt = createdAt, updatedAt = updatedAt, synced = synced,
    )

    @Test
    fun `deletions win over newer edits`() {
        val local = Library(pins = listOf(pin("a", updatedAt = 500, note = "new edit")))
        val remote = Library(deleted = mapOf("a" to 100L))
        assertEquals(emptyList<Pin>(), Library.merge(local, remote).pins)
    }

    @Test
    fun `last write wins by updatedAt`() {
        val local = Library(pins = listOf(pin("a", updatedAt = 100, note = "local")))
        val remote = Library(pins = listOf(pin("a", updatedAt = 200, note = "remote", synced = true)))
        val merged = Library.merge(local, remote).pins.single()
        assertEquals("remote", merged.note)
        assertTrue(merged.synced)
    }

    @Test
    fun `remote pins come back synced`() {
        val merged = Library.merge(Library(), Library(pins = listOf(pin("a", synced = false))))
        assertTrue(merged.pins.single().synced)
    }

    @Test
    fun `pins pointing at a deleted board lose their board`() {
        val local = Library(pins = listOf(pin("a", boardId = "b")))
        val merged = Library.merge(local, Library(deleted = mapOf("b" to 100L)))
        assertNull(merged.pins.single().boardId)
    }

    @Test
    fun `remote json excludes unsynced pins and the purge list`() {
        val lib = Library(
            pins = listOf(pin("synced", synced = true), pin("local", synced = false)),
            purge = listOf("media/old.jpg"),
        )
        val json = JSONObject(lib.toJson(forRemote = true))
        val ids = (0 until json.getJSONArray("pins").length())
            .map { json.getJSONArray("pins").getJSONObject(it).getString("id") }
        assertEquals(listOf("synced"), ids)
        assertFalse(json.has("purge"))
    }

    @Test
    fun `local json round-trips`() {
        val lib = Library(
            pins = listOf(pin("a", synced = true), pin("b", note = "hi", boardId = "board")),
            boards = listOf(Board("board", "Inspiration", 10, 20)),
            deleted = mapOf("gone" to 5L),
            purge = listOf("media/x.jpg", "thumbs/x.webp"),
        )
        assertEquals(lib, Library.fromJson(lib.toJson()))
    }
}
