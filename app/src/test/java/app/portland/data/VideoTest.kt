package app.portland.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoTest {
    @Test
    fun `pages without a video fall back to the preview image`() {
        assertNull(videoError("ERROR: Unsupported URL: https://example.com/post"))
        assertNull(videoError("ERROR: [generic] Unable to download webpage"))
        assertNull(videoError("ERROR: [twitter] 123: No video could be found in this tweet"))
    }

    @Test
    fun `sites that refuse say why, by name`() {
        assertEquals(
            "YouTube blocked the download for now. Try again later.",
            videoError("ERROR: [youtube] jNQXAC9IVRw: Sign in to confirm you’re not a bot. Use --cookies-from-browser"),
        )
        assertEquals(
            "Vimeo needs you to be signed in for this video",
            videoError("ERROR: [vimeo] 76979871: The web client only works when logged-in. Use --cookies"),
        )
        assertEquals("That Instagram video is private", videoError("ERROR: [instagram:story] x: This content is private"))
        assertEquals("Couldn't download the video from That site", videoError("ERROR: Postprocessing: ffmpeg not found"))
    }
}
