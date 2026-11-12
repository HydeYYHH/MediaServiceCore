package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class VisitorWatchTrackingTest {
    private fun answer(id: String = "video", playback: String = "https://www.youtube.com/api/stats/playback?vm=server") =
        """{"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"$id"},"playbackTracking":{
            "videostatsPlaybackUrl":{"baseUrl":"$playback"},"videostatsWatchtimeUrl":{"baseUrl":"https://s.youtube.com/api/stats/watchtime?of=server"}}}"""
    private fun http(block: (HttpRequest) -> HttpReply) = object : HttpClient { override fun execute(request: HttpRequest) = block(request) }

    @Test fun visitorTrackingUsesTheNativeWebIdentityAndDoesNotClaimPersonalizationFromStatisticsAcceptance() {
        val context = VisitorSession("YSC=fixture; VISITOR_INFO1_LIVE=fixture", "visitor-data").requestContext("fr", "CA", 2)
        val requests = ArrayList<HttpRequest>()
        val result = loadWatchTracking("video", context, http { request -> requests += request; HttpReply(200, answer()) })
        assertTrue(result.ok)
        val request = requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://www.youtube.com/youtubei/v1/player?prettyPrint=false", request.url)
        assertEquals(context.visitorCookie, request.headers["Cookie"])
        assertEquals("visitor-data", request.headers["X-Goog-Visitor-Id"])
        assertTrue(request.headers.keys.none { it.equals("Authorization", true) || it.equals("X-Goog-AuthUser", true) || it.equals("X-Goog-PageId", true) })
        val root = jsonObject(request.body)!!
        assertEquals("WEB", root.path("context", "client")!!.text("clientName"))
        assertEquals("visitor-data", root.path("context", "client")!!.text("visitorData"))
        assertEquals("fr", root.path("context", "client")!!.text("hl"))
        assertNull(root.obj("playbackContext"))
        assertEquals("https://s.youtube.com/api/stats/watchtime?of=server", result.value!!.watchTimeUrl)
        assertEquals("missing_watch_identity", loadWatchTracking("video", RequestContext()).error!!.code)
    }

    @Test fun signedTrackingRetainsAccountSelectionAndTheExistingAccountOnlyEntryStaysSignedOnly() {
        val context = RequestContext(accountId = "owner", webSession = WebSession("SAPISID=secret", 3), onBehalfOfUser = "brand")
        var calls = 0
        val transport = http { request ->
            calls++
            assertEquals("3", request.headers["X-Goog-AuthUser"])
            assertEquals("brand", request.headers["X-Goog-PageId"])
            assertTrue(request.headers["Authorization"]!!.startsWith("SAPISIDHASH "))
            HttpReply(200, answer())
        }
        assertTrue(loadWatchTracking("video", context, transport).ok)
        assertTrue(loadAccountWatchTracking("video", context, transport).ok)
        val visitor = VisitorSession("YSC=fixture", "visitor").requestContext()
        assertEquals("sign_in_required", loadAccountWatchTracking("video", visitor, transport).error!!.code)
        assertEquals(2, calls)
    }

    @Test fun visitorTrackingFailsExplicitlyForUnplayableOrMismatchedResponsesWithoutChangingClient() {
        val context = VisitorSession("YSC=fixture", "visitor").requestContext()
        var calls = 0
        var body = """{"playabilityStatus":{"status":"UNPLAYABLE"}}"""
        val transport = http { request ->
            calls++
            assertEquals("WEB", jsonObject(request.body)!!.path("context", "client")!!.text("clientName"))
            HttpReply(200, body)
        }
        assertEquals("playability_unplayable", loadWatchTracking("video", context, transport).error!!.code)
        assertEquals(1, calls)
        body = answer("other")
        assertEquals("tracking_video_mismatch", loadWatchTracking("video", context, transport).error!!.code)
        body = answer(playback = "https://evil.test/api/stats/playback")
        assertEquals("invalid_watch_tracking_url", loadWatchTracking("video", context, transport).error!!.code)
        body = """{"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"video"}}"""
        assertEquals("missing_watch_tracking", loadWatchTracking("video", context, transport).error!!.code)
        val invalid = context.copy(visitorCookie = "SAPISID=credentials")
        assertEquals("invalid_visitor_session", loadWatchTracking("video", invalid, transport).error!!.code)
        assertEquals(4, calls)
    }

    @Test fun successfulPlaybackAlreadyProvidesVisitorTrackingForTheSameLedgerWithoutAnotherPlayerRead() {
        val context = VisitorSession("YSC=fixture; VISITOR_INFO1_LIVE=fixture", "visitor-data").requestContext()
        val requests = ArrayList<HttpRequest>()
        val transport = http { request ->
            requests += request
            assertEquals(context.visitorCookie, request.headers["Cookie"])
            assertEquals(context.visitor, request.headers["X-Goog-Visitor-Id"])
            assertFalse(request.headers.containsKey("Authorization"))
            if (request.method == "POST") {
                assertEquals("VISIONOS", jsonObject(request.body)!!.path("context", "client")!!.text("clientName"))
                HttpReply(200, """{"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"video","lengthSeconds":"100"},
                    "streamingData":{"adaptiveFormats":[{"itag":137,"mimeType":"video/mp4","height":1080,"url":"https://example.test/media"},
                        {"itag":140,"mimeType":"audio/mp4","url":"https://example.test/audio"}]},
                    "playbackTracking":{"videostatsPlaybackUrl":{"baseUrl":"https://www.youtube.com/api/stats/playback?vm=server"},
                    "videostatsWatchtimeUrl":{"baseUrl":"https://s.youtube.com/api/stats/watchtime?of=server"}}}""")
            } else HttpReply(204, "")
        }
        val excluded = PlayerClient.values().filter { it != PlayerClient.VISIONOS }.toSet()
        val loaded = PlaybackSession(transport).load("video", context, excluded = excluded, includeMuxedFloor = false).value!!
        assertEquals(PlayerClient.VISIONOS, loaded.client)
        val details = loaded.answer!!.details!!
        val ledger = WatchHistory(transport)
        assertTrue(ledger.update("video", 0, details.durationSeconds!! * 1000, details.tracking!!, context).ok)
        assertTrue(ledger.update("video", 5000, details.durationSeconds!! * 1000, details.tracking!!, context).ok)
        assertEquals(1, requests.count { it.method == "POST" })
        assertEquals(2, requests.count { it.method == "GET" })
        assertTrue(requests.last().url.contains("of=server"))
        assertTrue(requests.last().url.contains("st=0.0"))
        assertTrue(requests.last().url.contains("et=5.0"))
    }
}
