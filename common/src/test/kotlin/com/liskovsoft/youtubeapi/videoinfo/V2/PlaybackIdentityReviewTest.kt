package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class PlaybackIdentityReviewTest {
    @Test fun refusedSupplementalClientIsNotMergedBackIntoTheCatalog() {
        var floorRequests = 0
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply = if (request.url.contains("reel_item_watch")) {
                floorRequests++
                HttpReply(200, """{"playerResponse":{"videoDetails":{"videoId":"video"},"streamingData":{"formats":[{"url":"https://floor","mimeType":"video/mp4","itag":18}]}}}""")
            } else HttpReply(200, """{"videoDetails":{"videoId":"video"},"streamingData":{"adaptiveFormats":[{"url":"https://primary","mimeType":"video/mp4","itag":137},{"url":"https://audio","mimeType":"audio/mp4","itag":140}]}}""")
        }
        val session = PlaybackSession(http)
        val excluded = session.load("video", excluded = setOf(PlayerClient.ANDROID_VR))
        assertTrue(excluded.ok)
        assertTrue(excluded.value!!.answer!!.muxedFormats.isEmpty())
        assertNull(excluded.value!!.floorClient)
        assertEquals(0, floorRequests)
        val allowed = session.load("video")
        assertTrue(allowed.ok)
        assertEquals(1, allowed.value!!.answer!!.muxedFormats.size)
        assertEquals(PlayerClient.ANDROID_VR, allowed.value!!.floorClient)
        assertEquals(1, floorRequests)
    }

    @Test fun cookieIdentityReachesTheWebClientWithoutBearerCredentials() {
        var first: HttpRequest? = null
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                if (first == null) first = request
                return HttpReply(200, """{"streamingData":{"formats":[{"url":"https://media","mimeType":"video/mp4"}]}}""")
            }
        }
        assertTrue(loadPlayer("video", RequestContext(webSession = WebSession("SAPISID=test")), http).ok)
        assertEquals("SAPISID=test", first!!.headers["Cookie"])
        assertTrue(first!!.headers["Authorization"]!!.startsWith("SAPISIDHASH "))
    }

    @Test fun bearerIdentityOnlyReachesTheSupportedClient() {
        val requests = mutableListOf<HttpRequest>()
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                requests += request
                val authenticated = request.headers["Authorization"] == "Bearer fixture"
                return HttpReply(200, if (authenticated)
                    """{"streamingData":{"formats":[{"url":"https://media","mimeType":"video/mp4"}]}}"""
                    else """{"playabilityStatus":{"status":"LOGIN_REQUIRED"}}""")
            }
        }
        assertTrue(loadPlayer("video", RequestContext(accessToken = "fixture"), http).ok)
        assertEquals(1, requests.count { it.headers.containsKey("Authorization") })
        assertTrue(requests.last().body.contains("TVHTML5"))
        assertFalse(requests.any { it.headers.containsKey("Cookie") })
    }

    @Test fun aMuxedFallbackCannotBorrowAnotherVideosMediaOrExtendExpiry() {
        fun load(floorId: String): PlayerAnswer {
            val http = object : HttpClient {
                override fun execute(request: HttpRequest): HttpReply = if (request.url.contains("reel_item_watch"))
                    HttpReply(200, """{"playerResponse":{"videoDetails":{"videoId":"$floorId"},"streamingData":{"expiresInSeconds":1,"formats":[{"url":"https://floor","mimeType":"video/mp4","itag":18}]}}}""")
                else HttpReply(200, """{"videoDetails":{"videoId":"video"},"streamingData":{"expiresInSeconds":60,"adaptiveFormats":[{"url":"https://primary","mimeType":"video/mp4","itag":137},{"url":"https://audio","mimeType":"audio/mp4","itag":140}]}}""")
            }
            return PlaybackSession(http, now = { 1000L }).load("video").value!!.answer!!
        }
        assertTrue(load("another").muxedFormats.isEmpty())
        val accepted = load("video")
        assertEquals(1, accepted.muxedFormats.size)
        assertEquals(2000L, accepted.expiresAtMs)
    }
}
