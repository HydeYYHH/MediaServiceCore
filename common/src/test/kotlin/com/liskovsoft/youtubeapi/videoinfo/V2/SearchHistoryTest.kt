package com.liskovsoft.youtubeapi.videoinfo.V2
import org.junit.Assert.*
import org.junit.Test
class SearchHistoryTest {
    @Test fun scopesRecentEntriesAndOnlyClearsAfterRemoteSuccess() {
        val history = SearchHistory(capacity=2); val a = RequestContext("a","t"); val b = RequestContext("b","u")
        history.record("one", a); history.record("two", a); history.record("one", a); history.record("three", b)
        assertEquals(listOf("one","two"), history.read(a)); assertEquals(listOf("three"), history.read(b))
        history.remove("one", a); assertEquals(listOf("two"), history.read(a))
        val bad = object : HttpClient { override fun execute(request: HttpRequest) = HttpReply(200, "{\"success\":false}") }
        assertFalse(history.clearRemote(a,bad).ok); assertEquals(listOf("two"), history.read(a))
        val good = object : HttpClient { override fun execute(request: HttpRequest) = HttpReply(200, "{}") }
        assertTrue(history.clearRemote(a,good).ok); assertTrue(history.read(a).isEmpty()); assertFalse(history.read(b).isEmpty())
    }
}
