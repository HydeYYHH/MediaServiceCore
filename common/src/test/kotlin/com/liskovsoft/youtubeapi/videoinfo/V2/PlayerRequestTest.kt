package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class PlayerRequestTest {
    @Test
    fun visionOsQuery() {
        val query = playerQuery(PlayerClient.VISIONOS, "dQw4w9WgXcQ", null)
        assertEquals("https://www.youtube.com/youtubei/v1/player?prettyPrint=false", query.url)
        assertEquals("101", query.headers["X-Youtube-Client-Name"])
        assertEquals("1.02", query.headers["X-Youtube-Client-Version"])
        assertTrue(query.headers["User-Agent"]!!.contains("Version/26.0"))
        assertFalse(query.headers.containsKey("X-Goog-Visitor-Id"))
        assertTrue(query.body.contains("\"clientName\":\"VISIONOS\""))
        assertTrue(query.body.contains("\"videoId\":\"dQw4w9WgXcQ\""))
        assertTrue(query.body.contains("\"hl\":\"en\""))
        assertTrue(query.body.contains("\"gl\":\"US\""))
        assertTrue(query.body.contains("\"contentCheckOk\":true"))
        assertTrue(query.body.contains("\"racyCheckOk\":true"))
    }

    @Test
    fun webEmbedAndSafari() {
        val embed = playerQuery(PlayerClient.WEB_EMBED, "abcdefghijk", "visitor-1")
        assertEquals("56", embed.headers["X-Youtube-Client-Name"])
        assertEquals("2.20260908.01.00", embed.headers["X-Youtube-Client-Version"])
        assertTrue(embed.headers["User-Agent"]!!.contains("Version/15.5 Safari"))
        assertEquals("visitor-1", embed.headers["X-Goog-Visitor-Id"])
        assertTrue(embed.body.contains("\"clientName\":\"WEB_EMBEDDED_PLAYER\""))

        val safari = playerQuery(PlayerClient.WEB_SAFARI, "abcdefghijk", "")
        assertEquals("1", safari.headers["X-Youtube-Client-Name"])
        assertEquals("2.20260907.06.00", safari.headers["X-Youtube-Client-Version"])
        assertTrue(safari.body.contains("\"clientName\":\"WEB\""))
        assertFalse(safari.body.contains("WEB_SAFARI"))
        assertFalse(safari.headers.containsKey("X-Goog-Visitor-Id"))
        assertTrue(safari.headers["User-Agent"]!!.contains("Version/15.5 Safari"))
    }

    @Test
    fun tvAndVrAgents() {
        val tv = playerQuery(PlayerClient.TV, "abcdefghijk", null)
        assertEquals("7", tv.headers["X-Youtube-Client-Name"])
        assertTrue(tv.headers["User-Agent"]!!.contains("Cobalt/25"))
        assertTrue(tv.body.contains("\"clientName\":\"TVHTML5\""))

        val vr = playerQuery(PlayerClient.ANDROID_VR, "abcdefghijk", null)
        assertEquals("28", vr.headers["X-Youtube-Client-Name"])
        assertEquals("1.65.10", vr.headers["X-Youtube-Client-Version"])
        assertTrue(vr.body.contains("\"clientName\":\"ANDROID_VR\""))
    }

    @Test
    fun readsOkBody() {
        val query = playerQuery(PlayerClient.WEB, "dQw4w9WgXcQ", null)
        val answer = fetchPlayer(query) {
            Pair(200, """
                {"playabilityStatus":{"status":"OK"},
                 "streamingData":{"adaptiveFormats":[{"url":"https://rr.googlevideo.com/videoplayback?id=v"}]}}
            """)
        }
        assertEquals("OK", answer.status)
        assertTrue(answer.hasDirectUrl)
        assertFalse(answer.transportFailed)
    }

    @Test
    fun postFailureIsTransport() {
        val query = playerQuery(PlayerClient.WEB, "dQw4w9WgXcQ", null)
        val thrown = fetchPlayer(query) { throw IOException("down") }
        assertTrue(thrown.transportFailed)
        assertNull(thrown.status)

        val denied = fetchPlayer(query) { Pair(403, "") }
        assertTrue(denied.transportFailed)
        assertFalse(denied.hasDirectUrl)
    }

    @Test
    fun badVideoIdSkipsPost() {
        var called = false
        val answer = fetchPlayer(playerQuery(PlayerClient.WEB, "bad id", null)) {
            called = true
            Pair(200, "{}")
        }
        assertFalse(called)
        assertTrue(answer.transportFailed)
    }

    @Test
    fun readsSignatureTimestamp() {
        assertEquals(19395, signatureTimestamp("player.signatureTimestamp:19395;"))
        assertEquals(20522, signatureTimestamp("signatureTimestamp=20522,"))
        assertNull(signatureTimestamp("signatureTimestamp:"))
        assertNull(signatureTimestamp("no marker"))
    }

    @Test
    fun signatureTimestampInBody() {
        val withTimestamp = playerQuery(PlayerClient.WEB, "dQw4w9WgXcQ", null, 19395)
        assertTrue(withTimestamp.body.contains("\"playbackContext\":{\"contentPlaybackContext\":{\"signatureTimestamp\":19395}}"))
        assertTrue(withTimestamp.body.indexOf("playbackContext") < withTimestamp.body.indexOf("\"videoId\""))
        val without = playerQuery(PlayerClient.WEB, "dQw4w9WgXcQ", null)
        assertFalse(without.body.contains("playbackContext"))
    }
}
