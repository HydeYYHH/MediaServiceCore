package com.liskovsoft.youtubeapi.videoinfo.V2
import org.junit.Assert.*
import org.junit.Test
class PlaybackCacheTest {
    @Test fun expiresAndEvictsLeastRecentlyUsedEntries() {
        var now = 0L
        val cache = MemoryPlaybackCache(2, 10, {now}, 100)
        cache.saveScript("a", "a"); cache.saveSignature("a", "s", "solved")
        assertEquals("a", cache.script("a")); cache.saveScript("b", "b")
        assertNull(cache.signature("a", "s"))
        now = 10; assertNull(cache.script("a"))
        cache.saveScript("big", "x".repeat(101)); assertNull(cache.script("big"))
    }
    @Test fun rejectsWritesStartedBeforeInvalidation() {
        val cache = TimedCache<String, String>(2) {0}
        val old = cache.generation(); cache.clear(); cache.put("a", "stale", 100, old)
        assertNull(cache.get("a"))
    }
    @Test fun cachesPlaybackOnlyUntilTheShorterServerExpiry() {
        var now = 0L; var calls = 0
        val http = object : HttpClient { override fun execute(request: HttpRequest): HttpReply {
            calls++; return HttpReply(200, """{"streamingData":{"expiresInSeconds":"1","formats":[{"url":"https://media"}]}}""")
        }}
        val session = PlaybackSession(http, now = {now})
        session.load("v"); session.load("v"); assertEquals(1, calls)
        now = 1000; session.load("v"); assertEquals(2, calls)
        session.invalidate(); session.load("v"); assertEquals(3, calls)
    }
}
