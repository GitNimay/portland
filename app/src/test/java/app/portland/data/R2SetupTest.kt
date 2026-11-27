package app.portland.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class R2SetupTest {
    private val cf = FakeCloudflare()
    private val http = OkHttpClient()
    private val setup = R2Setup(CloudflareApi(http, cf.api), http, endpointFor = cf::endpoint, retryDelayMs = 0)

    @After fun close() = cf.stop()

    private fun token(value: String = "tok-value-1", id: String = "a".repeat(32), configure: FakeCloudflare.Token.() -> FakeCloudflare.Token = { this }) =
        FakeCloudflare.Token(id, value).configure().also { cf.tokens += it }

    private fun connected(result: SetupResult): R2Config {
        assertTrue("expected Connected, got $result", result is SetupResult.Connected)
        return (result as SetupResult.Connected).config
    }

    @Test
    fun `a token connects, makes the bucket and derives the S3 keys from itself`() = runBlocking {
        val t = token()
        val config = connected(setup.withToken("  ${t.value}\n"))
        assertEquals(FakeCloudflare.ACCOUNT, config.accountId)
        assertEquals(DEFAULT_BUCKET, config.bucket)
        assertEquals(t.id, config.accessKeyId)
        assertEquals(sha256Hex(t.value.toByteArray()), config.secretAccessKey)
        assertEquals(t.value, config.apiToken)
        assertTrue(config.captions)
        assertEquals(setOf(DEFAULT_BUCKET), cf.buckets)
        assertTrue("the probe object is cleaned up", cf.objects.isEmpty())
    }

    @Test
    fun `an existing bucket is used, not made again`() = runBlocking {
        val t = token()
        cf.buckets += DEFAULT_BUCKET
        cf.objects["$DEFAULT_BUCKET/index.json"] = "{}".toByteArray()
        connected(setup.withToken(t.value))
        assertEquals(0, cf.bucketsCreated)
        assertEquals(setOf("$DEFAULT_BUCKET/index.json"), cf.objects.keys)
    }

    @Test
    fun `several accounts ask which one, then connect to it`() = runBlocking {
        val t = token()
        val other = CloudflareAccount("fedcba9876543210fedcba9876543210", "Work")
        cf.accounts += other
        val ask = setup.withToken(t.value)
        assertEquals(SetupResult.PickAccount(cf.accounts.toList()), ask)
        assertEquals(other.id, connected(setup.withToken(t.value, other.id)).accountId)
    }

    @Test
    fun `a token that cannot list accounts asks for the account id`() = runBlocking {
        val t = token { copy(seesAccounts = false) }
        assertEquals(SetupResult.NeedAccountId, setup.withToken(t.value))
        assertEquals(FakeCloudflare.ACCOUNT, connected(setup.withToken(t.value, FakeCloudflare.ACCOUNT)).accountId)
    }

    @Test
    fun `an account-owned token is verified against its account`() = runBlocking {
        val t = token { copy(user = false, seesAccounts = false) }
        assertEquals(SetupResult.NeedAccountId, setup.withToken(t.value))
        assertEquals(t.id, connected(setup.withToken(t.value, FakeCloudflare.ACCOUNT)).accessKeyId)
    }

    @Test
    fun `a token from the app's link connects with no questions asked`() = runBlocking {
        // What the account-token link makes (checked live): not a user token, sees its one account, may use AI.
        val t = token { copy(user = false) }
        val config = connected(setup.withToken(t.value))
        assertEquals(FakeCloudflare.ACCOUNT, config.accountId)
        assertEquals(t.id, config.accessKeyId)
        assertTrue(config.captions)
        assertEquals(setOf(DEFAULT_BUCKET), cf.buckets)
    }

    @Test
    fun `a wrong token is reported as such`() = runBlocking {
        token()
        assertEquals(SetupResult.Failed(SetupProblem.BadToken), setup.withToken("not-a-real-token"))
        assertEquals(SetupResult.Failed(SetupProblem.BadToken), setup.withToken("has spaces in it"))
        assertEquals(SetupResult.Failed(SetupProblem.BadToken), setup.withToken(""))
    }

    @Test
    fun `an unknown account id is reported`() = runBlocking {
        val t = token { copy(seesAccounts = false) }
        assertEquals(SetupResult.Failed(SetupProblem.NoSuchAccount), setup.withToken(t.value, "ffffffffffffffffffffffffffffffff"))
    }

    @Test
    fun `an account without R2 turned on says so`() = runBlocking {
        val t = token()
        cf.r2Enabled = false
        assertEquals(SetupResult.Failed(SetupProblem.R2Off), setup.withToken(t.value))
    }

    @Test
    fun `a token limited to one bucket uses that bucket when it exists`() = runBlocking {
        val t = token { copy(r2Admin = false, seesAccounts = false) }
        cf.buckets += DEFAULT_BUCKET
        val config = connected(setup.withToken(t.value, FakeCloudflare.ACCOUNT))
        assertEquals(DEFAULT_BUCKET, config.bucket)
        assertEquals(0, cf.bucketsCreated)
    }

    @Test
    fun `a token limited to objects with no bucket to use lacks permission`() = runBlocking {
        val t = token { copy(r2Admin = false) }
        assertEquals(SetupResult.Failed(SetupProblem.NoPermission), setup.withToken(t.value))
    }

    @Test
    fun `a brand new token is retried while it spreads across Cloudflare`() = runBlocking {
        val t = token { copy(coldStarts = R2Setup.PROBE_ATTEMPTS - 1) }
        connected(setup.withToken(t.value))
        Unit
    }

    @Test
    fun `a token that never starts working is not accepted`() = runBlocking {
        val t = token { copy(coldStarts = R2Setup.PROBE_ATTEMPTS) }
        assertEquals(SetupResult.Failed(SetupProblem.BucketUnreachable), setup.withToken(t.value))
    }

    @Test
    fun `captions stay off when the token cannot use Workers AI`() = runBlocking {
        val t = token { copy(ai = false) }
        assertFalse(connected(setup.withToken(t.value)).captions)
    }

    @Test
    fun `access keys connect once the bucket answers`() = runBlocking {
        cf.buckets += "mine"
        cf.s3Keys["KEYID"] = "SECRET"
        val config = connected(setup.withKeys(FakeCloudflare.ACCOUNT, " mine ", "KEYID", "SECRET"))
        assertEquals(R2Config(FakeCloudflare.ACCOUNT, "mine", "KEYID", "SECRET"), config)
        assertEquals(SetupResult.Failed(SetupProblem.BucketUnreachable), setup.withKeys(FakeCloudflare.ACCOUNT, "mine", "KEYID", "WRONG"))
        assertEquals(SetupResult.Failed(SetupProblem.BucketUnreachable), setup.withKeys(FakeCloudflare.ACCOUNT, "nope", "KEYID", "SECRET"))
        assertEquals(SetupResult.Failed(SetupProblem.NoSuchAccount), setup.withKeys("not-an-id", "mine", "KEYID", "SECRET"))
    }

    @Test
    fun `no connection is reported as offline`() = runBlocking {
        val t = token()
        cf.stop()
        assertEquals(SetupResult.Failed(SetupProblem.Offline), setup.withToken(t.value))
    }

    @Test
    fun `the account id is found in whatever was pasted`() {
        val id = FakeCloudflare.ACCOUNT
        assertEquals(id, R2Setup.accountIdIn(id))
        assertEquals(id, R2Setup.accountIdIn(" ${id.uppercase()} "))
        assertEquals(id, R2Setup.accountIdIn("https://dash.cloudflare.com/$id/r2/overview"))
        assertEquals(id, R2Setup.accountIdIn("https://$id.r2.cloudflarestorage.com"))
        assertEquals(null, R2Setup.accountIdIn("1234"))
        assertEquals(null, R2Setup.accountIdIn(id + "0")) // 33 hex digits is not an account id
    }
}
