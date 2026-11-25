package app.portland.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncTest {
    private val day = 24 * 60 * 60 * 1000L
    private val now = 100 * day

    @Test
    fun `orphans are deleted pins at once and unknown objects after a grace period`() {
        val lib = Library(
            pins = listOf(Pin("live", false, "jpg", 1, 1, createdAt = 1), Pin("local", true, "mp4", 1, 1, createdAt = 1, synced = false)),
            deleted = mapOf("gone" to 5L),
        )
        val objects = listOf(
            "media/live.jpg" to now, "thumbs/live.webp" to now - 9 * day, // in use
            "media/local.mp4" to now, // still being uploaded by this install
            "media/gone.png" to now, "thumbs/gone.webp" to now, // tombstoned: immediately
            "media/fresh.jpg" to now - 1000, // another install may be mid-upload
            "media/old.jpg" to now - 2 * day, // nobody claims it
            "media/live.png" to now - 2 * day, // a stale copy under an old extension
        )
        assertEquals(listOf("media/gone.png", "thumbs/gone.webp", "media/old.jpg", "media/live.png"), orphans(lib, objects, now))
    }

    @Test
    fun `list pages parse with continuation`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?><ListBucketResult><IsTruncated>true</IsTruncated>
            <Contents><Key>media/a&amp;b.jpg</Key><LastModified>2026-01-02T03:04:05.000Z</LastModified><Size>1</Size></Contents>
            <Contents><Key>thumbs/c.webp</Key><LastModified>2026-01-02T03:04:06.000Z</LastModified></Contents>
            <NextContinuationToken>abc/+=</NextContinuationToken></ListBucketResult>"""
        val (items, token) = parseList(xml)
        assertEquals(listOf("media/a&b.jpg" to 1767323045000L, "thumbs/c.webp" to 1767323046000L), items)
        assertEquals("abc/+=", token)
        assertNull(parseList("<ListBucketResult><IsTruncated>false</IsTruncated></ListBucketResult>").second)
    }

    @Test
    fun `colours survive json and a merge with a copy that lacks them`() {
        val tagged = Pin("a", false, "jpg", 1, 1, createdAt = 1, updatedAt = 5, colors = listOf("blue"), synced = true)
        assertEquals(tagged, Library.fromJson(Library(pins = listOf(tagged)).toJson()).pins.single())
        val newerUntagged = tagged.copy(note = "edited", updatedAt = 9, colors = null)
        val merged = Library.merge(Library(pins = listOf(tagged)), Library(pins = listOf(newerUntagged))).pins.single()
        assertEquals("edited", merged.note)
        assertEquals(listOf("blue"), merged.colors)
    }
}
