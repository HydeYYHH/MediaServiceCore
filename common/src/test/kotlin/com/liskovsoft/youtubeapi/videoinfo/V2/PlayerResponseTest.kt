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
        assertEquals(listOf("https://rr.googlevideo.com/videoplayback?id=v"), answer.adaptiveUrls)
        assertTrue(answer.muxedUrls.isEmpty())
        assertNull(answer.hlsUrl)
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
        assertEquals("https://manifest.googlevideo.com/api/manifest/hls", answer.hlsUrl)
        assertTrue(answer.muxedUrls.isEmpty())
        assertTrue(answer.adaptiveUrls.isEmpty())
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
        assertNull(answer.hlsUrl)
        assertTrue(answer.muxedUrls.isEmpty())
        assertTrue(answer.adaptiveUrls.isEmpty())
    }

    @Test
    fun keepsPlainUrlsInOrder() {
        val answer = playerAnswer("""
            {"playabilityStatus":{"status":"OK"},
             "streamingData":{
               "formats":[
                 {"url":""},
                 {"signatureCipher":"s=1&url=https://hidden.example/x"},
                 {"url":"https://muxed.example/a"},
                 {"url":"https://muxed.example/b"}],
               "adaptiveFormats":[
                 {"url":"https://adaptive.example/1"},
                 {"url":""},
                 {"url":"https://adaptive.example/2"}]}}
        """)
        assertEquals(listOf("https://muxed.example/a", "https://muxed.example/b"), answer.muxedUrls)
        assertEquals(listOf("https://adaptive.example/1", "https://adaptive.example/2"), answer.adaptiveUrls)
        assertNull(answer.hlsUrl)
        assertTrue(answer.hasMuxed)
        assertTrue(answer.hasDirectUrl)
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
        assertEquals("https://manifest.googlevideo.com/api/manifest/hls", fromDetails.hlsUrl)
        assertTrue(fromDetails.muxedUrls.isEmpty())

        val fromStreamability = playerAnswer("""
            {"playabilityStatus":{"status":"OK","liveStreamability":{}},
             "streamingData":{"formats":[{"url":"https://rr.googlevideo.com/videoplayback?id=live"}]}}
        """)
        assertTrue(fromStreamability.live)
        assertTrue(fromStreamability.hasMuxed)
        assertTrue(fromStreamability.hasDirectUrl)
        assertEquals(listOf("https://rr.googlevideo.com/videoplayback?id=live"), fromStreamability.muxedUrls)
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
        assertNull(answer.hlsUrl)
        assertTrue(answer.muxedUrls.isEmpty())
        assertTrue(answer.adaptiveUrls.isEmpty())
        assertEquals(ClientOutcome.SOFT, clientOutcome(
            answer.transportFailed, answer.status, answer.reason,
            answer.hasDirectUrl, answer.hasHls, answer.hasMuxed, answer.live
        ))
    }
}
