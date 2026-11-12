package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class NetworkPageShapeTest {
    private var body = "{}"
    private val http = object : HttpClient {
        override fun execute(request: HttpRequest) = HttpReply(200, body)
    }

    @Test fun initialReadsRejectUnknownObjectsAndNestedEmptyListsWithoutTrustingRequestedIds() {
        for (unknown in listOf("{}", """{"responseContext":{}}""",
            """{"futureRenderer":{"items":[]}}""", """{"futureRenderer":{"contents":[]}}""",
            """{"futureRenderer":{"nested":{"continuationItems":[]}}}""")) {
            body = unknown
            val results = listOf(loadBrowse("UCrequested", http = http), loadSearch("query", http = http),
                loadChannel("UCrequested", http = http), loadPlaylist("PLrequested", RequestContext(), http = http),
                loadWatch("requested", http = http))
            assertTrue(unknown, results.all { !it.ok && it.error?.kind == RequestFailure.PARSE })
            // Public parsers still return their partial/default models for host-side inspection.
            assertEquals("UCrequested", channelPage(unknown, "UCrequested").id)
            assertEquals("requested", watchPage(unknown, "requested").videoId)
            assertTrue(playlistPage(unknown).videos.isEmpty())
        }
    }

    @Test fun unknownContinuationTypesAndUnknownRowsCannotPretendThatEveryListEnded() {
        for (unknown in listOf("""{"continuationContents":{"futureContinuation":{"items":[]}}}""",
            """{"continuationContents":{}}""",
            """{"continuationContents":{"gridContinuation":{"items":[{"futureRenderer":{"items":[]}}]}}}""",
            """{"onResponseReceivedEndpoints":[{"futureAction":{"continuationItems":[]}}]}""",
            """{"futureRenderer":{"appendContinuationItemsAction":{"continuationItems":[]}}}""")) {
            body = unknown
            val results = listOf(loadBrowse(null, continuation = "cursor", http = http),
                loadSearch(null, continuation = "cursor", http = http),
                loadChannel("UCrequested", continuation = "cursor", http = http),
                loadPlaylist(null, RequestContext(), "cursor", http),
                loadWatch(null, continuation = "cursor", http = http), loadCommentReplies("cursor", http = http),
                loadComments("requested", RequestContext(), continuation = "cursor", http = http))
            assertTrue(unknown, results.all { !it.ok && it.error?.kind == RequestFailure.PARSE })
        }
    }

    @Test fun explicitEmptyInitialContainersPreserveValidPrivateAndPublicPages() {
        body = """{"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"title":"Videos",
            "selected":true,"content":{"richGridRenderer":{"contents":[]}}}}]}}}"""
        assertTrue(loadBrowse("UCrequested", http = http).value!!.items.isEmpty())
        assertEquals("Videos", loadChannel("UCrequested", http = http).value!!.page.tabs.single().title)
        body = """{"contents":{"twoColumnSearchResultsRenderer":{"primaryContents":{"sectionListRenderer":{"contents":[]}}}}}"""
        assertTrue(loadSearch("no results", http = http).value!!.items.isEmpty())
        body = """{"metadata":{"playlistMetadataRenderer":{"title":"Empty private list"}},
            "contents":{"playlistVideoListRenderer":{"contents":[]}}}"""
        assertEquals("Empty private list", loadPlaylist("PLprivate", RequestContext(), http = http).value!!.title)
        body = """{"contents":{"twoColumnWatchNextResults":{"results":{"results":{"contents":[]}}}}}"""
        assertTrue(loadWatch("requested", http = http).value!!.related.isEmpty())
    }

    @Test fun knownEmptyContinuationEnvelopesAreOperationSpecificAndKeepTheEndOfListVisible() {
        body = """{"continuationContents":{"gridContinuation":{"items":[]}}}"""
        assertTrue(loadBrowse(null, continuation = "cursor", http = http).value!!.items.isEmpty())
        assertTrue(loadChannel("UCrequested", continuation = "cursor", http = http).ok)
        assertTrue(loadSearch(null, continuation = "cursor", http = http).ok)
        assertEquals("unknown_comment_page", loadCommentReplies("cursor", http = http).error?.code)
        assertEquals("unknown_watch_page", loadWatch(null, continuation = "cursor", http = http).error?.code)
        body = """{"continuationContents":{"playlistVideoListContinuation":{"contents":[]}}}"""
        assertTrue(loadPlaylist(null, RequestContext(), "cursor", http).value!!.videos.isEmpty())
        body = """{"continuationContents":{"tvSurfaceContentContinuation":{"content":{"sectionListRenderer":{"contents":[]}}}}}"""
        assertTrue(loadWatch(null, continuation = "cursor", http = http).value!!.related.isEmpty())
        body = """{"continuationContents":{"commentRepliesContinuation":{"contents":[]}}}"""
        assertTrue(loadCommentReplies("cursor", http = http).value!!.comments.isEmpty())
        assertEquals("unknown_browse_page", loadBrowse(null, continuation = "cursor", http = http).error?.code)
        for (end in listOf("""{"responseContext":{}}""", """{"responseContext":{},"trackingParams":"opaque"}""")) {
            body = end
            assertTrue(end, loadBrowse(null, continuation = "cursor", http = http).ok)
            assertTrue(end, loadWatch(null, continuation = "cursor", http = http).ok)
            for (comment in listOf(loadCommentReplies("cursor", http = http),
                loadComments("requested", RequestContext(), continuation = "cursor", http = http))) {
                assertFalse(end, comment.ok); assertNull(comment.value)
                assertEquals(RequestFailure.PARSE, comment.error?.kind)
                assertEquals("unknown_comment_page", comment.error?.code)
            }
        }
        body = """{"onResponseReceivedEndpoints":[{"appendContinuationItemsAction":{"continuationItems":[]}}]}"""
        assertTrue(loadBrowse(null, continuation = "cursor", http = http).ok)
        assertTrue(loadWatch(null, continuation = "cursor", http = http).ok)
        for (result in listOf(loadCommentReplies("cursor", http = http),
            loadComments("requested", RequestContext(), continuation = "cursor", http = http))) {
            assertTrue(result.error.toString(), result.ok)
            assertTrue(result.value!!.comments.isEmpty()); assertNull(result.value!!.nextKey); assertNull(result.value!!.createKey)
        }
    }

    @Test fun metadataAndCommentsOpeningCapabilitiesAreServerEvidenceButContinuationDataCannotBeAnInitialPage() {
        body = """{"metadata":{"channelMetadataRenderer":{"externalId":"UCactual","title":"No uploads"}}}"""
        assertEquals("UCactual", loadChannel("UCrequested", http = http).value!!.id)
        body = """{"videoDetails":{"videoId":"requested"},"engagementPanelSectionListRenderer":{
            "panelIdentifier":"engagement-panel-comments-section","content":{"continuationItemRenderer":{
            "continuationEndpoint":{"continuationCommand":{"token":"opening"}}}}}}"""
        assertEquals("opening", loadWatch("requested", http = http).value!!.commentsKey)
        body = """{"continuationContents":{"gridContinuation":{"items":[]}}}"""
        assertEquals("unknown_browse_page", loadBrowse("UCrequested", http = http).error?.code)
        assertEquals("unknown_channel_page", loadChannel("UCrequested", http = http).error?.code)
    }

    @Test fun webInitialWatchKeepsChatAndMetadataWhenAuxiliaryResponseActionsArePresent() {
        body = """{"contents":{"twoColumnWatchNextResults":{"results":{"results":{"contents":[{
            "videoPrimaryInfoRenderer":{"title":{"simpleText":"Live fixture"}}}]}}}},
            "liveChatRenderer":{"continuations":[{"reloadContinuationData":{"continuation":"live-chat"}}]},
            "onResponseReceivedEndpoints":[{"updateToggleButtonStateCommand":{"buttonId":"fixture-control","toggled":true}}]}"""
        val opened = loadWatch("requested", http = http)
        assertTrue(opened.error?.code, opened.ok)
        assertEquals("requested", opened.value!!.videoId)
        assertEquals("Live fixture", opened.value!!.title)
        assertEquals("live-chat", opened.value!!.chatKey)
    }

    @Test fun responseActionsCannotTurnPureContinuationsOrUnknownPrimaryContentsIntoInitialPages() {
        val append = """{"appendContinuationItemsAction":{"continuationItems":[{
            "compactVideoRenderer":{"videoId":"continued","title":{"simpleText":"Continued"}}}]}}"""
        for (contents in listOf("", """"contents":{"futureWatchResults":{"items":[]}},""",
            """"contents":{"twoColumnWatchNextResults":{}},""",
            """"contents":{"twoColumnWatchNextResults":{"futureResults":{"items":[]}}},""")) {
            body = """{$contents"onResponseReceivedEndpoints":[$append]}"""
            assertEquals("unknown_watch_page", loadWatch("requested", http = http).error?.code)
            assertEquals("unknown_browse_page", loadBrowse("UCrequested", http = http).error?.code)
            assertEquals("unknown_channel_page", loadChannel("UCrequested", http = http).error?.code)
            assertEquals("unknown_playlist_page", loadPlaylist("PLrequested", RequestContext(), http = http).error?.code)
            assertEquals("unknown_search_page", loadSearch("query", http = http).error?.code)
            // The same real command envelope is still usable in its continuation lane.
            assertEquals("continued", loadWatch(null, continuation = "cursor", http = http).value!!.related.single().id)
        }
    }
}
