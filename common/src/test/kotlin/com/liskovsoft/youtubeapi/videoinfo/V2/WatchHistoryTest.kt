package com.liskovsoft.youtubeapi.videoinfo.V2
import org.junit.Assert.*
import org.junit.Test
class WatchHistoryTest {
    @Test fun createsBeforeUpdatingAndPreservesTheAnswersParameters() {
        val requests = ArrayList<HttpRequest>()
        val http = object : HttpClient { override fun execute(request: HttpRequest): HttpReply { requests.add(request); return HttpReply(200, "") } }
        val history = WatchHistory(http, "cpn"); val c = RequestContext("a","t")
        val track = PlayerTracking("https://www.youtube.com/api/stats/playback?ei=event&vm=keep", "https://www.youtube.com/api/stats/watchtime?of=keep")
        assertTrue(history.update("v", 1000, 10000, track, c).ok)
        assertTrue(history.update("v", 2000, 10000, track, c).ok)
        assertEquals(3, requests.size); assertEquals("event", urlParameter(requests.first().url,"ei"))
        assertEquals("1.0", urlParameter(requests.last().url,"st")); assertEquals("2.0", urlParameter(requests.last().url,"et"))
        assertEquals("cpn", urlParameter(requests.last().url,"cpn"))
        assertFalse(history.update("v", 1, 10, track.copy(playbackUrl = "https://evil/api/stats/playback"), c.copy(accountId="b")).ok)
        assertEquals(3, requests.size)
    }
    @Test fun historyControlsRequireAuthentication() {
        assertEquals(RequestFailure.AUTH, clearWatchHistory(RequestContext()).error!!.kind)
    }
}
