package com.liskovsoft.youtubeapi.videoinfo.V2
import org.junit.Assert.*
import org.junit.Test
class LibraryBrowseTest {
    @Test fun anOpenedInitialBrowseSuppliesTheDirectoryWithoutRepeatingTheRequest() {
        var calls = 0
        val http = object : HttpClient { override fun execute(request: HttpRequest): HttpReply {
            calls++
            return HttpReply(200, """{"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{
                "title":"Ada","endpoint":{"browseEndpoint":{"browseId":"UCada","params":"channel-filter"}},
                "presentationStyle":{"style":"NEW_CONTENT"}}}]}},"videoRenderer":{"videoId":"abcdefghijk"}}""")
        } }
        val context = RequestContext("a", "token", generation = 7)
        val opened = loadSubscriptionsBrowse(context, http).value!!
        assertEquals("abcdefghijk", opened.feed.value!!.items.single().id)
        val directory = loadSubscribedChannelsPage(context, opened, http).value!!
        assertEquals("channel-filter", directory.channels.single().params)
        assertTrue(directory.channels.single().hasNewContent)
        assertEquals(1, calls)
        loadSubscribedChannelsPage(context.copy(generation = 8), opened, http)
        assertEquals(2, calls)
        loadSubscribedChannelsPage(context.copy(accessToken = "rotated"), opened, http)
        assertEquals(3, calls)
    }

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

    @Test fun readsFormattedGuideTitlesWithoutAFallbackRequest() {
        var calls = 0
        val http = object : HttpClient { override fun execute(request: HttpRequest): HttpReply {
            calls++
            return HttpReply(200, """{"guideEntryRenderer":{"formattedTitle":{"simpleText":"Ada"},"navigationEndpoint":{"browseEndpoint":{"browseId":"UCada"}}}}""")
        }}
        assertEquals("Ada", loadSubscribedChannels(RequestContext("a", "t"), http).value!!.single().title)
        assertEquals(2, calls) // TV subscriptions tabs, then the guide fallback.
    }

    @Test fun anAccountOnlyGuideFallsBackToTheSubscribedChannelsBrowse() {
        var calls = 0
        val http = object : HttpClient { override fun execute(request: HttpRequest): HttpReply {
            calls++
            assertEquals("Bearer token", request.headers["Authorization"])
            if (jsonObject(request.body)?.text("browseId") == "FEsubscriptions") return HttpReply(200, "{}")
            return if (request.url.contains("guide")) HttpReply(200, """{"guideAccountEntryRenderer":{}}""")
            else {
                assertEquals("FEchannels", jsonObject(request.body)!!.text("browseId"))
                HttpReply(200, """{"channelRenderer":{"channelId":"UCada","title":{"simpleText":"Ada"},"thumbnail":{"thumbnails":[{"url":"https://example.com/avatar"}]}}}""")
            }
        }}
        val item = loadSubscribedChannels(RequestContext("a", "token"), http).value!!.single()
        assertEquals("UCada", item.id)
        assertEquals("Ada", item.title)
        assertEquals("https://example.com/avatar", item.thumbnail)
        assertEquals(3, calls)
    }

    @Test fun aRejectedFallbackRemainsAnError() {
        val http = object : HttpClient { override fun execute(request: HttpRequest) =
            if (request.url.contains("guide")) HttpReply(200, "{}") else HttpReply(403, "{}") }
        assertFalse(loadSubscribedChannels(RequestContext("a", "token"), http).ok)
    }
}
