package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

/** TV continuation pages may expose multiple independent grid buttons. */
class GridButtonActionContractTest {
    private val buttons = """{"gridButtonRenderer":{"title":{"simpleText":"First server action"},
        "icon":{"iconType":"TEST_ICON"},"trackingParams":"fixture-tracking",
        "navigationEndpoint":{"clickTrackingParams":"fixture-click","clientActionEndpoint":{"clientAction":"TEST_CLIENT_ACTION"}}}},
        {"gridButtonRenderer":{"title":{"simpleText":"Second server action"},"icon":{"iconType":"TEST_ICON"},
        "trackingParams":"fixture-tracking","navigationEndpoint":{"clickTrackingParams":"fixture-click",
        "signalServiceEndpoint":{"actions":[{"clickTrackingParams":"fixture-click","signalAction":{"signal":"TEST_SIGNAL"}}]}}}}"""
    private fun continuation(items: String) = """{"responseContext":{},"continuationContents":{"sectionListContinuation":{
        "trackingParams":"fixture-tracking","contents":[{"shelfRenderer":{"content":{"horizontalListRenderer":{"items":[$items]}}}}]}}}"""
    private class Wire(private val body: String) : HttpClient {
        val requests = ArrayList<HttpRequest>()
        override fun execute(request: HttpRequest): HttpReply { requests += request; return HttpReply(200, body) }
    }
    @Test fun aNonemptyTvButtonContinuationReturnsServerActionsWithoutGuessingAuthenticationOrExecutingThem() {
        val wire = Wire(continuation(buttons))
        val result = loadHome(HomeSection.LIVE, RequestContext(), "issued-home-cursor", wire)
        assertTrue(result.error?.code, result.ok)
        val page = result.value!!
        assertTrue(page.items.isEmpty()); assertNull(page.nextKey)
        assertEquals(listOf("First server action", "Second server action"), page.actions.map { it.title })
        val first = page.actions[0]
        assertEquals("clientActionEndpoint", first.endpoint.name)
        assertEquals("""{"clientAction":"TEST_CLIENT_ACTION"}""", first.endpoint.payload)
        assertNull(first.endpoint.apiUrl); assertNull(first.kind); assertNull(first.kindToken); assertFalse(first.selected)
        val second = page.actions[1]
        assertEquals("signalServiceEndpoint", second.endpoint.name)
        val command = jsonObject(second.endpoint.payload)!!
        assertEquals("TEST_SIGNAL", (command.arr("actions").single() as Json.Obj).obj("signalAction")!!.text("signal"))
        assertNull(second.kind); assertNull(second.kindToken); assertFalse(second.selected)
        assertEquals(1, wire.requests.size)
        val request = wire.requests.single()
        assertEquals("issued-home-cursor", jsonObject(request.body)!!.text("continuation"))
        assertEquals("TVHTML5", jsonObject(request.body)!!.path("context", "client")!!.text("clientName"))
        assertFalse(request.headers.keys.any { it.equals("Cookie", true) || it.equals("Authorization", true) })
    }
    @Test fun missingCommandsAndUnknownRootsRemainParseFailuresRatherThanEmptyPages() {
        for (body in listOf("{}", """{"unknownRenderer":{"items":[]}}""", continuation("""{"gridButtonRenderer":{}}"""),
            continuation("""{"gridButtonRenderer":{"title":{"simpleText":"Title only"},"navigationEndpoint":{}}}"""))) {
            val wire = Wire(body)
            val result = loadHome(HomeSection.LIVE, RequestContext(), "issued-home-cursor", wire)
            assertEquals(body, RequestFailure.PARSE, result.error?.kind)
            assertEquals("unknown_browse_page", result.error?.code)
            assertNull(result.value); assertEquals(1, wire.requests.size)
        }
    }
    @Test fun advertisingGridButtonsDoNotBecomeSearchContentOrSearchPageCapabilities() {
        fun search(estimate: String) = """{$estimate"contents":{"twoColumnSearchResultsRenderer":{"primaryContents":{
            "sectionListRenderer":{"contents":[{"itemSectionRenderer":{"contents":[{"searchPyvRenderer":{
            "content":{"horizontalListRenderer":{"items":[$buttons]}}}}]}}]}}}}}"""
        val explicitZero = search("\"estimatedResults\":\"0\",")
        val loaded = loadSearch("fixture", http = Wire(explicitZero))
        assertTrue(loaded.error?.code, loaded.ok)
        for (page in listOf(loaded.value!!, searchPage(explicitZero))) {
            assertTrue(page.items.isEmpty()); assertTrue(page.actions.isEmpty()); assertTrue(page.groups.isEmpty()); assertNull(page.nextKey)
        }
        val unprovenEmpty = loadSearch("fixture", http = Wire(search("")))
        assertEquals("unknown_search_page", unprovenEmpty.error?.code)
    }
}
