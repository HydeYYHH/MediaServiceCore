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

    @Test fun buttonCommandsCarryTheNextPostsPage() {
        val root = jsonObject("""{"continuationItemRenderer":{"button":{"buttonRenderer":{"command":{"continuationCommand":{"token":"more-posts"}}}}}}""")!!
        assertEquals("more-posts", postContinuation(root))
    }

    @Test fun protocolRelativeAuthorPhotosAreUsableHttpsUrls() {
        val root = jsonObject(body.replace("https://yt3/a.png", "//yt3/a.png"))!!
        assertEquals("https://yt3/a.png", posts(root, "UCfallback").single().authorAvatar)
    }

    @Test fun postCommentsComeFromTheirOwnSectionInsteadOfAnUnrelatedContinuation() {
        val html = """<script>window["ytInitialData"] = {"contents":[
            {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"unrelated"}}}},
            {"itemSectionRenderer":{"sectionIdentifier":"comment-item-section","contents":[
                {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"post-comments"}}}}
            ]}}
        ]};</script>"""
        val http = object : HttpClient { override fun execute(request: HttpRequest) = HttpReply(200, html) }
        val page = loadPost("Ugkx_post", http).value!!
        assertEquals("post-comments", page.commentsKey)
        assertNull(page.nextKey)
    }

    @Test fun escapedInitialDataStillOpensThePost() {
        val html = "var ytInitialData = '\\x7b\\x22backstagePostRenderer\\x22:{\\x22postId\\x22:\\x22Ugkx_post\\x22}}';"
        val http = object : HttpClient { override fun execute(request: HttpRequest) = HttpReply(200, html) }
        assertEquals("Ugkx_post", loadPost("Ugkx_post", http).value!!.posts.single().postId)
    }

    @Test fun publicPostsAndTheirCommentContinuationsKeepTheWebLaneAndLocale() {
        val calls = ArrayList<HttpRequest>()
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                calls += request
                return HttpReply(200, """{"commentsHeaderRenderer":{"countText":{"simpleText":"20 Comments"}},"contents":[]}""")
            }
        }
        val context = RequestContext(accountId = "account", accessToken = "token", language = "zh-CN", generation = 7)
        assertTrue(loadChannelPosts("UCchannel", context.anonymous(), params = "posts", http = http).ok)
        assertEquals("20 Comments", loadPostComments("post-comments", context, http).value!!.total)
        calls.forEach {
            assertTrue(it.url.startsWith("https://www.youtube.com/youtubei/v1/browse?"))
            assertTrue(it.body.contains("\"clientName\":\"WEB\""))
            assertTrue(it.body.contains("\"hl\":\"zh-CN\""))
            assertFalse(it.headers.containsKey("Authorization"))
        }
        assertTrue(calls.last().body.contains("\"continuation\":\"post-comments\""))
        assertFalse(loadPostComments(" ", context, http).ok)
        assertEquals(2, calls.size)
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
