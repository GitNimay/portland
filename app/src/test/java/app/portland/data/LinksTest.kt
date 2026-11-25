package app.portland.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinksTest {
    @Test
    fun `finds the link inside shared text`() {
        assertEquals("https://example.com/a?b=1", Links.find("Look at this! https://example.com/a?b=1."))
        assertEquals("http://x.io/p", Links.find("(http://x.io/p)"))
        assertNull(Links.find("no link here"))
        assertNull(Links.find("ftp://example.com/file"))
    }

    @Test
    fun `prefers advertised video, then sources, then images, resolving and unescaping`() {
        val html = """
            <html><head>
            <meta property="og:image" content="/img/cover.jpg?w=1&amp;h=2">
            <meta content='https://cdn.example.com/v.mp4' property='og:video:secure_url' />
            <meta name="twitter:image" content="https://cdn.example.com/t.png">
            <meta property="og:image" content="https://other.example.com/second.jpg">
            </head><body><video src="clip.webm"></video></body></html>
        """.trimIndent()
        assertEquals(
            listOf(
                "https://cdn.example.com/v.mp4",
                "https://example.com/post/clip.webm",
                "https://example.com/img/cover.jpg?w=1&h=2",
                "https://cdn.example.com/t.png",
            ),
            mediaCandidates(html, "https://example.com/post/1"),
        )
    }

    @Test
    fun `a page without media gives nothing`() {
        assertEquals(emptyList<String>(), mediaCandidates("<html><meta name=\"description\" content=\"hi\"></html>", "https://e.com"))
    }
}
