package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class PlayerScriptReadTest {
    @Test fun reloadsTheScriptAndTokensOnAnExplicitRefresh() {
        var version = 1
        var tokenCalls = 0
        val seen = ArrayList<Int>()
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply = when (request.method) {
                "GET" -> if (request.url.contains("/embed/")) HttpReply(200, "{\"jsUrl\":\"/s/player/$version/base.js\"}")
                    else HttpReply(200, "signatureTimestamp:$version")
                else -> {
                    seen.add(jsonObject(request.body)!!.path("playbackContext", "contentPlaybackContext")!!.int("signatureTimestamp")!!)
                    HttpReply(200, "{\"streamingData\":{\"formats\":[{\"url\":\"https://media\"}]}}")
                }
            }
        }
        val tokens = object : PlayerTokenProvider {
            override fun token(request: PlayerTokenRequest): PlayerToken { tokenCalls++; return PlayerToken("T$tokenCalls", Long.MAX_VALUE) }
        }
        val session = PlaybackSession(http, tokens = tokens, scripts = JvmPlayerScripts(http))
        assertTrue(session.load("v").ok)
        version = 2
        session.load("v")
        assertEquals(listOf(1), seen)
        assertTrue(session.load("v", fresh = true).ok)
        assertEquals(listOf(1, 2), seen)
        assertEquals(6, tokenCalls)
    }

    @Test fun reportsScriptReadFailures() {
        val http = object : HttpClient { override fun execute(request: HttpRequest) = HttpReply(200, "no script") }
        assertEquals("missing_player_script", JvmPlayerScripts(http).script("v", RequestContext(), false).error!!.code)
    }
}
