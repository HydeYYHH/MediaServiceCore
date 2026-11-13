package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class SearchAdvertisingContinuationTest {
    private val hidden = """{"videoRenderer":{"videoId":"advertised"}}"""
    private val advertisement = """{"searchPyvRenderer":{"ads":[{"content":$hidden,
        "navigationEndpoint":{"searchEndpoint":{"query":"advertised-query","params":"advertised-filter"}},
        "continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"advertised-next"}}}}],
        "trackingParams":"advertising-tracking","commandMetadata":{"webCommandMetadata":{"apiUrl":"/youtubei/v1/browse"}}}}"""
    private fun body(slots: String = List(4) { advertisement }.joinToString(","), target: String? = "\"search-feed\"",
        estimate: String? = "\"100\"", extraCommand: String = ""): String {
        val targetField = target?.let { "\"targetId\":$it," }.orEmpty()
        val estimateField = estimate?.let { "\"estimatedResults\":$it," }.orEmpty()
        return """{"responseContext":{},${estimateField}"trackingParams":"tracking","isSafeSearchTriggered":false,
            "onResponseReceivedCommands":[{"clickTrackingParams":"append-tracking","appendContinuationItemsAction":{
            ${targetField}"continuationItems":[{"itemSectionRenderer":{"contents":[$slots],"trackingParams":"section-tracking"}}]}},
            {"clickTrackingParams":"ads-tracking","adsControlFlowOpportunityReceivedCommand":{}}$extraCommand]}"""
    }
    private fun read(body: String): ReadResult<SearchPage> = loadSearch(null, continuation = "issued-next",
        http = object : HttpClient { override fun execute(request: HttpRequest): HttpReply {
            assertEquals("issued-next", jsonObject(request.body)?.text("continuation"))
            return HttpReply(200, body)
        } })
    private fun assertEmpty(page: SearchPage) {
        assertTrue(page.items.isEmpty()); assertTrue(page.groups.isEmpty()); assertTrue(page.messages.isEmpty())
        assertTrue(page.actions.isEmpty()); assertTrue(page.filters.isEmpty()); assertNull(page.nextKey)
    }

    @Test fun observedSearchFeedAdvertisingAppendEndsOrganicResultsWithoutExposingAdvertisingCapabilities() {
        for (estimate in listOf("\"100\"", "100", null)) {
            val body = body(estimate = estimate)
            val result = read(body)
            assertTrue(result.error?.code, result.ok)
            assertEmpty(result.value!!)
            assertEmpty(searchPage(body))
        }
    }

    @Test fun advertisingEndRequiresTheOwnedSearchFeedAndCannotNominateAnOpeningPage() {
        for (target in listOf(null, "\"comments-section\"", "null", "false")) {
            val result = read(body(target = target))
            assertEquals("unknown_search_page", result.error?.code)
            assertNull(result.value)
        }
        val initial = loadSearch("query", http = object : HttpClient {
            override fun execute(request: HttpRequest) = HttpReply(200, body())
        })
        assertEquals("unknown_search_page", initial.error?.code)
        val unrelated = """{"responseContext":{},"estimatedResults":"100","onResponseReceivedCommands":[
            {"adsControlFlowOpportunityReceivedCommand":{"content":$hidden}}]}"""
        assertEquals("unknown_search_page", read(unrelated).error?.code)
    }

    @Test fun knownAdvertisingDoesNotHideUnknownOrganicRowsOrClaimAnIssuedOrganicCursorEnded() {
        val cursor = """{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"organic-next"}}}}"""
        for (slots in listOf("$advertisement,{\"futureResultViewModel\":{}}", "$advertisement,$cursor",
            "{\"searchPyvRenderer\":null}", "{\"searchPyvRenderer\":{},\"futureResultViewModel\":{}}")) {
            assertEquals("unknown_search_page", read(body(slots)).error?.code)
        }
        val duplicate = """,{"appendContinuationItemsAction":{"targetId":"search-feed","continuationItems":[]}}"""
        assertEquals("unknown_search_page", read(body(extraCommand = duplicate)).error?.code)
    }

    @Test fun advertisingEndCannotOverrideForeignOrMalformedRouteDeclarations() {
        for (route in listOf("\"/youtubei/v1/browse\"", "\"https://foreign.test/youtubei/v1/search\"", "false")) {
            val declaration = "\"commandMetadata\":{\"webCommandMetadata\":{\"apiUrl\":$route}}"
            val body = body().replace("\"targetId\":\"search-feed\"", "\"targetId\":\"search-feed\",$declaration")
            assertEquals("unknown_search_page", read(body).error?.code)
        }
        for (declaration in listOf("\"commandMetadata\":null", "\"apiUrl\":\"/youtubei/v1/browse\"")) {
            val body = body().replace("\"responseContext\":{}", "\"responseContext\":{},$declaration")
            assertEquals("unknown_search_page", read(body).error?.code)
        }
        for (declaration in listOf("\"apiUrl\":\"/youtubei/v1/browse\"", "\"commandMetadata\":null",
            "\"commandMetadata\":{\"webCommandMetadata\":{\"apiUrl\":\"https://foreign.test/youtubei/v1/search\"}}")) {
            val body = body().replace("\"itemSectionRenderer\":{", "\"itemSectionRenderer\":{$declaration,")
            assertEquals("unknown_search_page", read(body).error?.code)
        }
        val declaredSearch = body().replace("\"itemSectionRenderer\":{", "\"itemSectionRenderer\":{\"apiUrl\":\"/youtubei/v1/search\",")
        assertEmpty(read(declaredSearch).value!!)
    }
}
