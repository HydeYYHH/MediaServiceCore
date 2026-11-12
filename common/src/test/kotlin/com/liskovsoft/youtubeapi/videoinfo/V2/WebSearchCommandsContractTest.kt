package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class WebSearchCommandsContractTest {
    private fun http(body: String) = object : HttpClient { override fun execute(request: HttpRequest) = HttpReply(200, body) }

    @Test fun observedSearchCommandEnvelopePreservesItemsGroupsImagesAndTheNextContinuation() {
        // WEB search continuations use a top-level command array.
        val body = """{"responseContext":{},"estimatedResults":"10","onResponseReceivedCommands":[
            {"appendContinuationItemsAction":{"continuationItems":[{"itemSectionRenderer":{"contents":[
              {"videoRenderer":{"videoId":"video","channelThumbnailSupportedRenderers":{"channelThumbnailWithLinkRenderer":{
                "thumbnail":{"thumbnails":[{"url":"https://example.test/author","width":68,"height":68}]}}}}}]}},
              {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"next"}}}}]}}
        ]}"""
        val page = loadSearch(null, continuation = "issued", http = http(body)).value!!
        assertEquals("video", page.items.single().id)
        assertEquals(68, page.items.single().authorArtwork!!.best!!.width)
        assertEquals(page.items, page.groups.single().items)
        assertEquals("next", page.nextKey)
        assertEquals("unknown_search_page", loadSearch("initial", http = http(body)).error!!.code)
    }

    @Test fun emptyKnownCommandListIsAnEndButUnknownAndMalformedCommandsStillFail() {
        val empty = """{"onResponseReceivedCommands":[{"appendContinuationItemsAction":{"continuationItems":[]}}]}"""
        assertTrue(loadSearch(null, continuation = "issued", http = http(empty)).value!!.items.isEmpty())
        for (body in listOf(
            """{"onResponseReceivedCommands":[]}""",
            """{"onResponseReceivedCommands":{"appendContinuationItemsAction":{"continuationItems":[]}}}""",
            """{"onResponseReceivedCommands":[{"appendContinuationItemsAction":{"continuationItems":{}}}]}""",
            """{"onResponseReceivedCommands":[{"appendContinuationItemsAction":{"continuationItems":[{"futureCardViewModel":{}}]}}]}""",
            """{"nested":{"onResponseReceivedCommands":[{"appendContinuationItemsAction":{"continuationItems":[]}}]}}"""
        )) assertEquals(body, "unknown_search_page", loadSearch(null, continuation = "issued", http = http(body)).error!!.code)
        val initial = """{"contents":{"twoColumnSearchResultsRenderer":{"primaryContents":{"sectionListRenderer":{"contents":[
            {"itemSectionRenderer":{"contents":[{"videoRenderer":{"videoId":"initial"}}]}}]}}}},
            "onResponseReceivedCommands":[{"loggingCommand":{}}]}"""
        assertEquals("initial", loadSearch("initial", http = http(initial)).value!!.items.single().id)
    }
}
