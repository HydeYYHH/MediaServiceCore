package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerResponseTest {
    @Test
    fun vodDirectUrl() {
        val answer = playerAnswer("""
            {"playabilityStatus":{"status":"OK"},
             "streamingData":{"adaptiveFormats":[{"url":"https://rr.googlevideo.com/videoplayback?id=v"}]}}
        """)
        assertEquals("OK", answer.status)
        assertTrue(answer.hasDirectUrl)
        assertFalse(answer.hasMuxed)
        assertFalse(answer.hasHls)
        assertFalse(answer.live)
        assertFalse(answer.transportFailed)
    }

    @Test
    fun hlsOnly() {
        val answer = playerAnswer("""
            {"playabilityStatus":{"status":"OK"},
             "streamingData":{"hlsManifestUrl":"https://manifest.googlevideo.com/api/manifest/hls"}}
        """)
        assertTrue(answer.hasHls)
        assertFalse(answer.hasDirectUrl)
        assertFalse(answer.hasMuxed)
    }

    @Test
    fun sabrOnly() {
        val answer = playerAnswer("""
            {"playabilityStatus":{"status":"OK"},
             "streamingData":{"serverAbrStreamingUrl":"https://sabr.googlevideo.com/videoplayback"}}
        """)
        assertEquals("OK", answer.status)
        assertFalse(answer.hasDirectUrl)
        assertFalse(answer.hasHls)
        assertFalse(answer.hasMuxed)
        assertEquals(ClientOutcome.SOFT, clientOutcome(
            answer.transportFailed, answer.status, answer.reason,
            answer.hasDirectUrl, answer.hasHls, answer.hasMuxed, answer.live
        ))
    }

    @Test
    fun ageCheckIgnoresObjectReason() {
        val answer = playerAnswer("""
            {"playabilityStatus":{"status":"AGE_CHECK_REQUIRED","reason":{"simpleText":"Sign in"}}}
        """)
        assertEquals("AGE_CHECK_REQUIRED", answer.status)
        assertNull(answer.reason)
    }

    @Test
    fun liveManifest() {
        val fromDetails = playerAnswer("""
            {"videoDetails":{"isLive":true},
             "playabilityStatus":{"status":"OK"},
             "streamingData":{"hlsManifestUrl":"https://manifest.googlevideo.com/api/manifest/hls",
                              "formats":[{"url":""}]}}
        """)
        assertTrue(fromDetails.live)
        assertTrue(fromDetails.hasHls)
        assertFalse(fromDetails.hasMuxed)

        val fromStreamability = playerAnswer("""
            {"playabilityStatus":{"status":"OK","liveStreamability":{}},
             "streamingData":{"formats":[{"url":"https://rr.googlevideo.com/videoplayback?id=live"}]}}
        """)
        assertTrue(fromStreamability.live)
        assertTrue(fromStreamability.hasMuxed)
        assertTrue(fromStreamability.hasDirectUrl)
    }

    @Test
    fun blankBody() {
        val answer = playerAnswer("  ")
        assertFalse(answer.transportFailed)
        assertNull(answer.status)
        assertNull(answer.reason)
        assertFalse(answer.hasDirectUrl)
        assertFalse(answer.hasHls)
        assertFalse(answer.hasMuxed)
        assertFalse(answer.live)
        assertEquals(ClientOutcome.SOFT, clientOutcome(
            answer.transportFailed, answer.status, answer.reason,
            answer.hasDirectUrl, answer.hasHls, answer.hasMuxed, answer.live
        ))
    }
}
