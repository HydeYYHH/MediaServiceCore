package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class PlaybackExpiryTest {
    @Test fun expiresThePlaybackWhenAnAttachedTokenExpires() {
        var time = 0L
        var calls = 0
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                calls++
                return HttpReply(200, "{\"streamingData\":{\"expiresInSeconds\":60,\"formats\":[{\"url\":\"https://media\"}]}}")
            }
        }
        val tokens = object : PlayerTokenProvider {
            override fun token(request: PlayerTokenRequest) = PlayerToken("T$time", time + 1000)
        }
        val session = PlaybackSession(http, tokens = tokens, now = { time })
        val first = session.load("v").value!!
        assertEquals(1000L, first.answer!!.expiresAtMs)
        time = 500
        assertEquals(first.source, session.load("v").value!!.source)
        assertEquals(1, calls)
        time = 1000
        val refreshed = session.load("v").value!!
        assertEquals(2, calls)
        assertEquals("T1000", urlParameter(refreshed.playback.videoUrl!!, "pot"))
    }
}
