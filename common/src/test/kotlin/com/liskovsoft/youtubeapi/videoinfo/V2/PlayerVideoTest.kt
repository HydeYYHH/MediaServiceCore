package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class PlayerVideoTest {
    @Test fun selectsCodecRangeAndFrameRateWithoutGuessingManifestCapabilities() {
        val body = """{"streamingData":{"hlsManifestUrl":"https://hls","adaptiveFormats":[{"url":"https://hdr","mimeType":"video/mp4; codecs=\"av01.0.08M.10\"","height":1080,"fps":60,"colorInfo":{"transferCharacteristics":"COLOR_TRANSFER_CHARACTERISTICS_SMPTE_ST_2084"}},{"url":"https://sdr","mimeType":"video/mp4; codecs=\"avc1.640028\"","height":720,"fps":30,"colorInfo":{"transferCharacteristics":"COLOR_TRANSFER_CHARACTERISTICS_BT_709"}},{"url":"https://audio","mimeType":"audio/webm; codecs=\"opus\""}]}}"""
        val answer = playerAnswer(body)
        assertEquals(VideoRange.HDR, answer.adaptiveFormats[0].dynamicRange)
        val preference = PlaybackPreference(videoCodecs = setOf("avc1"), audioCodecs = setOf("opus"), maxFps = 30, dynamicRange = VideoRange.SDR)
        assertEquals("https://sdr", selectPlayback(answer, preference).videoUrl)
        assertEquals("https://audio", selectPlayback(answer, preference).audioUrl)
        assertFalse(selectPlayback(answer, preference.copy(videoCodecs = setOf("vp9"))).hasSource())
        assertEquals(listOf("avc1.640028", "mp4a.40.2"), formatCodecs("video/mp4; codecs=\"avc1.640028, mp4a.40.2\""))
    }
}
