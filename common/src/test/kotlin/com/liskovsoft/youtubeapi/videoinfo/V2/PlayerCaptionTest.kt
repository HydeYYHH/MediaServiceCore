package com.liskovsoft.youtubeapi.videoinfo.V2
import org.junit.Assert.*
import org.junit.Test
class PlayerCaptionTest {
    @Test fun translatesAndDeduplicatesWithoutChangingTheSignedQuery() {
        val body = """{"captions":{"playerCaptionsTracklistRenderer":{"captionTracks":[{"baseUrl":"https://x?sig=a%2Bb&lang=en#end","languageCode":"en","name":{"simpleText":"English"},"kind":"asr","isTranslatable":true}],"translationLanguages":[{"languageCode":"en"},{"languageCode":"zh-CN"},{"languageCode":"zh-cn"}]}}}"""
        val tracks = playerCaptions(body)
        val result = translateCaptions(tracks, captionLanguages(body))
        assertEquals(2, result.size); assertTrue(result.last().translated)
        assertEquals("https://x?sig=a%2Bb&lang=en&tlang=zh-CN#end", result.last().url)
        assertEquals(1, tracks.size)
    }
    @Test fun handlesEmptyAndUntranslatableTracks() {
        assertEquals(emptyList<PlayerCaption>(), translateCaptions(emptyList(), listOf(CaptionLanguage("en", null))))
        val tracks = listOf(PlayerCaption(null, "en", null, null, true, false))
        assertEquals(tracks, translateCaptions(tracks, listOf(CaptionLanguage("zh", null))))
    }
}
