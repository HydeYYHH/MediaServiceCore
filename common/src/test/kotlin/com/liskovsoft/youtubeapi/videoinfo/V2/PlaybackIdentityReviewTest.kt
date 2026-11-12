package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class PlaybackIdentityReviewTest {
    @Test fun transportCapabilitiesParticipateInAcceptanceAndCacheIdentity() {
        var requests = 0
        val session = PlaybackSession(object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                requests++
                return HttpReply(200, if (requests <= 2)
                    """{"streamingData":{"serverAbrStreamingUrl":"https://media.test/sabr"},
                        "playerConfig":{"mediaCommonConfig":{"mediaUstreamerRequestConfig":{"videoPlaybackUstreamerConfig":"config"}}}}"""
                else """{"streamingData":{"formats":[{"url":"https://media.test/muxed"}]}}""")
            }
        })
        val supported = session.load("video", includeMuxedFloor = false)
        assertTrue(supported.ok)
        assertEquals("https://media.test/sabr", supported.value!!.playback.sabrUrl)
        val unsupported = session.load("video", preference = PlaybackPreference(allowSabr = false), includeMuxedFloor = false)
        assertTrue(unsupported.ok)
        assertEquals(3, requests)
        assertEquals("https://media.test/muxed", unsupported.value!!.playback.videoUrl)
        assertNull(unsupported.value!!.playback.sabrUrl)
    }

    @Test fun playbackSkipsTheSupplementalRequestButCatalogCanStillLoadIt() {
        val paths = mutableListOf<String>()
        val session = PlaybackSession(object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                paths += request.url
                return if (request.url.contains("reel_item_watch"))
                    HttpReply(200, """{"playerResponse":{"videoDetails":{"videoId":"video"},"streamingData":{"formats":[{"url":"https://floor","mimeType":"video/mp4","itag":18}]}}}""")
                else HttpReply(200, """{"videoDetails":{"videoId":"video"},"streamingData":{"adaptiveFormats":[{"url":"https://primary","mimeType":"video/mp4","itag":137},{"url":"https://audio","mimeType":"audio/mp4","itag":140}]}}""")
            }
        })
        val playback = session.load("video", includeMuxedFloor = false)
        assertTrue(playback.ok)
        assertEquals(1, paths.size)
        assertTrue(playback.value!!.answer!!.muxedFormats.isEmpty())
        session.load("video", includeMuxedFloor = false)
        assertEquals(1, paths.size)
        val catalog = session.load("video", includeMuxedFloor = true)
        assertTrue(catalog.ok)
        assertEquals(PlayerClient.ANDROID_VR, catalog.value!!.floorClient)
        assertEquals(1, paths.count { it.contains("reel_item_watch") })
        assertTrue(catalog.value!!.answer!!.muxedFormats.isNotEmpty())
    }

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

    @Test fun theWebPlayerRetainsTheSelectedBrandAndGoogleAccountIndex() {
        val requests = mutableListOf<HttpRequest>()
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                requests += request
                val authenticated = request.headers["Cookie"] == "SAPISID=fixture"
                return HttpReply(200, if (authenticated)
                    """{"streamingData":{"formats":[{"url":"https://media","mimeType":"video/mp4"}]}}"""
                    else """{"playabilityStatus":{"status":"LOGIN_REQUIRED"}}""")
            }
        }
        assertTrue(loadPlayer("video", RequestContext(accountId = "brand", onBehalfOfUser = "brand",
            webSession = WebSession("SAPISID=fixture", 2)), http).ok)
        assertEquals(1, requests.count { it.headers.containsKey("Authorization") })
        assertTrue(requests.single().body.contains("\"clientName\":\"WEB\""))
        assertEquals("2", requests.single().headers["X-Goog-AuthUser"])
        assertEquals("brand", requests.single().headers["X-Goog-PageId"])
        assertEquals("brand", jsonObject(requests.single().body)!!.path("context", "user")!!.text("onBehalfOfUser"))
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
