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
    @Test fun seedlessDocumentKeepsTheCurrentVideoSeparateFromTheReplacementIdentity() {
        val calls = ArrayList<HttpRequest>()
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                calls += request
                assertEquals("GET", request.method)
                return HttpReply(200,
                    """var ytInitialData = {"overlay":{"reelPlayerOverlayRenderer":{"doubleTapLikeButton":{"likeButtonRenderer":{"target":{"videoId":"current"}}}}},"replacementEndpoint":{"reelWatchEndpoint":{"videoId":"next"}},"title":{"videoTitleHeaderViewModel":{"videoTitle":{"content":"Current title"}}}};""")
            }
        }
        val context = RequestContext("account", "token", "visitor", "zh-CN", "CN", 7, "brand",
            visitorCookie = "VISITOR_INFO1_LIVE=private")
        val page = loadHome(HomeSection.SHORTS, context, http = http).value!!
        assertEquals(listOf("current", "next"), page.items.map { it.id })
        assertEquals("Current title", page.items.first().title)
        assertNull(page.items.last().title)
        val request = calls.single()
        assertEquals("https://m.youtube.com/shorts?hl=zh-CN", request.url)
        assertTrue(request.body.isEmpty())
        assertTrue(request.headers.keys.none { it.equals("Authorization", true) || it.equals("Cookie", true) ||
            it.equals("X-Goog-Visitor-Id", true) || it.equals("X-Goog-PageId", true) })
    }
    @Test fun authenticationAndVerificationFailuresAreNotBypassed() {
        val challenge = "https://www.google.com/sorry/index?continue=https%3A%2F%2Fm.youtube.com%2Fshorts"
        val failures = listOf(
            HttpReply(401, "{}") to RequestError(RequestFailure.AUTH, "http_401", 401),
            HttpReply(403, "{}") to RequestError(RequestFailure.AUTH, "http_403", 403),
            HttpReply(429, "{}", mapOf("Retry-After" to "7")) to RequestError(RequestFailure.HTTP, "http_429", 429, 7_000),
            HttpReply(302, "", mapOf("Location" to challenge)) to RequestError(RequestFailure.VERIFICATION,
                "traffic_challenge", 302, detail = challenge),
        )
        for ((reply, expected) in failures) {
            var count = 0
            val http = object : HttpClient {
                override fun execute(request: HttpRequest): HttpReply {
                    count++
                    assertEquals("GET", request.method)
                    return reply
                }
            }
            val result = loadHome(HomeSection.SHORTS, http = http)
            assertNull(result.value)
            assertEquals(expected, result.error)
            assertEquals(1, count)
        }
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
