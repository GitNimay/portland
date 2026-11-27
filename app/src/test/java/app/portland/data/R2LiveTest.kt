package app.portland.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Against real Cloudflare, only when a token is given (never in CI):
 *   PORTLAND_CF_TOKEN=<api token> [PORTLAND_CF_ACCOUNT=<account id>] ./gradlew :app:testDebugUnitTest --tests '*R2LiveTest*'
 * Connects exactly as the app does (creating the "portland" bucket if the token may), then writes, lists, reads and
 * deletes objects under portland-live-test/.
 */
class R2LiveTest {
    private val token = System.getenv("PORTLAND_CF_TOKEN")
    private val account = System.getenv("PORTLAND_CF_ACCOUNT")

    @Test
    fun `connects and round-trips objects in a real bucket`() = runBlocking {
        assumeTrue("set PORTLAND_CF_TOKEN to run", !token.isNullOrBlank())
        val http = OkHttpClient()
        val result = R2Setup(CloudflareApi(http), http).withToken(token!!, account)
        assertTrue("expected Connected, got $result", result is SetupResult.Connected)
        val config = (result as SetupResult.Connected).config
        println("connected to ${config.identity}, captions=${config.captions}")

        val client = R2Client(config, base = http)
        val key = "portland-live-test/${System.currentTimeMillis()}.txt"
        val bytes = "live ${System.nanoTime()}".toByteArray()
        client.putBytes(key, bytes, "text/plain")
        try {
            assertArrayEquals(bytes, client.get(key))
            assertEquals(listOf(key), client.list("portland-live-test/").map { it.first }.filter { it == key })
        } finally {
            client.delete(key)
        }
        assertNull(client.get(key))
    }
}
