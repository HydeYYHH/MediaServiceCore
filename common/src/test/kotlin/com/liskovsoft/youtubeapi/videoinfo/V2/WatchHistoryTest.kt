package com.liskovsoft.youtubeapi.videoinfo.V2

import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class WatchHistoryTest {
    @Test fun accountTrackingUsesAuthenticatedTvPlaybackWithTheCurrentSignatureTimestamp() {
        val requests = mutableListOf<HttpRequest>()
        val context = RequestContext("account", "access", visitor = "visitor", onBehalfOfUser = "channel")
        val scripts = object : PlayerScriptProvider {
            override fun script(videoId: String, context: RequestContext, fresh: Boolean) =
                ReadResult(PlayerScript("https://www.youtube.com/s/player/test/base.js", "signatureTimestamp:20400"))
        }
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                requests += request
                return HttpReply(200, """{"playbackTracking":{"videostatsPlaybackUrl":{"baseUrl":"https://s.youtube.com/api/stats/playback?vm=account"},"videostatsWatchtimeUrl":{"baseUrl":"https://s.youtube.com/api/stats/watchtime?vm=account"}}}""")
            }
        }
        val result = loadAccountWatchTracking("video", context, http, scripts)
        assertTrue(result.ok)
        assertTrue(result.value!!.playbackUrl!!.contains("vm=account"))
        val request = requests.single()
        assertEquals("Bearer access", request.headers["Authorization"])
        val root = jsonObject(request.body)!!
        assertEquals("Samsung", root.path("context", "client")?.text("deviceMake"))
        assertEquals("channel", root.path("context", "user")?.text("onBehalfOfUser"))
        assertEquals(20400, root.path("playbackContext", "contentPlaybackContext")?.int("signatureTimestamp"))
    }

    @Test fun missingAccountTrackingIsAnErrorEvenWhenThePlayerReturnsHttpSuccess() {
        val scripts = object : PlayerScriptProvider {
            override fun script(videoId: String, context: RequestContext, fresh: Boolean) =
                ReadResult(PlayerScript("https://www.youtube.com/s/player/test/base.js", "signatureTimestamp:20400"))
        }
        val http = object : HttpClient {
            override fun execute(request: HttpRequest) = HttpReply(200, """{"playabilityStatus":{"status":"UNPLAYABLE"}}""")
        }
        assertEquals("missing_account_tracking", loadAccountWatchTracking("video", RequestContext("account", "access"), http, scripts).error?.code)
        assertEquals(RequestFailure.AUTH, loadAccountWatchTracking("video", RequestContext(), http, scripts).error?.kind)
    }

    @Test fun anonymousPlaybackUsesTheReturnedStatisticsHost() {
        val requests = mutableListOf<HttpRequest>()
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                requests += request
                return HttpReply(204, "")
            }
        }
        val history = WatchHistory(http)
        val context = RequestContext(visitor = "visitor-data", visitorCookie = "VISITOR_INFO1_LIVE=cookie")
        val tracking = PlayerTracking("https://s.youtube.com/api/stats/playback?ei=event&vm=keep",
            "https://s.youtube.com/api/stats/watchtime?of=keep")
        assertTrue(history.update("aqz-KE-bpKQ", 1000, 100_000, tracking, context).ok)
        assertTrue(history.update("aqz-KE-bpKQ", 11_000, 100_000, tracking, context).ok)
        assertEquals(2, requests.size)
        assertTrue(requests.all { URI(it.url).host == "s.youtube.com" })
        assertTrue(requests.all { urlParameter(it.url, "ver") == "2" })
        assertTrue(requests.all { it.headers["X-Goog-Visitor-Id"] == "visitor-data" })
        assertTrue(requests.all { it.headers["Cookie"] == context.visitorCookie })
        assertEquals("1.0", urlParameter(requests.last().url, "st"))
        assertEquals("11.0", urlParameter(requests.last().url, "et"))
        assertEquals("keep", urlParameter(requests.first().url, "vm"))
        assertEquals("keep", urlParameter(requests.last().url, "of"))
    }

    @Test fun trackingRejectsUntrustedOriginsAndMismatchedPathsBeforeSending() {
        val requests = mutableListOf<HttpRequest>()
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                requests += request
                return HttpReply(200, "")
            }
        }
        for (base in listOf("http://s.youtube.com/api/stats/playback",
            "https://s.youtube.com.evil.test/api/stats/playback",
            "https://s.youtube.com@evil.test/api/stats/playback",
            "https://name@s.youtube.com/api/stats/playback",
            "https://s.youtube.com:444/api/stats/playback",
            "https://s.youtube.com/api/stats/watchtime")) {
            val result = WatchHistory(http).update("aqz-KE-bpKQ", 1000, 100_000,
                PlayerTracking(base, "https://s.youtube.com/api/stats/watchtime"), RequestContext(visitor = "visitor"))
            assertEquals("invalid_tracking_url", result.error?.code)
        }
        assertTrue(requests.isEmpty())
    }

    @Test fun createsBeforeUpdatingAndPreservesTheAnswersParameters() {
        val requests = ArrayList<HttpRequest>()
        val http = object : HttpClient { override fun execute(request: HttpRequest): HttpReply { requests.add(request); return HttpReply(200, "") } }
        val history = WatchHistory(http); val c = RequestContext("a","t")
        val track = PlayerTracking("https://www.youtube.com/api/stats/playback?ei=event&vm=keep&ver=1", "https://www.youtube.com/api/stats/watchtime?of=keep")
        assertTrue(history.update("v", 1000, 10000, track, c).ok)
        // The first update only creates the playback record: a watch-time report must
        // span a real interval, so it goes out with the next update.
        assertEquals(1, requests.size)
        assertTrue(history.update("v", 2000, 10000, track, c).ok)
        assertEquals(2, requests.size); assertEquals("event", urlParameter(requests.first().url,"ei"))
        assertTrue(requests.all { urlParameter(it.url, "ver") == "2" })
        assertTrue(requests.all { URI(it.url).rawQuery.split('&').count { field -> field.startsWith("ver=") } == 1 })
        assertEquals("1.0", urlParameter(requests.last().url,"st")); assertEquals("2.0", urlParameter(requests.last().url,"et"))
        // The cpn is minted per playback session and stays stable across its reports.
        val cpn = urlParameter(requests.last().url,"cpn")
        assertNotNull(cpn); assertEquals(cpn, urlParameter(requests.first().url,"cpn"))
        assertFalse(history.update("v", 1, 10, track.copy(playbackUrl = "https://evil/api/stats/playback"), c.copy(accountId="b")).ok)
        assertEquals(2, requests.size)
    }
    @Test fun aSeekReanchorsTheLedgerWithoutReportingTheSkippedSpan() {
        val requests = ArrayList<HttpRequest>()
        val http = object : HttpClient { override fun execute(request: HttpRequest): HttpReply { requests.add(request); return HttpReply(200, "") } }
        val history = WatchHistory(http); val c = RequestContext("a","t")
        val track = PlayerTracking("https://www.youtube.com/api/stats/playback?ei=event", "https://www.youtube.com/api/stats/watchtime?of=keep")
        assertTrue(history.update("v", 1000, 10000, track, c).ok)
        assertTrue(history.update("v", 2000, 10000, track, c).ok)
        assertEquals(2, requests.size)
        // Seek from 2s to 8s: the skipped 6s span is never sent; the ledger
        // re-anchors so the next report chains from the seek target.
        assertTrue(history.update("v", 8000, 10000, track, c, discontinuity = true).ok)
        assertEquals(2, requests.size)
        assertTrue(history.update("v", 9000, 10000, track, c).ok)
        assertEquals(3, requests.size)
        assertEquals("8.0", urlParameter(requests.last().url,"st")); assertEquals("9.0", urlParameter(requests.last().url,"et"))
    }
    @Test fun historyControlsRequireAuthentication() {
        assertEquals(RequestFailure.AUTH, clearWatchHistory(RequestContext()).error!!.kind)
    }
}
