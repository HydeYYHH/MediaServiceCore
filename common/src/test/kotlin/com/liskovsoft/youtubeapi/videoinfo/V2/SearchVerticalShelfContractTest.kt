package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class SearchVerticalShelfContractTest {
    private fun video(id: String) = """{"videoRenderer":{"videoId":"$id","title":{"simpleText":"Fixture video"}}}"""
    private fun shelf(content: String) = """{"shelfRenderer":{"title":{"simpleText":"Fixture shelf"},"content":$content}}"""
    private fun vertical(items: String) = """{"verticalListRenderer":{"items":[$items]}}"""
    private fun cursor(token: String) = """{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"$token"}}}}"""
    private fun page(slots: String) = """{"estimatedResults":"784100","contents":{"twoColumnSearchResultsRenderer":{
        "primaryContents":{"sectionListRenderer":{"contents":[{"itemSectionRenderer":{"contents":[$slots]}}]}}}}}"""
    private fun continued(slots: String) = """{"onResponseReceivedCommands":[{
        "appendContinuationItemsAction":{"continuationItems":[$slots]}}]}"""
    private fun read(body: String, continuation: Boolean = false): ReadResult<SearchPage> {
        var calls = 0
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                calls++
                val query = jsonObject(request.body)!!
                if (continuation) assertEquals("issued-search-cursor", query.text("continuation"))
                else assertEquals("fixture search", query.text("query"))
                return HttpReply(200, body)
            }
        }
        return loadSearch(if (continuation) null else "fixture search",
            continuation = if (continuation) "issued-search-cursor" else null, http = http)
            .also { assertEquals(1, calls) }
    }
    private fun rejected(slot: String) {
        for ((body, continuation) in listOf(page(video("ordinary") + "," + slot) to false, continued(slot) to true))
            assertEquals("unknown_search_page", read(body, continuation).error?.code)
    }

    @Test fun observedTenItemVerticalShelfPreservesNativeCardsBesideHorizontalAndGridShelves() {
        // Vertical and horizontal shelves expose their own item lists.
        // Item identities below are synthetic; no unobserved item kind is granted trust.
        val verticalVideos = (0 until 10).map { video("vertical-$it") }
        val horizontalVideos = (0 until 11).map { video("horizontal-$it") }
        val horizontal = """{"horizontalListRenderer":{"items":[${horizontalVideos.joinToString(",")}]}}"""
        val grid = """{"gridShelfViewModel":{"contents":[${(0 until 3).joinToString(",") { video("grid-$it") }}]}}"""
        val body = page(listOf(shelf(vertical(verticalVideos.joinToString(","))), shelf(horizontal), grid, video("ordinary")).joinToString(","))
        val result = read(body)
        assertTrue(result.error?.code, result.ok)
        val loaded = result.value!!
        assertEquals(searchPage(body), loaded)
        assertEquals((0 until 10).map { "vertical-$it" } + (0 until 11).map { "horizontal-$it" } +
            (0 until 3).map { "grid-$it" } + "ordinary", loaded.items.map { it.id })
        assertEquals(25, loaded.items.size)
        assertTrue(loaded.items.all { it.kind == "video" && it.title == "Fixture video" })
        assertNull(loaded.nextKey)
    }

    @Test fun issuedContinuationConsumesTheSameExplicitVerticalItemsPathAndItsNativeCursor() {
        val loaded = read(continued(shelf(vertical(video("first") + "," + video("second") + "," + cursor("issued-next")))), true).value!!
        assertEquals(listOf("first", "second"), loaded.items.map { it.id })
        assertNull(loaded.nextKey)
        assertEquals("issued-next", loaded.groups.single().nextKey)
    }

    @Test fun verticalShelfRejectsMalformedArraysUnknownItemsAndParallelWrappers() {
        for (content in listOf(
            """{"verticalListRenderer":{}}""", """{"verticalListRenderer":{"contents":[]}}""",
            """{"verticalListRenderer":{"items":null}}""", """{"verticalListRenderer":{"items":{}}}""",
            """{"verticalListRenderer":{"items":"[]"}}""", vertical("null"), vertical("[]"), vertical("{}"),
            vertical("""{"futureRenderer":{}}"""), vertical(video("valid") + ",{\"postRenderer\":{}}"),
            vertical(video("valid").dropLast(1) + ",\"futureRenderer\":{}}"),
            vertical(video("valid")).dropLast(1) + ",\"videoRenderer\":{\"videoId\":\"parallel\"}}"))
            rejected(shelf(content))
    }

    @Test fun knownVerticalWrapperNeverGrantsTrustToUnknownAncestorsOrUnsupportedSiblingContent() {
        rejected("""{"futureWrapper":{"content":${vertical(video("hidden"))}}}""")
        rejected(shelf("""{"futureVerticalListRenderer":{"items":[${video("hidden")}]}}"""))
        rejected(shelf(vertical(video("valid"))).dropLast(1) + ",\"futureRenderer\":{}}")
        // The bounded real projection did not expose the horizontal shelf's direct item kinds.
        // Observing global postRenderer counts cannot justify silently dropping such a slot.
        rejected(shelf(vertical(video("valid"))) + "," + shelf("""{"horizontalListRenderer":{"items":[{"postRenderer":{}}]}}"""))
    }

    @Test fun advertisementsContainingVerticalShelvesNeverProduceItemsActionsOrContinuations() {
        val hidden = shelf(vertical(video("advertised") + "," + cursor("ad-next") +
            ",\"invalid-ad-item\",{\"buttonRenderer\":{\"navigationEndpoint\":{\"browseEndpoint\":{\"browseId\":\"UCadvertised\"}}}}"))
        val ad = """{"searchPyvRenderer":{"content":$hidden}}"""
        val loaded = read(page(ad + "," + shelf(vertical(video("organic"))))).value!!
        assertEquals(listOf("organic"), loaded.items.map { it.id })
        assertTrue(loaded.groups.flatMap { it.items }.all { it.id == "organic" })
        assertTrue(loaded.actions.isEmpty())
        assertNull(loaded.nextKey)
    }
}
