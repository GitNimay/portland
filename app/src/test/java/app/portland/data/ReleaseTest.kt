package app.portland.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseTest {
    private val apk = "https://github.com/GitNimay/portland/releases/download/v1.0.42/app-release.apk"

    @Test
    fun `parses the manifest ci publishes`() {
        // Same shape as the latest.json the "GitHub release" step in .github/workflows/android.yml attaches.
        val json = """{"versionCode":42,"versionName":"1.0.42","apk":"$apk","sha256":"ABCDEF0123","notes":"Fix grid"}"""
        assertEquals(Release(42, "1.0.42", apk, "abcdef0123"), Release.parse(json))
    }

    @Test(expected = org.json.JSONException::class)
    fun `rejects a manifest without a checksum`() {
        Release.parse("""{"versionCode":42,"versionName":"1.0.42","apk":"$apk"}""")
    }

    @Test(expected = org.json.JSONException::class)
    fun `rejects an apk that is not served over https`() {
        Release.parse("""{"versionCode":42,"versionName":"1.0.42","apk":"http://example.com/a.apk","sha256":"ab"}""")
    }
}
