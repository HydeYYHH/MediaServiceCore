package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class WebDocumentTest {
    @Test fun parsesJsonAndEscapedDocumentsWithoutExecutingScripts() {
        assertEquals("a}b", initialDataDocument("var ytInitialData = {\"title\":\"a}b\"};alert(1)")!!.text("title"))
        val quoted = "var ytInitialData = '\\x7b\\x22title\\x22:\\x22Hello\\x22}';alert(1)"
        assertEquals("Hello", initialDataDocument(quoted)!!.text("title"))
        assertNull(initialDataDocument("var ytInitialData = {"))
    }
    @Test fun aRejectedSeedlessEndpointFallsBackToThePublicDocumentAndKeepsTheCurrentIdentity() {
        val calls = ArrayList<HttpRequest>()
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                calls += request
                return if (request.method == "POST") HttpReply(400, "{}") else HttpReply(200,
                    """var ytInitialData = {"overlay":{"reelPlayerOverlayRenderer":{"doubleTapLikeButton":{"likeButtonRenderer":{"target":{"videoId":"current"}}}}},"replacementEndpoint":{"reelWatchEndpoint":{"videoId":"next"}},"title":{"videoTitleHeaderViewModel":{"videoTitle":{"content":"Current title"}}}};""")
            }
        }
        val page = loadHome(HomeSection.SHORTS, http = http).value!!
        assertEquals(listOf("current", "next"), page.items.map { it.id })
        assertEquals("Current title", page.items.first().title)
        assertNull(page.items.last().title)
        assertEquals(2, calls.size)
        assertEquals("GET", calls.last().method)
    }
    @Test fun authenticationAndVerificationFailuresAreNotBypassed() {
        var count = 0
        val http = object : HttpClient { override fun execute(request: HttpRequest): HttpReply { count++; return HttpReply(429, "{}") } }
        assertFalse(loadHome(HomeSection.SHORTS, http = http).ok)
        assertEquals(1, count)
    }
    @Test fun anonymousWatchProgressRequiresAnIdentityAndOnlyUsesApprovedTrackingOrigins() {
        val calls = ArrayList<HttpRequest>()
        val http = object : HttpClient { override fun execute(request: HttpRequest): HttpReply { calls += request; return HttpReply(200, "") } }
        val history = WatchHistory(http)
        val tracking = PlayerTracking("https://www.youtube.com/api/stats/playback", "https://www.youtube.com/api/stats/watchtime")
        assertFalse(history.update("video", 1, 10, tracking, RequestContext()).ok)
        assertTrue(history.update("video", 1, 10, tracking, RequestContext(visitor = "visitor")).ok)
        assertTrue(history.update("video", 5, 10, tracking, RequestContext(visitor = "visitor")).ok)
        // The playback record and the chained watch-time report both carry the
        // visitor, never an Authorization header.
        assertEquals(2, calls.size)
        calls.forEach {
            assertEquals("visitor", it.headers["X-Goog-Visitor-Id"])
            assertFalse(it.headers.containsKey("Authorization"))
        }
        assertFalse(history.update("other", 1, 10, tracking.copy(playbackUrl = "https://other.com/api/stats/playback"), RequestContext(visitor = "visitor")).ok)
        assertEquals(2, calls.size)
    }
}
