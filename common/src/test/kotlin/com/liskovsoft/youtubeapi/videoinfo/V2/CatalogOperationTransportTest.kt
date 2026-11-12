package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class CatalogOperationTransportTest {
    private val id = "UC_x5XG1OV2P6uZZ5FSM9Ttw"
    private class Transport(private val body: String) : HttpClient {
        val requests = mutableListOf<HttpRequest>()
        override fun execute(request: HttpRequest): HttpReply {
            requests += request
            return HttpReply(200, body)
        }
    }
    private val unused = object : HttpClient {
        override fun execute(request: HttpRequest): HttpReply = error("An operation must use its current transport")
    }
    private fun page(video: String, next: String? = null, continuation: Boolean = false): String {
        val cards = """{"videoRenderer":{"videoId":"$video","title":{"simpleText":"Video"}}}${next?.let {
            "," + """{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"$it"}}}}"""
        }.orEmpty()}"""
        return if (continuation) """{"onResponseReceivedActions":[{"appendContinuationItemsAction":{"continuationItems":[$cards]}}]}"""
            else """{"contents":{"richGridRenderer":{"contents":[$cards]}}}"""
    }
    private fun landing() = """{"metadata":{"channelMetadataRenderer":{"externalId":"$id","title":"Lessons"}},
        "contents":{"twoColumnBrowseResultsRenderer":{"tabs":[
        {"tabRenderer":{"tabIdentifier":"home","selected":true,"content":{"richGridRenderer":{"contents":[]}}}},
        {"tabRenderer":{"tabIdentifier":"videos","endpoint":{"browseEndpoint":{"browseId":"$id","params":"server-videos"}}}}
    ]}}}"""

    @Test fun aCatalogContinuationUsesTheCurrentTransportAndStillFencesGeneration() {
        val account = AccountSession()
        val catalog = CatalogSession(account, unused)
        val firstHttp = Transport(page("first", "owned-next"))
        val first = catalog.home(HomeSection.HOME, firstHttp).value!!
        val nextHttp = Transport(page("last", continuation = true))
        assertEquals("last", catalog.next(first.next!!, nextHttp).value!!.items.single().item.id)
        assertEquals(1, firstHttp.requests.size)
        assertEquals(1, nextHttp.requests.size)
        assertEquals("owned-next", jsonObject(nextHttp.requests.single().body)!!.text("continuation"))
        account.invalidate()
        assertEquals("session_changed", catalog.next(first.next!!, nextHttp).error?.code)
        assertEquals(1, nextHttp.requests.size)
    }

    @Test fun anOpenedChannelAndItsPreparedPageReuseTheLandingAndKeepFreshTransports() {
        val account = AccountSession()
        val channels = ChannelSession(account, unused)
        val openingHttp = Transport(landing())
        val handle = channels.open(id, openingHttp).value!!
        val tabHttp = Transport(page("first", "tab-next"))
        assertTrue(handle.openTab(ChannelTabKind.VIDEOS, tabHttp).ok)
        val catalog = CatalogSession(account, unused)
        val prepared = catalog.channel(handle, ChannelTabKind.VIDEOS, unused).value!!
        assertEquals("first", prepared.items.single().item.id)
        assertEquals(1, openingHttp.requests.size)
        assertEquals(1, tabHttp.requests.size)
        val nextHttp = Transport(page("last", continuation = true))
        assertEquals("last", catalog.next(prepared.next!!, nextHttp).value!!.items.single().item.id)
        assertEquals(1, nextHttp.requests.size)
        assertEquals("foreign_channel_handle", CatalogSession(AccountSession(), unused)
            .channel(handle, ChannelTabKind.VIDEOS, unused).error?.code)
    }

    @Test fun channelSearchKeepsItsIssuerAndDoesNotRetainAnEarlierReadTransport() {
        val account = AccountSession()
        val channels = ChannelSession(account, unused)
        val handle = channels.open(id, Transport(landing())).value!!
        val searchHttp = Transport(page("found", "search-next"))
        val first = handle.search("lesson", searchHttp).value!!
        assertEquals("lesson", jsonObject(searchHttp.requests.single().body)!!.text("query"))
        val nextHttp = Transport(page("later", continuation = true))
        assertEquals("later", handle.nextSearch(first.next!!, nextHttp).value!!.page.items.single().id)
        assertEquals("search-next", jsonObject(nextHttp.requests.single().body)!!.text("continuation"))
        val other = channels.open(id, Transport(landing())).value!!
        assertEquals("foreign_channel_search_cursor", other.nextSearch(first.next!!, unused).error?.code)
        account.invalidate()
        assertEquals("session_changed", handle.nextSearch(first.next!!, unused).error?.code)
    }
}
