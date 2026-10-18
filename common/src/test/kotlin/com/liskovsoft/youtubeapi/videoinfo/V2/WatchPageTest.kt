package com.liskovsoft.youtubeapi.videoinfo.V2
import org.junit.Assert.*
import org.junit.Test
class WatchPageTest {
    @Test fun keepsDetailsChaptersRecommendationsAndChat() {
        val p = watchPage("""{"videoPrimaryInfoRenderer":{"title":{"simpleText":"Title"}},"videoSecondaryInfoRenderer":{"description":{"runs":[{"text":"About"}]},"owner":{"videoOwnerRenderer":{"title":{"simpleText":"Ada"},"navigationEndpoint":{"browseEndpoint":{"browseId":"UCada"}}}}},"chapterRenderer":{"title":{"simpleText":"Chapter"},"timeRangeStartMillis":5000},"compactVideoRenderer":{"videoId":"related"},"liveChatRenderer":{"continuations":[{"reloadContinuationData":{"continuation":"chat"}}]}}""", "v")
        assertEquals("About", p.description); assertEquals("UCada", p.channelId)
        assertEquals(5000L, p.chapters.single().startMs); assertEquals("related", p.related.single().id); assertEquals("chat", p.chatKey)
    }
    @Test fun mapsStoryboardTimesToSheetsAndCells() {
        val board = readStoryboard("https://x/L\$L/\$N.jpg?sig=keep|80#45#20#2#2#1000#M\$M#signature")!!
        val tile = board.tile(6500, 20000)!!
        assertTrue(tile.url.contains("/L0/M1.jpg")); assertEquals(0, tile.column); assertEquals(1, tile.row)
        assertNull(readStoryboard("bad")); assertNotNull(board.tile(Long.MAX_VALUE, 20000))
    }
}
