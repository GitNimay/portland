package app.portland.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseTest {
    @Test
    fun `parses the manifest ci publishes`() {
        // Same shape as the "Publish to R2" step in .github/workflows/android.yml writes.
        val json = """{"versionCode":42,"versionName":"1.0.42","apk":"releases/portland-42.apk","sha256":"ABCDEF0123","notes":"Fix grid"}"""
        assertEquals(Release(42, "1.0.42", "releases/portland-42.apk", "abcdef0123"), Release.parse(json))
    }

    @Test(expected = org.json.JSONException::class)
    fun `rejects a manifest without a checksum`() {
        Release.parse("""{"versionCode":42,"versionName":"1.0.42","apk":"releases/portland-42.apk"}""")
    }
}
