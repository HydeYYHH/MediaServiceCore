package com.liskovsoft.youtubeapi.videoinfo.V2
import org.junit.Assert.*
import org.junit.Test
class PlaybackPreferenceTest {
    private fun answer() = playerAnswer("""{"streamingData":{"formats":[{"url":"https://mux","height":360}],"adaptiveFormats":[{"url":"https://video","height":1080,"mimeType":"video/mp4"},{"url":"https://original","mimeType":"audio/mp4","audioTrack":{"id":"en.4"}},{"url":"https://dub","mimeType":"audio/mp4","bitrate":256000,"audioTrack":{"id":"de.10","audioIsDefault":true}}]}}""")
    @Test fun choosesHighQualityWithOriginalAudioThenHonorsPreferences() {
        val normal = selectPlayback(answer()); assertEquals("https://video", normal.videoUrl); assertEquals("https://original", normal.audioUrl)
        assertEquals("https://mux", selectPlayback(answer(), PlaybackPreference(maxHeight = 480)).videoUrl)
        assertEquals("https://dub", selectPlayback(answer(), PlaybackPreference(audioId = "de.10", audioOnly = true)).audioUrl)
        assertNull(selectPlayback(answer(), PlaybackPreference(audioOnly = true)).videoUrl)
    }
    @Test fun keepsManifestOnlySources() {
        assertEquals("https://dash", selectPlayback(playerAnswer("""{"streamingData":{"dashManifestUrl":"https://dash"}}""")).dashUrl)
        assertEquals(Playback(null, null, null), selectPlayback(null))
    }
}
