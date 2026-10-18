package com.liskovsoft.youtubeapi.videoinfo.V2
import org.junit.Assert.*
import org.junit.Test
class LibraryBrowseTest {
    @Test fun requiresAnAccountAndKeepsPrivateEmptyPagesDistinctFromErrors() {
        var calls = 0
        val http = object : HttpClient { override fun execute(request: HttpRequest): HttpReply {
            calls++; assertEquals("Bearer token", request.headers["Authorization"])
            assertEquals("FEsubscriptions", jsonObject(request.body)!!.text("browseId"))
            return HttpReply(200, "{}")
        }}
        assertFalse(loadLibrary(LibrarySection.SUBSCRIPTIONS, RequestContext(), http = http).ok); assertEquals(0, calls)
        assertTrue(loadLibrary(LibrarySection.SUBSCRIPTIONS, RequestContext("a","token"), http = http).ok)
    }
    @Test fun extractsSubscribedChannelsFromTheGuide() {
        val http = object : HttpClient { override fun execute(request: HttpRequest) = HttpReply(200, """{"guideEntryRenderer":{"title":{"simpleText":"Ada"},"navigationEndpoint":{"browseEndpoint":{"browseId":"UCada"}}}}""") }
        assertEquals("UCada", loadSubscribedChannels(RequestContext("a","t"), http).value!!.single().id)
    }
}
