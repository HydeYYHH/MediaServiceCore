package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

/** Legacy page end contracts remain operation-specific; network comments require an owned collection. */
class MetadataContinuationEndTest {
    private class Wire(private val body: String) : HttpClient {
        val requests = ArrayList<HttpRequest>()
        override fun execute(request: HttpRequest): HttpReply {
            requests += request
            return HttpReply(200, body)
        }
    }
    private fun continued(http: HttpClient): List<Pair<String, ReadResult<*>>> = listOf(
        "browse" to loadBrowse(null, continuation = "issued-cursor", http = http),
        "search" to loadSearch(null, continuation = "issued-cursor", http = http),
        "channel" to loadChannel("UCfixture", continuation = "issued-cursor", http = http),
        "playlist" to loadPlaylist(null, RequestContext(), "issued-cursor", http),
        "watch" to loadWatch(null, continuation = "issued-cursor", http = http),
        "comment" to loadComments("abcdefghijk", RequestContext(), continuation = "issued-cursor", http = http),
        "comment" to loadCommentReplies("issued-cursor", http = http))

    @Test fun realMetadataOnlyEndsRetainEmptyModelsAndRemoveAllContinuationCapabilities() {
        for (body in listOf("""{"responseContext":{}}""",
            """{"responseContext":{"serviceTrackingParams":[]},"trackingParams":"server-metadata"}""")) {
            val wire = Wire(body)
            for ((operation, result) in continued(wire)) {
                if (operation == "comment") {
                    assertFalse("metadata alone cannot establish a comment end", result.ok)
                    assertNull(result.value)
                    assertEquals(RequestFailure.PARSE, result.error?.kind)
                    assertEquals("unknown_comment_page", result.error?.code)
                    continue
                }
                assertTrue("$operation: ${result.error}", result.ok)
                when (val page = result.value) {
                    is BrowsePage -> { assertTrue(page.items.isEmpty()); assertNull(page.nextKey) }
                    is SearchPage -> { assertTrue(page.items.isEmpty()); assertNull(page.nextKey) }
                    is ChannelPage -> { assertTrue(page.page.items.isEmpty()); assertNull(page.page.nextKey) }
                    is PlaylistPage -> { assertTrue(page.videos.isEmpty()); assertNull(page.nextKey) }
                    is WatchPage -> { assertTrue(page.related.isEmpty()); assertNull(page.nextKey); assertNull(page.commentsKey) }
                    else -> fail("$operation returned an unexpected model")
                }
            }
            assertEquals(7, wire.requests.size)
            assertTrue(wire.requests.all { jsonObject(it.body)?.text("continuation") == "issued-cursor" })
        }
    }

    @Test fun explicitOwnedCommentCollectionsRetainEmptyModelsAndRemoveContinuationCapabilities() {
        for (body in listOf("""{"responseContext":{},"continuationContents":{"itemSectionContinuation":{"contents":[]}}}""",
            """{"responseContext":{},"continuationContents":{"commentRepliesContinuation":{"contents":[]}}}""",
            """{"onResponseReceivedEndpoints":[{"appendContinuationItemsAction":{"continuationItems":[]}}]}""")) {
            val wire = Wire(body)
            for (result in listOf(loadComments("abcdefghijk", RequestContext(), continuation = "issued-cursor", http = wire),
                loadCommentReplies("issued-cursor", http = wire))) {
                assertTrue(result.error.toString(), result.ok)
                val page = result.value!!
                assertTrue(page.comments.isEmpty()); assertNull(page.nextKey); assertNull(page.createKey)
                assertTrue(page.sortOptions.isEmpty())
            }
            assertEquals(2, wire.requests.size)
            assertTrue(wire.requests.all { jsonObject(it.body)?.text("continuation") == "issued-cursor" })
        }
    }

    @Test fun emptyObjectsMissingContextMalformedMetadataAndUnknownSiblingsAreParseFailuresForEveryContinuation() {
        val bad = listOf("{}", """{"trackingParams":"metadata-without-context"}""",
            """{"responseContext":{},"unknown":{}}""", """{"unknown":{"responseContext":{}}}""") +
            listOf("null", "false", "0", "[]", "\"wrong-type\"").map { "{\"responseContext\":$it}" } +
            listOf("null", "false", "0", "[]", "{}").map { "{\"responseContext\":{},\"trackingParams\":$it}" }
        for (body in bad) {
            val wire = Wire(body)
            for ((operation, result) in continued(wire)) {
                assertFalse("$operation accepted $body", result.ok)
                assertNull(result.value)
                assertEquals("$operation: $body", RequestFailure.PARSE, result.error?.kind)
                assertEquals("unknown_${operation}_page", result.error?.code)
            }
            assertEquals(7, wire.requests.size)
        }
    }

    @Test fun metadataOnlyResponsesDoNotEstablishInitialBrowseSearchChannelPlaylistOrWatchPages() {
        val wire = Wire("""{"responseContext":{},"trackingParams":"server-metadata"}""")
        val results = listOf(loadBrowse("FEwhat_to_watch", http = wire), loadSearch("fixture", http = wire),
            loadChannel("UCfixture", http = wire), loadPlaylist("PLfixture", RequestContext(), http = wire),
            loadWatch("abcdefghijk", http = wire))
        assertEquals(5, wire.requests.size)
        assertTrue(results.all { it.value == null && it.error?.kind == RequestFailure.PARSE })
    }
}
