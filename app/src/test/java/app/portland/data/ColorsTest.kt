package app.portland.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorsTest {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun fill(n: Int, c: Int) = IntArray(n) { c }

    @Test
    fun `pixels land in the expected buckets`() {
        val cases = mapOf(
            rgb(220, 30, 30) to "red", rgb(250, 140, 20) to "orange", rgb(250, 220, 40) to "yellow",
            rgb(40, 170, 60) to "green", rgb(20, 180, 180) to "teal", rgb(30, 90, 220) to "blue",
            rgb(20, 30, 90) to "blue", rgb(130, 50, 200) to "purple", rgb(240, 80, 170) to "pink",
            rgb(250, 180, 190) to "pink", rgb(120, 70, 35) to "brown", rgb(10, 10, 12) to "black",
            rgb(128, 128, 130) to "gray", rgb(250, 250, 248) to "white",
        )
        for ((c, name) in cases) assertEquals("rgb ${Integer.toHexString(c)}", name, Colors.name((c shr 16) and 255, (c shr 8) and 255, c and 255))
    }

    @Test
    fun `a blue accent counts but a few stray pixels do not`() {
        val photo = fill(80, rgb(250, 250, 248)) + fill(12, rgb(30, 90, 220)) + fill(2, rgb(220, 30, 30))
        val names = Colors.of(photo)
        assertEquals(listOf("white", "blue"), names) // dominant first; 2% red is noise
    }

    @Test
    fun `neutrals need to dominate to be tagged`() {
        assertEquals(listOf("red"), Colors.of(fill(90, rgb(220, 30, 30)) + fill(10, rgb(128, 128, 130))))
    }

    @Test
    fun `transparent and empty images give no colours`() {
        assertEquals(emptyList<String>(), Colors.of(IntArray(10)))
        assertEquals(emptyList<String>(), Colors.of(IntArray(0)))
    }

    @Test
    fun `queries accept aliases and reject mixed text`() {
        assertEquals(setOf("blue"), Colors.query(" Blue "))
        assertEquals(setOf("gray", "red"), Colors.query("grey crimson"))
        assertNull(Colors.query("blue car"))
        assertNull(Colors.query(""))
    }

    @Test
    fun `colour search finds tagged pins, dominant first, alongside text hits`() {
        fun pin(id: String, colors: List<String>?, note: String = "") = Pin(id, false, "jpg", 1, 1, note = note, createdAt = 1, colors = colors)
        val lib = Library(
            pins = listOf(
                pin("a", listOf("white", "blue")),
                pin("b", listOf("red")),
                pin("c", listOf("blue", "gray")),
                pin("d", null, note = "my blue sofa"),
            ),
        )
        assertEquals(listOf("c", "a", "d"), lib.search("blue").map { it.id })
        assertEquals(listOf("b"), lib.search("red").map { it.id })
        assertEquals(listOf("d"), lib.search("blue sofa").map { it.id })
        assertTrue(lib.search("purple").isEmpty())
        assertEquals(4, lib.search("  ").size)
    }
}
