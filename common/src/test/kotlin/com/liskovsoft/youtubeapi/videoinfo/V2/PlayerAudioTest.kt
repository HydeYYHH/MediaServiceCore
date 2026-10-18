package com.liskovsoft.youtubeapi.videoinfo.V2
import org.junit.Assert.*
import org.junit.Test
class PlayerAudioTest {
    @Test fun readsTheDefaultAndDubbedTracksOnce() {
        val body = """{"streamingData":{"adaptiveFormats":[{"url":"https://a","audioTrack":{"id":"de-DE.10","displayName":"Deutsch","audioIsDefault":true,"isAutoDubbed":true}},{"url":"https://b","audioTrack":{"id":"de-DE.10","displayName":"Deutsch","audioIsDefault":true,"isAutoDubbed":true}},{"url":"https://c"}]}}"""
        val audio = playerAudioTracks(body).single()
        assertEquals("de-DE", audio.language); assertTrue(audio.isDefault); assertTrue(audio.autoDubbed)
        assertEquals(audio, playerAnswer(body).adaptiveFormats.first().audioTrack)
        assertTrue(playerAudioTracks("{}").isEmpty())
    }
}
