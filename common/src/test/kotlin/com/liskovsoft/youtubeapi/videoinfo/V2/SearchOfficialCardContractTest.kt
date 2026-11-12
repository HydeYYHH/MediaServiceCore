package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class SearchOfficialCardContractTest {
    private fun video(id: String) = """{"videoRenderer":{"videoId":"$id","title":"Owned video"}}"""
    private fun cursor(token: String, request: String = "CONTINUATION_REQUEST_TYPE_SEARCH") = """{
        "continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"$token","request":"$request"}}}}"""
    private fun action(id: String) = """{"buttonCardViewModel":{"title":"Owned action","rendererContext":{
        "commandContext":{"onTap":{"innertubeCommand":{"browseEndpoint":{"browseId":"$id"}}}}}}}"""
    private fun message(text: String) = """{"messageRenderer":{"text":{"simpleText":"$text"}}}"""
    private fun official(content: String = "") = """{"officialCardViewModel":{$content}}"""
    private fun opening(slots: String, next: String? = null, estimate: String = "1234"): String = """{
        "estimatedResults":"$estimate","contents":{"twoColumnSearchResultsRenderer":{"primaryContents":{
        "sectionListRenderer":{"contents":[{"itemSectionRenderer":{"contents":[$slots]}}${next?.let { ",${cursor(it)}" }.orEmpty()}]}}}}}"""
    private fun continued(slots: String, next: String? = null, estimate: String = "1234") = """{
        "estimatedResults":"$estimate","onResponseReceivedCommands":[{"appendContinuationItemsAction":{
        "targetId":"search-feed","continuationItems":[$slots${next?.let { ",${cursor(it)}" }.orEmpty()}]}}]}"""
    private fun read(body: String, continuation: Boolean = false): ReadResult<SearchPage> = loadSearch(
        if (continuation) null else "rose", continuation = if (continuation) "issued-page" else null,
        http = object : HttpClient { override fun execute(request: HttpRequest) = HttpReply(200, body) })
    private fun rejected(body: String, continuation: Boolean) {
        assertEquals("unknown_search_page", read(body, continuation).error?.code)
        val parsed = searchPage(body)
        assertTrue(parsed.items.isEmpty()); assertTrue(parsed.groups.isEmpty()); assertTrue(parsed.actions.isEmpty())
        assertTrue(parsed.messages.isEmpty()); assertTrue(parsed.filters.isEmpty()); assertNull(parsed.nextKey)
    }
    private fun untrustedOfficial(): String {
        val filter = """{"searchFilterGroupRenderer":{"title":"Hidden filter","filters":[{"searchFilterRenderer":{
            "label":"Hidden","navigationEndpoint":{"searchEndpoint":{"params":"hidden-filter"}}}}]}}"""
        return official(""""commandMetadata":{"webCommandMetadata":{"apiUrl":"https://foreign.test/youtubei/v1/browse"}},
            "contents":[${video("hidden-video")},${cursor("hidden-cursor", "CONTINUATION_REQUEST_TYPE_BROWSE")},
                ${action("FEhidden")},${message("Hidden message")},$filter],
            "continuations":[{"nextContinuationData":{"continuation":"hidden-declared-cursor"}}]""")
    }

    @Test fun observedOfficialCardKeepsOrganicOrderAndTheOwnedPageCursorWithoutLeakingItsChildren() {
        val own = (0 until 12).map { video("own-$it") }
        val slots = (own.take(5) + untrustedOfficial() + own.drop(5) + action("FEowned")).joinToString(",")
        for ((body, continuation) in listOf(opening(slots, "owned-next") to false, continued(slots, "owned-next") to true)) {
            val result = read(body, continuation)
            assertTrue(result.error?.code, result.ok)
            val loaded = result.value!!
            assertEquals((0 until 12).map { "own-$it" }, loaded.items.map { it.id })
            assertTrue(loaded.groups.flatMap { it.items }.all { it.id.startsWith("own-") })
            assertEquals("owned-next", loaded.nextKey)
            assertEquals(listOf("FEowned"), loaded.actions.map { jsonObject(it.endpoint.payload)!!.text("browseId") })
            assertTrue(loaded.messages.isEmpty()); assertTrue(loaded.filters.isEmpty())
            assertEquals(loaded, searchPage(body))
        }
    }

    @Test fun theIssuedOuterCursorStillLoadsTheNextNativeSearchPage() {
        var requests = 0
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                val body = jsonObject(request.body)!!
                return when (requests++) {
                    0 -> {
                        assertEquals("rose", body.text("query")); assertNull(body.text("continuation"))
                        HttpReply(200, opening("${official()},${video("first")}", "issued-page"))
                    }
                    1 -> {
                        assertNull(body.text("query")); assertEquals("issued-page", body.text("continuation"))
                        HttpReply(200, continued("${untrustedOfficial()},${video("second")}"))
                    }
                    else -> error("unexpected search request")
                }
            }
        }
        val first = loadSearch("rose", http = http).value!!
        assertEquals(listOf("first"), first.items.map { it.id })
        val second = loadSearch(null, continuation = first.nextKey, http = http).value!!
        assertEquals(listOf("second"), second.items.map { it.id }); assertNull(second.nextKey)
        assertEquals(2, requests)
    }

    @Test fun anOfficialCardCannotBecomeASuccessfulEmptyPageThroughCursorsEmptyListsOrZeroEstimates() {
        val auxiliary = untrustedOfficial()
        val empty = """{"itemSectionRenderer":{"contents":[]}}"""
        val advertisement = """{"searchPyvRenderer":{}}"""
        for (slots in listOf(auxiliary, "$auxiliary,$empty", "$auxiliary,$advertisement")) {
            for (next in listOf<String?>(null, "owned-next")) {
                rejected(opening(slots, next, "0"), false)
                rejected(continued(slots, next, "0"), true)
                val fragment = searchPage("""{"contents":[$slots${next?.let { ",${cursor(it)}" }.orEmpty()}]}""")
                assertTrue(fragment.items.isEmpty()); assertTrue(fragment.actions.isEmpty()); assertNull(fragment.nextKey)
            }
        }
    }

    @Test fun onlySupportedSiblingMessagesAndActionsCanEstablishADisplayablePage() {
        for ((body, continuation) in listOf(
            opening("${untrustedOfficial()},${message("Owned message")}") to false,
            continued("${untrustedOfficial()},${message("Owned message")}") to true)) {
            val loaded = read(body, continuation).value!!
            assertTrue(loaded.items.isEmpty()); assertEquals(listOf("Owned message"), loaded.messages)
            assertTrue(loaded.actions.isEmpty()); assertNull(loaded.nextKey)
        }
        val loaded = read(opening("${untrustedOfficial()},${action("FEowned")}")).value!!
        assertTrue(loaded.items.isEmpty()); assertEquals(1, loaded.actions.size)
        assertEquals("FEowned", jsonObject(loaded.actions.single().endpoint.payload)!!.text("browseId"))
    }

    @Test fun theOpaqueExceptionDoesNotAuthorizeWrongTypesParallelKindsOrOtherRendererNames() {
        val malformed = listOf(
            """{"officialCardViewModel":null}""", """{"officialCardViewModel":[]}""",
            """{"officialCardViewModel":"card"}""", """{"officialCardViewModel":{},"videoRenderer":{"videoId":"parallel"}}""",
            """{"officialCardViewModel":{},"trackingParams":false}""",
            """{"officialCardRenderer":{}}""", """{"futureOfficialCardViewModel":{}}""",
            """{"horizontalCardListRenderer":{}}""", """{"universalWatchCardRenderer":{}}""",
            """{"futureWrapper":{"content":${official()}}}"""
        )
        for (slot in malformed) {
            rejected(opening("${video("own")},$slot"), false)
            rejected(continued("${video("own")},$slot"), true)
        }
    }

    @Test fun opaqueChildrenCannotRepairForeignOrAmbiguousOwnedCursors() {
        val auxiliary = untrustedOfficial()
        val wrongRoute = """{"continuationItemRenderer":{"continuationEndpoint":{
            "commandMetadata":{"webCommandMetadata":{"apiUrl":"/youtubei/v1/browse"}},
            "continuationCommand":{"token":"own"}}}}"""
        for (slot in listOf(wrongRoute, "${cursor("same")},${cursor("same")}")) {
            rejected(opening("${video("own")},$auxiliary,$slot"), false)
            rejected(continued("${video("own")},$auxiliary,$slot"), true)
        }
        val root = jsonObject(opening("${video("own")},$auxiliary", "owned-next"))!!
        val foreign = jsonObject("""{"webCommandMetadata":{"apiUrl":"/youtubei/v1/browse"}}""")!!
        rejected(Json.Obj(root.fields + ("commandMetadata" to foreign)).encode(), false)
    }
}
