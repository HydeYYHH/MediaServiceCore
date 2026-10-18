package com.liskovsoft.youtubeapi.videoinfo.V2
import org.junit.Assert.*
import org.junit.Test
class CaptionOriginTest {
    @Test fun prefersTheOriginalTranscriptEvenWhenADubIsListedFirst() {
        val dub = PlayerCaption("https://x?lang=de&variant=timing-optimized", "de", null, null, true, true)
        val original = PlayerCaption("https://x?lang=en", "en", null, null, true, true)
        val result = translateCaptions(listOf(dub, original), listOf(CaptionLanguage("zh", null)), listOf(PlayerAudio("de.10", "de", null, false, true)))
        assertEquals("en", urlParameter(result.last().url!!, "lang"))
        assertNull(urlParameter(result.last().url!!, "variant"))
        assertEquals("en", urlParameter(translateCaptions(listOf(dub, original), listOf(CaptionLanguage("zh", null))).last().url!!, "lang"))
    }
    @Test fun retainsTheUpstreamFallbackWhenOnlyDubbedTranscriptsExist() {
        val dub = PlayerCaption("https://x?variant=timing-optimized", "de", null, null, true, true)
        assertEquals(2, translateCaptions(listOf(dub), listOf(CaptionLanguage("en", null))).size)
        assertTrue(translateCaptions(emptyList(), emptyList()).isEmpty())
    }
}
