package app.portland.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PostsTest {
    @Test
    fun `recognises X post links in their many forms`() {
        assertEquals("1854566771139989544", Posts.tweetId("https://x.com/someone/status/1854566771139989544"))
        assertEquals("1854566771139989544", Posts.tweetId("https://twitter.com/someone/status/1854566771139989544?s=20&t=abc"))
        assertEquals("1854566771139989544", Posts.tweetId("https://mobile.twitter.com/someone/status/1854566771139989544/photo/2"))
        assertEquals("1854566771139989544", Posts.tweetId("https://www.x.com/i/web/status/1854566771139989544"))
        assertEquals("1854566771139989544", Posts.tweetId("https://fxtwitter.com/someone/status/1854566771139989544"))
        assertNull(Posts.tweetId("https://x.com/someone"))
        assertNull(Posts.tweetId("https://example.com/someone/status/1854566771139989544"))
        assertNull(Posts.tweetId("not a url"))
    }

    @Test
    fun `recognises Pinterest pins on any regional site`() {
        assertEquals("123456789012345678", Posts.pinId("https://www.pinterest.com/pin/123456789012345678/"))
        assertEquals("123456789012345678", Posts.pinId("https://in.pinterest.com/pin/123456789012345678"))
        assertEquals("123456789012345678", Posts.pinId("https://pinterest.co.uk/pin/cozy-reading-nook--123456789012345678/"))
        assertNull(Posts.pinId("https://www.pinterest.com/someone/boards/"))
        assertNull(Posts.pinId("https://notpinterest.example.com/pin/123/"))
        assertTrue(Posts.isPinShortLink("https://pin.it/4abcDEF"))
        assertFalse(Posts.isPinShortLink("https://www.pinterest.com/pin/1/"))
    }

    @Test
    fun `syndication token matches what X's widget computes in JavaScript`() {
        // Reference values from Node: ((Number(id) / 1e15) * Math.PI).toString(36).replace(/(0+|\.)/g, "")
        assertEquals("6dq1a2xwd93", Posts.syndicationToken("20"))
        assertEquals("3mgyc5hyb7", Posts.syndicationToken("1495083934434603009"))
        assertEquals("4huak68y5e3", Posts.syndicationToken("1854566771139989544"))
        assertEquals("46yye21k8o", Posts.syndicationToken("1730000000000000000"))
        assertEquals("2zqic77uqyk", Posts.syndicationToken("1234567890123456789"))
        assertEquals("3vmjqiybmlu", Posts.syndicationToken("1600000000000000000"))
        assertEquals("2f9lc2ug9mm", Posts.syndicationToken("999999999999999999"))
    }

    @Test
    fun `base 36 printing follows JavaScript`() {
        assertEquals("0.000000006dq1a2xwd93", jsRadix36(20 / 1e15 * Math.PI))
        assertEquals("4hu.ak68y5e3", jsRadix36(1854566771139989544.0 / 1e15 * Math.PI))
        assertEquals("0", jsRadix36(0.0))
        assertEquals("10", jsRadix36(36.0))
        assertEquals("0.i", jsRadix36(0.5))
    }

    @Test
    fun `api urls carry the id`() {
        val tweet = Posts.tweetApi("20")
        assertEquals("cdn.syndication.twimg.com", tweet.host)
        assertEquals("20", tweet.queryParameter("id"))
        assertEquals("6dq1a2xwd93", tweet.queryParameter("token"))
        val pin = Posts.pinApi("42")
        assertEquals("/resource/PinResource/get/", pin.encodedPath)
        assertEquals("/pin/42/", pin.queryParameter("source_url"))
        assertTrue(pin.queryParameter("data")!!.contains("\"id\":\"42\""))
    }

    @Test
    fun `every photo and video of an X post, best quality, in order`() {
        val json = """
            {"__typename":"Tweet","id_str":"1","mediaDetails":[
              {"type":"photo","media_url_https":"https://pbs.twimg.com/media/AAA.jpg"},
              {"type":"video","media_url_https":"https://pbs.twimg.com/ext_tw_video_thumb/1/pu/img/B.jpg",
               "video_info":{"variants":[
                 {"content_type":"application/x-mpegURL","url":"https://video.twimg.com/b.m3u8"},
                 {"bitrate":632000,"content_type":"video/mp4","url":"https://video.twimg.com/b-480.mp4"},
                 {"bitrate":2176000,"content_type":"video/mp4","url":"https://video.twimg.com/b-1280.mp4"}]}},
              {"type":"animated_gif","video_info":{"variants":[{"bitrate":0,"content_type":"video/mp4","url":"https://video.twimg.com/g.mp4"}]}},
              {"type":"photo","media_url_https":"https://pbs.twimg.com/media/DDD.png?name=small"}
            ]}
        """.trimIndent()
        assertEquals(
            listOf(
                PostMedia("https://pbs.twimg.com/media/AAA.jpg?name=orig", video = false),
                PostMedia("https://video.twimg.com/b-1280.mp4", video = true),
                PostMedia("https://video.twimg.com/g.mp4", video = true),
                PostMedia("https://pbs.twimg.com/media/DDD.png?name=orig", video = false),
            ),
            Posts.tweetMedia(json),
        )
    }

    @Test
    fun `an X post without media, a deleted one, or garbage gives nothing`() {
        assertEquals(emptyList<PostMedia>(), Posts.tweetMedia("""{"__typename":"Tweet","text":"hi"}"""))
        assertEquals(emptyList<PostMedia>(), Posts.tweetMedia("""{"__typename":"TweetTombstone"}"""))
        assertEquals(emptyList<PostMedia>(), Posts.tweetMedia("<html>"))
        assertEquals(emptyList<PostMedia>(), Posts.tweetMedia(""))
    }

    @Test
    fun `every slot of a Pinterest carousel, original size`() {
        val json = """
            {"resource_response":{"data":{"id":"1",
              "images":{"orig":{"url":"https://i.pinimg.com/originals/aa/cover.jpg","width":1000}},
              "carousel_data":{"carousel_slots":[
                {"id":"s1","images":{"236x":{"url":"https://i.pinimg.com/236x/aa/1.jpg","width":236},
                                     "originals":{"url":"https://i.pinimg.com/originals/aa/1.jpg","width":1200}}},
                {"id":"s2","images":{"236x":{"url":"https://i.pinimg.com/236x/aa/2.jpg","width":236},
                                     "736x":{"url":"https://i.pinimg.com/736x/aa/2.jpg","width":736}}}
              ]}}}}
        """.trimIndent()
        assertEquals(
            listOf(
                PostMedia("https://i.pinimg.com/originals/aa/1.jpg", video = false),
                PostMedia("https://i.pinimg.com/736x/aa/2.jpg", video = false),
            ),
            Posts.pinMedia(json),
        )
    }

    @Test
    fun `every page of a Pinterest story pin, videos as mp4 when offered`() {
        val json = """
            {"resource_response":{"data":{"id":"1","story_pin_data":{"pages":[
              {"blocks":[{"type":"story_pin_image_block","image":{"images":{"originals":{"url":"https://i.pinimg.com/originals/p1.jpg"}}}}]},
              {"blocks":[{"type":"story_pin_video_block","video":{"id":"v","video_list":{
                 "V_HLSV4":{"url":"https://v1.pinimg.com/videos/v.m3u8","width":1080},
                 "V_720P":{"url":"https://v1.pinimg.com/videos/v_720.mp4","width":720},
                 "V_EXP7":{"url":"https://v1.pinimg.com/videos/v_1080.mp4","width":1080}}}}]},
              {"blocks":[{"type":"story_pin_paragraph_block","text":"hello"}]},
              {"blocks":[{"type":"story_pin_video_block","video":{"video_list":{
                 "V_HLSV3_MOBILE":{"url":"https://v1.pinimg.com/videos/w.m3u8","width":540}}}}]}
            ]}}}}
        """.trimIndent()
        assertEquals(
            listOf(
                PostMedia("https://i.pinimg.com/originals/p1.jpg", video = false),
                PostMedia("https://v1.pinimg.com/videos/v_1080.mp4", video = true),
                PostMedia("https://v1.pinimg.com/videos/w.m3u8", video = true),
            ),
            Posts.pinMedia(json),
        )
    }

    @Test
    fun `a plain pin is its one video or image`() {
        assertEquals(
            listOf(PostMedia("https://i.pinimg.com/originals/x.jpg", video = false)),
            Posts.pinMedia("""{"images":{"orig":{"url":"https://i.pinimg.com/originals/x.jpg"}},"videos":null}"""),
        )
        assertEquals(
            listOf(PostMedia("https://v1.pinimg.com/videos/x.mp4", video = true)),
            Posts.pinMedia(
                """{"images":{"orig":{"url":"https://i.pinimg.com/originals/x.jpg"}},
                   "videos":{"video_list":{"V_720P":{"url":"https://v1.pinimg.com/videos/x.mp4","width":720}}}}""",
            ),
        )
        assertEquals(emptyList<PostMedia>(), Posts.pinMedia("""{"resource_response":{"data":null}}"""))
    }

    @Test
    fun `a huge post is capped`() {
        val photos = (1..40).joinToString(",") { """{"type":"photo","media_url_https":"https://pbs.twimg.com/media/$it.jpg"}""" }
        assertEquals(Posts.MAX_ITEMS, Posts.tweetMedia("""{"mediaDetails":[$photos]}""").size)
    }
}
