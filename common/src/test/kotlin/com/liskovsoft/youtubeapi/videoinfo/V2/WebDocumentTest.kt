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
        val context = RequestContext(accountId = "account", webSession = WebSession("SAPISID=token", 3), visitor = "visitor", language = "zh-CN", region = "CN", generation = 7, visitorCookie = "VISITOR_INFO1_LIVE=private")
        val page = loadHome(HomeSection.SHORTS, context, http = http).value!!
        assertEquals(listOf("current", "next"), page.items.map { it.id })
        assertEquals("Current title", page.items.first().title)
        assertNull(page.items.last().title)
        val request = calls.single()
        assertEquals("https://m.youtube.com/shorts?hl=zh-CN&authuser=3", request.url)
        assertTrue(request.body.isEmpty())
        assertEquals("SAPISID=token", request.headers["Cookie"])
        assertEquals(setOf("User-Agent", "Cookie"), request.headers.keys)
        assertFalse(request.headers.containsKey("Authorization"))
        assertFalse(request.headers.containsKey("Origin"))
        assertFalse(request.headers.containsKey("X-Origin"))
        assertFalse(request.headers.containsKey("X-Goog-PageId"))
    }

    @Test fun anonymousDocumentRetainsVisitorCookiesWithoutInventingAnAccountSelection() {
        val calls = ArrayList<HttpRequest>()
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                calls += request
                return HttpReply(200, """var ytInitialData = {"entries":[],"sequenceContinuation":"visitor-next"};""")
            }
        }
        val context = RequestContext(visitor = "visitor", visitorCookie = "VISITOR_INFO1_LIVE=fixture")
        assertEquals("visitor-next", loadHome(HomeSection.SHORTS, context, http = http).value!!.nextKey)
        val request = calls.single()
        assertEquals("https://m.youtube.com/shorts?hl=en", request.url)
        assertEquals("VISITOR_INFO1_LIVE=fixture", request.headers["Cookie"])
        assertEquals(setOf("User-Agent", "Cookie"), request.headers.keys)
    }

    @Test fun delegatedShortsEntryFailsBeforeTheDocumentAndNeverFallsBackToAPersonalAccount() {
        var calls = 0
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                calls++
                error("A brand cannot be represented by the unverified document account selector")
            }
        }
        val context = RequestContext(accountId = "brand-account", webSession = WebSession("SAPISID=fixture", 2), onBehalfOfUser = "brand-page")
        val result = loadHome(HomeSection.SHORTS, context, http = http)
        assertNull(result.value)
        assertEquals(RequestFailure.UNSUPPORTED, result.error!!.kind)
        assertEquals("shorts_brand_document_identity_unsupported", result.error!!.code)
        assertEquals(0, calls)
    }

    @Test fun signedInShortsContinuationKeepsApiAuthorizationAndItsSelectedChannel() {
        val context = RequestContext(accountId = "brand-account", webSession = WebSession("SAPISID=fixture", 2), onBehalfOfUser = "brand-page")
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                assertEquals("POST", request.method)
                assertTrue(request.url.endsWith("/reel/reel_watch_sequence"))
                assertEquals("SAPISID=fixture", request.headers["Cookie"])
                assertTrue(request.headers["Authorization"]!!.startsWith("SAPISIDHASH "))
                assertEquals("2", request.headers["X-Goog-AuthUser"])
                assertEquals("brand-page", request.headers["X-Goog-PageId"])
                assertEquals("brand-page", jsonObject(request.body)!!.path("context", "user")!!.text("onBehalfOfUser"))
                assertEquals("brand-next", jsonObject(request.body)!!.text("sequenceParams"))
                return HttpReply(200, """{"entries":[]}""")
            }
        }
        assertTrue(loadHome(HomeSection.SHORTS, context, "brand-next", http).ok)
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
