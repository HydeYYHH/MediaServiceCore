package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class SearchGridShelfContractTest {
    private fun video(id: String) = """{"videoRenderer":{"videoId":"$id","title":{"simpleText":"Fixture video"}}}"""
    private fun short(id: String) = """{"shortsLockupViewModel":{"overlayMetadata":{"primaryText":{"content":"Fixture short"}},
        "onTap":{"innertubeCommand":{"reelWatchEndpoint":{"videoId":"$id"}}}}}"""
    private fun grid(cards: String) = """{"gridShelfViewModel":{"contents":[$cards]}}"""
    private fun cursor(token: String) = """{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"$token"}}}}"""
    private fun page(slots: String, next: String? = null, estimate: String = "1234") = """{
        "estimatedResults":"$estimate","contents":{"twoColumnSearchResultsRenderer":{"primaryContents":{
        "sectionListRenderer":{"contents":[{"itemSectionRenderer":{"contents":[$slots]}}${next?.let { ",${cursor(it)}" }.orEmpty()}]}}}}}"""
    private fun continuation(slots: String) = """{"onResponseReceivedCommands":[{
        "appendContinuationItemsAction":{"continuationItems":[$slots]}}]}"""
    private fun read(body: String, continued: Boolean = false): ReadResult<SearchPage> {
        var calls = 0
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                calls++
                val query = jsonObject(request.body)!!
                if (continued) assertEquals("issued-search-cursor", query.text("continuation"))
                else assertEquals("fixture search", query.text("query"))
                return HttpReply(200, body)
            }
        }
        return loadSearch(if (continued) null else "fixture search", continuation = if (continued) "issued-search-cursor" else null,
            http = http).also { assertEquals(1, calls) }
    }
    private fun rejected(body: String, continued: Boolean = false) {
        assertEquals("unknown_search_page", read(body, continued).error?.code)
    }

    @Test fun observedFiveAndTwentyShortsGridsRemainOrganicBesideTwentyCardsAndFourOpaqueAdvertisements() {
        // The primary collection contains two independent gridShelfViewModel arrays.
        val ordinary = (0 until 20).map { video("fixture-video-$it") }
        val shorts = (0 until 25).map { short("fixture-short-$it") }
        val advertisement = """{"searchPyvRenderer":{"content":${grid(short("advertised-short"))}}}"""
        val slots = listOf(advertisement) + ordinary.take(10) + grid(shorts.take(5).joinToString(",")) +
            listOf(advertisement, advertisement) + ordinary.drop(10) + grid(shorts.drop(5).joinToString(",")) + advertisement
        assertEquals(26, slots.size)
        val body = page(slots.joinToString(","), next = "issued-next-page")
        val loaded = read(body).value!!
        assertEquals(searchPage(body), loaded)
        assertEquals(45, loaded.items.size)
        assertEquals(20, loaded.items.count { it.kind == "video" })
        assertEquals(25, loaded.items.count { it.kind == "short" })
        assertEquals((0 until 25).map { "fixture-short-$it" }, loaded.items.filter { it.kind == "short" }.map { it.id })
        assertEquals(loaded.items, loaded.groups.single().items)
        assertEquals("issued-next-page", loaded.nextKey)
    }

    @Test fun issuedContinuationAcceptsOnlyTheSameExplicitGridContentsPath() {
        val cards = listOf(short("short-one"), short("short-two"))
        val loaded = read(continuation(grid(cards.joinToString(","))), continued = true).value!!
        assertEquals(listOf("short-one", "short-two"), loaded.items.map { it.id })
        assertTrue(loaded.items.all { it.kind == "short" })
        assertNull(loaded.nextKey)
    }

    @Test fun gridContentsMustBeAnArrayOfKnownUnambiguousCardsEvenBesideAnOrdinaryResult() {
        val malformed = listOf(
            """{"gridShelfViewModel":{}}""", """{"gridShelfViewModel":{"items":[]}}""",
            """{"gridShelfViewModel":{"contents":null}}""", """{"gridShelfViewModel":{"contents":{}}}""",
            """{"gridShelfViewModel":{"contents":"[]"}}""", grid("null"), grid("[]"), grid("{}"),
            grid("""{"futureShortsViewModel":{}}"""),
            grid("""{"shortsLockupViewModel":{"overlayMetadata":{"primaryText":{"content":"No identity"}}}}"""),
            grid(short("known-short") + ", {\"futureShortsViewModel\":{}}"),
            grid(short("known-short").dropLast(1) + ",\"futureRenderer\":{}}"),
            grid(short("known-short")).dropLast(1) + "," + video("parallel").drop(1)
        )
        for (slot in malformed) {
            rejected(page(video("ordinary") + "," + slot))
            rejected(continuation(slot), continued = true)
        }
    }

    @Test fun recognizedGridDoesNotGiveTrustToUnknownAncestorsOrSimilarNames() {
        val nested = grid(short("known-short"))
        for (slot in listOf("""{"futureGridShelfViewModel":{"contents":[${short("known-short")}]}}""",
            """{"futureWrapper":{"content":$nested}}""", """{"futureWrapper":{"content":${grid("")}}}""",
            nested.dropLast(1) + ",\"futureWrapper\":{}}")) {
            rejected(page(slot, estimate = "0"))
            rejected(continuation(slot), continued = true)
        }
    }

    @Test fun advertisingGridChildrenNeverBecomeOrganicItemsGroupsActionsOrPageCursors() {
        val adContents = listOf(short("advertised-short"), cursor("ad-only-cursor"),
            """{"buttonRenderer":{"navigationEndpoint":{"browseEndpoint":{"browseId":"UCadvertised"}}}}""").joinToString(",")
        val ad = """{"searchPyvRenderer":{"content":${grid(adContents)}}}"""
        val empty = read(page(ad, estimate = "0")).value!!
        assertTrue(empty.items.isEmpty()); assertTrue(empty.groups.isEmpty()); assertTrue(empty.actions.isEmpty())
        assertTrue(empty.messages.isEmpty()); assertNull(empty.nextKey)
        val organic = read(page("$ad,${grid(short("organic-short"))}")).value!!
        assertEquals(listOf("organic-short"), organic.items.map { it.id })
        assertTrue(organic.actions.isEmpty()); assertNull(organic.nextKey)
    }
}
