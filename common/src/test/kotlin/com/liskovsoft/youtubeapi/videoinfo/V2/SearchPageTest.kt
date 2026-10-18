package com.liskovsoft.youtubeapi.videoinfo.V2
import org.junit.Assert.*
import org.junit.Test
class SearchPageTest {
    @Test fun keepsMixedResultsAndTheContinuation() {
        val page = searchPage("""{"contents":[{"videoRenderer":{"videoId":"v","title":{"runs":[{"text":"Video"}]}}},{"channelRenderer":{"channelId":"UC1","title":{"simpleText":"Channel"}}},{"playlistRenderer":{"playlistId":"PL1","title":{"simpleText":"List"}}},{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"next"}}}}]}""")
        assertEquals(listOf("video","channel","playlist"), page.items.map { it.kind }); assertEquals("next", page.nextKey)
        val q = searchQuery(null, continuation = "a\"b")
        assertEquals("a\"b", jsonObject(q.body)!!.text("continuation")); assertFalse(q.body.contains("\"query\""))
    }
    @Test fun distinguishesAnEmptyPageFromFailure() {
        val empty = object : HttpClient { override fun execute(request: HttpRequest) = HttpReply(200, "{}") }
        assertTrue(loadSearch("q", http = empty).value!!.items.isEmpty())
        assertEquals(RequestFailure.INVALID, loadSearch("", http = empty).error!!.kind)
    }
}
