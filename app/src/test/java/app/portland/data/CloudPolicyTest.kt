package app.portland.data

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.URLDecoder

class CloudPolicyTest {
    private val config = R2Config(FakeCloudflare.ACCOUNT, "portland", "id", "secret", apiToken = "tok", captions = true)

    @Test
    fun `the backup card shows until connected, snoozed or turned off for good`() {
        val now = 1_000_000L
        assertTrue(CloudPolicy.showNudge(CloudState(), now))
        assertFalse(CloudPolicy.showNudge(CloudState(config = config), now))
        assertFalse(CloudPolicy.showNudge(CloudState(nudgeNever = true), now))
        assertFalse(CloudPolicy.showNudge(CloudState(nudgeAfter = now + 1), now))
        assertTrue("comes back once the snooze is over", CloudPolicy.showNudge(CloudState(nudgeAfter = now), now))
        assertFalse("never outlasts any snooze", CloudPolicy.showNudge(CloudState(nudgeNever = true, nudgeAfter = 0), now + CloudPolicy.SNOOZE_MS))
    }

    private val inR2Here = Pin("a", false, "jpg", 1, 1, createdAt = 3, synced = true)
    private val inR2Only = Pin("b", false, "jpg", 1, 1, createdAt = 2, synced = true)
    private val notUploaded = Pin("c", true, "mp4", 1, 1, createdAt = 1, synced = false)
    private val pins = listOf(inR2Here, inR2Only, notUploaded)
    private val onPhone = setOf("a", "c")

    @Test
    fun `cloud mode frees only phone copies that are safely in R2`() {
        assertEquals(listOf(inR2Here), CloudPolicy.toEvict(pins) { it.id in onPhone })
    }

    @Test
    fun `backup mode and leaving R2 fetch pins that live only in R2`() {
        assertEquals(listOf(inR2Only), CloudPolicy.toFetch(pins) { it.id in onPhone })
    }

    @Test
    fun `a new bucket gets every pin this phone holds and forgets the old bucket's deletions`() {
        val lib = Library(pins = pins, purge = listOf("media/old.jpg"))
        val next = CloudPolicy.forNewBucket(lib) { it.id in onPhone }
        assertEquals(listOf(false, true, false), next.pins.map { it.synced })
        assertTrue(next.purge.isEmpty())
    }

    @Test
    fun `config survives json and never prints its keys`() {
        assertEquals(config, R2Config.fromJson(config.toJson()))
        val keys = config.copy(apiToken = null, captions = false)
        assertEquals(keys, R2Config.fromJson(keys.toJson()))
        assertFalse(config.toString().contains("secret"))
        assertFalse(config.toString().contains("tok"))
        assertEquals("${FakeCloudflare.ACCOUNT}/portland", config.identity)
    }

    @Test
    fun `the token link pre-fills an account token with R2, account and Workers AI permissions`() {
        val uri = URI(TOKEN_TEMPLATE_URL)
        assertEquals("dash.cloudflare.com", uri.host)
        assertEquals("/", uri.path)
        val params = uri.rawQuery.split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        assertEquals("/:account/api-tokens", params["to"])
        val permissions = JSONArray(params.getValue("permissionGroupKeys"))
        val pairs = (0 until permissions.length()).map { permissions.getJSONObject(it).let { p -> p.getString("key") to p.getString("type") } }
        assertEquals(
            listOf("workers_r2" to "edit", "account_settings" to "read", "a92d2450e05d4e7bb7d0a64968f83d11" to "read"),
            pairs,
        )
        assertEquals("Portland", params["name"])
    }
}
