package app.portland.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionsTest {
    @Test
    fun `reads result response from a workers ai reply`() {
        // Trimmed from a real mistral-small-3.1 reply.
        val json = """{"result":{"choices":[],"response":"Pouring coffee through a Chemex filter close-up in a dimly lit café","tool_calls":[]},"success":true,"errors":[],"messages":[]}"""
        assertEquals("Pouring coffee through a Chemex filter close-up in a dimly lit café", parseCaption(json))
    }

    @Test
    fun `empty or missing response becomes an empty caption`() {
        assertEquals("", parseCaption("""{"result":{},"success":true}"""))
        assertEquals("", parseCaption("""{"success":false,"errors":[{"message":"x"}]}"""))
    }

    @Test
    fun `cleans quotes, whitespace and trailing period`() {
        assertEquals("Pug wrapped in plaid blanket", cleanCaption("  \"Pug wrapped   in plaid\nblanket.\" "))
        assertEquals("Lion face, dramatic light", cleanCaption("“Lion face, dramatic light”"))
    }

    @Test
    fun `caps runaway output`() {
        assertTrue(cleanCaption("word ".repeat(100)).length <= 140)
    }

    @Test
    fun `caption survives json both as not-yet-described and described`() {
        val pending = Pin("a", false, "jpg", 3, 4, createdAt = 1)
        val done = pending.copy(id = "b", caption = "Fjord from a cliff")
        val failed = pending.copy(id = "c", caption = "")
        val back = Library.fromJson(Library(listOf(pending, done, failed)).toJson()).pins
        assertNull(back[0].caption)
        assertEquals("Fjord from a cliff", back[1].caption)
        assertEquals("", back[2].caption)
    }
}
