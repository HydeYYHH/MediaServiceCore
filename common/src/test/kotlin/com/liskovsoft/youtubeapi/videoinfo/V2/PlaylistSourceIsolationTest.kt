package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class PlaylistSourceIsolationTest {
    private fun video(id: String = "owned", set: String = "entry") =
        """{"playlistVideoRenderer":{"videoId":"$id","setVideoId":"$set","title":{"simpleText":"Entry"}}}"""
    private fun cursor(token: String = "own-next") =
        """{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"$token"}}}}"""
    private fun list(rows: String = video(), extra: String = "") =
        """{"playlistVideoListRenderer":{"playlistId":"PLowned","targetId":"PLowned","contents":[$rows]$extra}}"""
    private fun web(primary: String, outside: String = "", itemExtra: String = "") = """{
        "metadata":{"playlistMetadataRenderer":{"title":"Owned"}},
        "contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"selected":true,
        "content":{"sectionListRenderer":{"targetId":"VLPLowned","contents":[
        {"itemSectionRenderer":{$itemExtra"contents":[$primary]}}$outside]}}}}]}}}"""
    private class Wire(private val body: String) : HttpClient {
        val requests = ArrayList<HttpRequest>()
        override fun execute(request: HttpRequest): HttpReply {
            requests += request
            return HttpReply(200, body)
        }
    }
    private fun read(body: String, continuation: Boolean = false, id: String? = "PLowned"): ReadResult<PlaylistPage> {
        val wire = Wire(body)
        val result = loadPlaylist(id, RequestContext(), if (continuation) "issued" else null, wire)
        assertEquals(1, wire.requests.size)
        return result
    }
    private fun rejected(body: String, continuation: Boolean = false, id: String? = "PLowned") {
        val result = read(body, continuation, id)
        assertNull(result.value)
        assertEquals(RequestFailure.PARSE, result.error?.kind)
        assertEquals("unknown_playlist_page", result.error?.code)
        assertTrue(playlistPage(body).videos.isEmpty())
        assertNull(playlistPage(body).nextKey)
    }

    @Test fun terminalOwnedListNeverBorrowsParentOrOtherSectionCursor() {
        val body = web(list(), ",${cursor("outside")}", "\"continuations\":[{\"nextContinuationData\":{\"continuation\":\"parent\"}}],")
        val page = read(body).value!!
        assertEquals(listOf("owned"), page.videos.map { it.videoId })
        assertNull(page.nextKey)
        assertEquals(page, playlistPage(body))
    }

    @Test fun terminalOwnedListNeverBorrowsRootAppendOrMetadataCursor() {
        val root = jsonObject(web(list()))!!
        val fields = root.fields + mapOf(
            "responseContext" to jsonObject("""{"nested":${cursor("metadata")}}""")!!,
            "onResponseReceivedActions" to JsonReader("""[{"appendContinuationItemsAction":{"continuationItems":[${cursor("response")}]}}]""").parse())
        val page = read(Json.Obj(fields).encode()).value!!
        assertNull(page.nextKey)
        assertEquals(1, page.videos.size)
    }

    @Test fun ownCursorRemainsOwnedEvenWhenAnOutsideCursorHasIdenticalBytes() {
        val body = web(list("${video()},${cursor()}"), ",${cursor()}")
        assertEquals("own-next", read(body).value!!.nextKey)
        assertEquals("own-next", playlistPage(body).nextKey)
    }

    @Test fun onlyDirectListEntriesContributeAcrossSidebarAndOtherSections() {
        // Construct the unrelated section without pretending it is a direct list slot.
        val valid = web(list(), ",{\"itemSectionRenderer\":{\"contents\":[${video("other-section")},${cursor("other-next")}]}}")
        val root = jsonObject(valid)!!
        val result = read(Json.Obj(root.fields + ("sidebar" to jsonObject("""{"video":${video("sidebar")},"cursor":${cursor("sidebar-next")}}""")!!)).encode()).value!!
        assertEquals(listOf("owned"), result.videos.map { it.videoId })
        assertNull(result.nextKey)
    }

    @Test fun unselectedTabCannotContributeEntriesOrCursor() {
        val body = """{"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[
            {"tabRenderer":{"selected":false,"content":${list("${video("foreign")},${cursor("foreign")}")}}},
            {"tabRenderer":{"selected":true,"content":${list()}}}]}}}"""
        val page = read(body).value!!
        assertEquals(listOf("owned"), page.videos.map { it.videoId }); assertNull(page.nextKey)
    }

    @Test fun unknownOwnedSlotCannotBeTreatedAsAnEndOrSearchedForVideos() {
        for (slot in listOf("""{"futureRenderer":{"nested":${video()}}}""", """{"futureRenderer":{}}"""))
            rejected(web(list("${video()},$slot")))
    }

    @Test fun missingVideoIdCannotBorrowAnEntryFromInsideItsRenderer() {
        val body = web(list("""{"playlistVideoRenderer":{"title":{"simpleText":"Unavailable"},"future":${video("borrowed")}}},${video()}"""))
        assertEquals(listOf("owned"), read(body).value!!.videos.map { it.videoId })
    }

    @Test fun multipleOrMalformedOwnedListsDoNotFallBackToAnotherList() {
        for (primary in listOf("${list()},${list()}", "${list()},{\"playlistVideoListRenderer\":\"wrong\"}"))
            rejected(web(primary))
        rejected(web("""{"playlistVideoListRenderer":{"contents":"wrong"},"future":${list()}}"""))
    }

    @Test fun unknownWrapperCannotLendAHiddenListOrAnEmptyEnd() {
        rejected(web("""{"futureRenderer":{"nested":${list()}}}"""))
        rejected("""{"metadata":{"playlistMetadataRenderer":{"title":"Display only"}},"future":${list("")}}""")
    }

    @Test fun ambiguousTabsAndFlatSectionsAreNotMerged() {
        rejected("""{"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[
            {"tabRenderer":{"selected":true,"content":${list()}}},
            {"tabRenderer":{"selected":true,"content":${list()}}}]}}}""")
        rejected(web(video(), ",{\"itemSectionRenderer\":{\"contents\":[${video("second")}]}}"))
    }

    @Test fun declaredListAndAncestorIdentitiesMustMatchTheActualRequest() {
        for (body in listOf(web(list().replace("PLowned", "PLother")),
            web(list()).replace("VLPLowned", "VLPLother"),
            web(list(), itemExtra = "\"targetId\":\"PLother\","),
            web(list().replace("\"targetId\":\"PLowned\"", "\"targetId\":\"PLother\"")))) {
            assertEquals(RequestFailure.PARSE, read(body).error?.kind)
        }
        assertTrue(read(web(list()), id = "VLPLowned").ok)
        for (id in listOf("WL", "LL", "OLAKfixture")) {
            val body = """{"contents":{"playlistVideoListRenderer":{"playlistId":"$id","targetId":"VL$id","contents":[]}}}"""
            assertTrue(read(body, id = id).ok)
        }
    }

    @Test fun continuationUsesOneDirectIssuedCollectionOnly() {
        val body = """{"responseContext":{"future":${cursor("hidden")}},"onResponseReceivedEndpoints":[{
            "appendContinuationItemsAction":{"targetId":"PLowned","continuationItems":[${video()},${cursor()}]}}]}"""
        val page = read(body, true).value!!
        assertEquals(listOf("owned"), page.videos.map { it.videoId }); assertEquals("own-next", page.nextKey)
        assertEquals(page, playlistPage(body))
        val foreign = body.replace("\"targetId\":\"PLowned\"", "\"targetId\":\"PLother\"")
        assertEquals(RequestFailure.PARSE, read(foreign, true).error?.kind)
        // A pure parser has no caller playlist identity; its isolated collection is still readable.
        assertEquals("owned", playlistPage(foreign).videos.single().videoId)
        rejected(body.replace("\"targetId\":\"PLowned\"", "\"targetId\":\"comments-section\""), true, null)
    }

    @Test fun continuationCannotBorrowNestedOrParallelAppendCollections() {
        rejected("""{"future":{"appendContinuationItemsAction":{"continuationItems":[${video()}]}}}""", true)
        rejected("""{"onResponseReceivedActions":[{"appendContinuationItemsAction":{"continuationItems":[${video()}]}},
            {"appendContinuationItemsAction":{"continuationItems":[]}}]}""", true)
        rejected("""{"continuationContents":{"playlistVideoListContinuation":{"contents":[]},"gridContinuation":{"items":[]}}}""", true)
    }

    @Test fun cursorCarriersAreTypedUniqueAndBrowseRouted() {
        val bad = listOf(
            """{"continuationItemRenderer":{"continuationEndpoint":"wrong","button":{"buttonRenderer":{"command":{"continuationCommand":{"token":"nested"}}}}}}""",
            """{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":""}}}}""",
            cursor().replace("\"token\":\"own-next\"", "\"token\":\"own-next\",\"request\":\"CONTINUATION_REQUEST_TYPE_NEXT\""),
            cursor().replace("\"continuationEndpoint\":{", "\"continuationEndpoint\":{\"commandMetadata\":{\"webCommandMetadata\":{\"apiUrl\":\"/youtubei/v1/next\"}},"),
            "${cursor()},${cursor()}"
        )
        bad.forEach { rejected(web(list("${video()},$it"))) }
        assertEquals("own-next", read(web(list("${video()},${cursor()}"))).value!!.nextKey)
    }

    @Test fun ancestorRouteDeclarationsCannotBeStrippedIntoAnImplicitBrowse() {
        for (api in listOf("\"/youtubei/v1/next\"", "{}")) {
            val extra = "\"commandMetadata\":{\"webCommandMetadata\":{\"apiUrl\":$api}},"
            rejected(web(list(), itemExtra = extra))
        }
    }

    @Test fun ownNextDataWorksButReloadAndParentDataAreNotForwardCursors() {
        val next = ""","continuations":[{"nextContinuationData":{"continuation":"own"}}]"""
        assertEquals("own", read(web(list(extra = next))).value!!.nextKey)
        val reload = next.replace("nextContinuationData", "reloadContinuationData")
        assertNull(read(web(list(extra = reload))).value!!.nextKey)
        rejected(web(list("${video()},${cursor()}", next)))
    }

    @Test fun legacyFlatAndMetadataOnlyEndRemainOperationSpecific() {
        val flat = """{"contents":[${video()},${video("owned", "second")},${cursor()}]}"""
        val page = read(flat).value!!
        assertEquals(listOf("entry", "second"), page.videos.map { it.setVideoId }); assertEquals("own-next", page.nextKey)
        val end = """{"responseContext":{},"trackingParams":"opaque"}"""
        assertTrue(read(end, true).ok)
        assertEquals(RequestFailure.PARSE, read(end).error?.kind)
    }

    @Test fun selectedFlatModernListRetainsOnlyItsOwnModernCursor() {
        val modern = """{"lockupViewModel":{"contentType":"LOCKUP_CONTENT_TYPE_VIDEO","contentId":"owned",
            "metadata":{"lockupMetadataViewModel":{"title":{"content":"Entry"}}},
            "rendererContext":{"commandContext":{"onTap":{"innertubeCommand":{"watchEndpoint":{"videoId":"owned","playlistSetVideoId":"entry"}}}}}}}"""
        val next = """{"continuationItemViewModel":{"continuationCommand":{"innertubeCommand":{
            "continuationCommand":{"token":"modern","request":"CONTINUATION_REQUEST_TYPE_BROWSE"}}}}}"""
        val body = web(modern, ",$next")
        val page = read(body).value!!
        assertEquals("owned", page.videos.single().videoId); assertEquals("modern", page.nextKey)
    }

    @Test fun declaredVideoIdentitiesAndPhysicalEntriesCannotConflict() {
        rejected(web(list("""{"playlistVideoRenderer":{"videoId":"owned","navigationEndpoint":{"watchEndpoint":{"videoId":"other"}}}}""")))
        rejected(web(list("""{"playlistVideoRenderer":{"videoId":"owned","setVideoId":"entry","navigationEndpoint":{"watchEndpoint":{"videoId":"owned","playlistSetVideoId":"other-entry"}}}}""")))
    }

    @Test fun wholeDocumentAndOwnedSlotBudgetsFailClosed() {
        val body = web(list())
        val root = jsonObject(body)!!
        var nested: Json = Json.Obj(emptyMap())
        repeat(100) { nested = Json.Obj(mapOf("presentation" to nested)) }
        assertNull(ownedPlaylistCollection(Json.Obj(root.fields + ("metadataExtra" to nested))))
        rejected(web(list(List(2001) { video() }.joinToString(","))))
    }

    @Test fun callbackLoaderChecksDeclaredPlaylistIdentityWithoutChangingItsAbi() {
        var calls = 0
        val good = loadPlaylist("PLowned", null) { calls++; 200 to web(list()) }
        assertEquals(1, calls); assertEquals("owned", good.videos.single().videoId)
        val foreign = loadPlaylist("PLother", null) { calls++; 200 to web(list()) }
        assertEquals(2, calls); assertTrue(foreign.videos.isEmpty()); assertNull(foreign.nextKey)
    }

    @Test fun parallelDeclaredCollectionOrCommandWrappersAreAmbiguous() {
        rejected(web("""{"playlistVideoListRenderer":{"contents":[]},"futureRenderer":{"contents":[]}}"""))
        rejected("""{"onResponseReceivedEndpoints":[{"appendContinuationItemsAction":{"continuationItems":[]},
            "futureAction":{"continuationItems":[${video()}]}}]}""", true)
    }

    @Test fun modernAndTileDeclaredIdentityTypesAreNotSilentFallbacks() {
        val tile = """{"tileRenderer":{"contentId":"other","onSelectCommand":{"watchEndpoint":{"videoId":"owned"}}}}"""
        rejected(web(list(tile)))
        val modern = """{"lockupViewModel":{"contentType":"LOCKUP_CONTENT_TYPE_VIDEO","contentId":"other",
            "rendererContext":{"commandContext":{"onTap":{"innertubeCommand":{"watchEndpoint":{"videoId":"owned"}}}}}}}"""
        rejected(web(list(modern)))
        rejected(web(list(modern.replace("\"contentId\":\"other\"", "\"contentId\":{}"))))
        rejected(web(list("""{"tileRenderer":{"onSelectCommand":{"watchEndpoint":{"videoId":{}}}}}""")))
    }

    @Test fun leafPlaylistIdentityCannotLendAnotherListsPhysicalEntry() {
        val legacy = """{"playlistVideoRenderer":{"videoId":"owned","navigationEndpoint":{"watchEndpoint":{
            "videoId":"owned","playlistId":"PLother","playlistSetVideoId":"foreign-entry"}}}}"""
        val tile = """{"tileRenderer":{"onSelectCommand":{"watchEndpoint":{
            "videoId":"owned","playlistId":"PLother","playlistSetVideoId":"foreign-entry"}}}}"""
        val modern = """{"lockupViewModel":{"contentType":"LOCKUP_CONTENT_TYPE_VIDEO","contentId":"owned",
            "rendererContext":{"commandContext":{"onTap":{"innertubeCommand":{"watchEndpoint":{
            "videoId":"owned","playlistId":"PLother","playlistSetVideoId":"foreign-entry"}}}}}}}"""
        for (slot in listOf(legacy, tile, modern)) {
            rejected(web(list(slot)))
            rejected(web(list(slot.replace("\"playlistId\":\"PLother\"", "\"playlistId\":{}"))))
            val matched = slot.replace("PLother", "PLowned")
            assertTrue(read(web(list(matched))).ok)
        }
        rejected(web(list(legacy.replace("\"videoId\":\"owned\",\"navigationEndpoint\"",
            "\"playlistId\":\"PLother\",\"videoId\":\"owned\",\"navigationEndpoint\""))))
    }

    @Test fun mixedInitialAndContinuationEnvelopesHaveOneRejectionRule() {
        val root = jsonObject(web(list()))!!
        val continuation = jsonObject("""{"playlistVideoListContinuation":{"contents":[${video("other")}]}}""")!!
        val mixed = Json.Obj(root.fields + ("continuationContents" to continuation)).encode()
        rejected(mixed)
        rejected(mixed, continuation = true)
        assertTrue(playlistPage(web(list())).videos.isNotEmpty())
    }

    @Test fun loaderKeepsTheSuppliedSignedContextAndOneReadWithoutFallback() {
        val context = RequestContext(accountId = "fixture-account",
            onBehalfOfUser = "fixture-delegate", webSession = WebSession("SAPISID=fixture", 1))
        val wire = Wire(web(list("${video()},${cursor()}")))
        assertTrue(loadPlaylist("PLowned", context, http = wire).ok)
        assertEquals(1, wire.requests.size)
        val request = wire.requests.single()
        assertTrue(request.body.contains("PLowned"))
        assertEquals("1", request.headers["X-Goog-AuthUser"])
        assertTrue(request.headers.keys.any { it.equals("Authorization", true) })
    }
}
