package com.liskovsoft.youtubeapi.videoinfo.V2
import org.junit.Assert.*
import org.junit.Test

class PostBrowseTest {
    private val body = """{"contents":[{"backstagePostRenderer":{
        "postId":"Ugwx1","authorEndpoint":{"browseEndpoint":{"browseId":"UCabc"}},
        "authorText":{"simpleText":"Blender"},"authorThumbnail":{"thumbnails":[{"url":"https://yt3/a.png"}]},
        "publishedTimeText":{"simpleText":"2 days ago"},"contentText":{"simpleText":"Hello"},
        "backstageAttachment":{"backstageImageRenderer":{"image":{"thumbnails":[{"url":"https://i/img.png"}]}}},
        "voteCount":{"simpleText":"1.2K"},"replyCount":{"simpleText":"34"}}}],
        "continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"next1"}}}}"""

    @Test fun channelPostsParseRowsAndTheirOwnContinuation() {
        val root = jsonObject(body)!!
        val page = posts(root, "UCfallback")
        assertEquals(1, page.size)
        val post = page.single()
        assertEquals("Ugwx1", post.postId)
        assertEquals("UCabc", post.authorId)
        assertEquals("Blender", post.author)
        assertEquals("https://yt3/a.png", post.authorAvatar)
        assertEquals("2 days ago", post.published)
        assertEquals("Hello", post.text)
        assertEquals(listOf("https://i/img.png"), post.images)
        assertEquals("1.2K", post.likes)
        assertEquals("34", post.replies)
        assertEquals("next1", postContinuation(root))
    }

    @Test fun aPostWithoutItsOwnAuthorEndpointFallsBackToTheChannel() {
        val root = jsonObject("""{"backstagePostRenderer":{"postId":"Ugwx2"}}""")!!
        assertEquals("UCfallback", posts(root, "UCfallback").single().authorId)
    }

    @Test fun continuationAlsoRidesTheButtonRenderer() {
        val root = jsonObject("""{"continuationItemRenderer":{"button":{"buttonRenderer":{"continuationCommand":{"token":"btn"}}}}}""")!!
        assertEquals("btn", postContinuation(root))
    }

    @Test fun aMissingContinuationMeansTheListEnded() {
        assertNull(postContinuation(jsonObject("""{"contents":[]}""")!!))
    }

    @Test fun aMalformedPostIdFailsTypedBeforeAnyNetwork() {
        val failure = loadPost("../etc", http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply = error("must not run")
        })
        assertFalse(failure.ok)
        assertEquals(RequestFailure.INVALID, failure.error?.kind)
    }

    @Test fun thePostPageCarvesYtInitialDataOutOfTheDocument() {
        val page = """<html><script>var ytInitialData = {"contents":[{"backstagePostRenderer":{"postId":"Ugkz2",
            "contentText":{"simpleText":"From the page"}}}]};</script></html>"""
        val http = object : HttpClient { override fun execute(request: HttpRequest) = HttpReply(200, page) }
        val result = loadPost("Ugkz2abc", http = http)
        assertTrue(result.ok)
        assertEquals("Ugkz2", result.value!!.posts.single().postId)
        assertEquals("From the page", result.value!!.posts.single().text)
    }
}
