package app.portland.data

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class R2ClientTest {
    @get:Rule val tmp = TemporaryFolder()

    private val cf = FakeCloudflare().apply {
        buckets += "b"
        s3Keys["KEY"] = "SECRET"
    }
    private val config = R2Config(FakeCloudflare.ACCOUNT, "b", "KEY", "SECRET")
    private val client = R2Client(config, cf.endpoint(config), OkHttpClient())

    @After fun close() = cf.stop()

    @Test
    fun `objects round-trip through signed requests`() = runBlocking {
        val bytes = "hello".toByteArray()
        client.putBytes("media/a b+c.jpg", bytes, "image/jpeg")
        assertArrayEquals(bytes, client.get("media/a b+c.jpg"))
        val file = tmp.newFile("v.mp4").apply { writeBytes(ByteArray(200_000) { it.toByte() }) }
        client.put("media/v.mp4", file, "video/mp4")
        val back = tmp.root.resolve("back.mp4")
        client.download("media/v.mp4", back)
        assertArrayEquals(file.readBytes(), back.readBytes())
        client.delete("media/v.mp4")
        client.delete("media/v.mp4") // already gone is fine
        assertNull(client.get("media/v.mp4"))
    }

    @Test
    fun `a failed download leaves no partial file behind`() = runBlocking {
        val into = tmp.root.resolve("x.jpg")
        try {
            client.download("media/missing.jpg", into)
            fail("expected a 404")
        } catch (e: R2Exception) {
            assertEquals(404, e.code)
        }
        assertFalse(into.exists())
        assertFalse(tmp.root.resolve("x.jpg.part").exists())
    }

    @Test
    fun `wrong keys surface as a 403`() = runBlocking {
        val bad = R2Client(config.copy(secretAccessKey = "nope"), cf.endpoint(config), OkHttpClient())
        try {
            bad.putBytes("k", byteArrayOf(1), "text/plain")
            fail("expected a 403")
        } catch (e: R2Exception) {
            assertEquals(403, e.code)
        }
    }

    @Test
    fun `only requests to the bucket host are signed`() {
        MockWebServer().use { other ->
            other.start()
            other.enqueue(MockResponse.Builder().code(200).build())
            client.http.newCall(Request.Builder().url(other.url("/x")).build()).execute().close()
            assertNull(other.takeRequest().headers["Authorization"])
        }
        val signed = client.sign(Request.Builder().url(client.url("k")).build())
        assertTrue(signed.header("Authorization")!!.startsWith("AWS4-HMAC-SHA256 Credential=KEY/"))
    }

    @Test
    fun `conditional writes report a lost race instead of throwing`() {
        MockWebServer().use { s3 ->
            s3.start()
            s3.enqueue(MockResponse.Builder().code(412).build())
            s3.enqueue(MockResponse.Builder().code(200).build())
            val c = R2Client(config, s3.url("/b"), OkHttpClient())
            runBlocking {
                assertFalse(c.putBytesIfMatch("index.json", byteArrayOf(1), "application/json", null))
                assertTrue(c.putBytesIfMatch("index.json", byteArrayOf(1), "application/json", "\"e1\""))
            }
            assertEquals("*", s3.takeRequest().headers["If-None-Match"])
            assertEquals("\"e1\"", s3.takeRequest().headers["If-Match"])
        }
    }

    @Test
    fun `the etag for a conditional write comes from the uncompressed object`() {
        // R2 gzips larger objects for clients that accept it and then sends a weak ETag that If-Match never
        // satisfies (seen live with a 16 KB index.json: every index write failed with 412).
        MockWebServer().use { s3 ->
            s3.start()
            s3.enqueue(MockResponse.Builder().code(200).addHeader("ETag", "\"abc\"").body("{}").build())
            val c = R2Client(config, s3.url("/b"), OkHttpClient())
            assertEquals("\"abc\"", runBlocking { c.getWithEtag("index.json") }!!.second)
            assertEquals("identity", s3.takeRequest().headers["Accept-Encoding"])
        }
    }

    @Test
    fun `listing follows continuation tokens`() {
        MockWebServer().use { s3 ->
            s3.start()
            s3.enqueue(MockResponse.Builder().code(200).body(
                "<ListBucketResult><IsTruncated>true</IsTruncated><Contents><Key>media/a.jpg</Key>" +
                    "<LastModified>2026-01-01T00:00:00.000Z</LastModified></Contents><NextContinuationToken>t/2</NextContinuationToken></ListBucketResult>",
            ).build())
            s3.enqueue(MockResponse.Builder().code(200).body(
                "<ListBucketResult><IsTruncated>false</IsTruncated><Contents><Key>media/b.jpg</Key>" +
                    "<LastModified>2026-01-01T00:00:01.000Z</LastModified></Contents></ListBucketResult>",
            ).build())
            val c = R2Client(config, s3.url("/b"), OkHttpClient())
            assertEquals(listOf("media/a.jpg", "media/b.jpg"), runBlocking { c.list("media/") }.map { it.first })
            s3.takeRequest()
            assertEquals("t/2", s3.takeRequest().url.queryParameter("continuation-token"))
        }
    }

    @Test
    fun `object urls encode each key segment`() {
        val c = R2Client(config)
        assertEquals("https://${FakeCloudflare.ACCOUNT}.r2.cloudflarestorage.com/b/media/a%20b%2Bc.jpg", c.url("media/a b+c.jpg"))
    }
}
