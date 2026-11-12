package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class SearchPrimaryBoundaryTest {
    private val organic = """{"videoRenderer":{"videoId":"organic","title":{"simpleText":"Organic"},
        "menu":{"menuRenderer":{"items":[{"menuServiceItemRenderer":{"text":{"simpleText":"Keep"},
        "serviceEndpoint":{"feedbackEndpoint":{"feedbackToken":"organic-command"}}}}]}}}}"""
    // These outer slots contain search results; inner advertising UI is opaque.
    private val advertisement = """{"searchPyvRenderer":{"adSlotRenderer":{"content":{
        "itemSectionRenderer":{"title":{"simpleText":"Ad shelf"},"contents":[$organic,
        {"buttonRenderer":{"navigationEndpoint":{"browseEndpoint":{"browseId":"UCad"}}}},
        {"messageRenderer":{"text":{"simpleText":"Ad message"}}},
        {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"ad-cursor"}}}}]}}}}}"""
    private fun page(slots: String, estimate: String? = null, layout: String = "twoColumnSearchResultsRenderer", direct: Boolean = false): String {
        val list = """{"sectionListRenderer":{"contents":[{"itemSectionRenderer":{"contents":[$slots]}}]}}"""
        return """{${estimate?.let { "\"estimatedResults\":$it," }.orEmpty()}"contents":{"$layout":${if (direct) list else "{\"primaryContents\":$list}"}}}"""
    }
    private fun read(body: String, continuation: Boolean = false): ReadResult<SearchPage> {
        val http = object : HttpClient { override fun execute(request: HttpRequest) = HttpReply(200, body) }
        return loadSearch(if (continuation) null else "filter query", continuation = if (continuation) "issued-cursor" else null, http = http)
    }
    private fun rejected(body: String, continuation: Boolean = false) {
        assertEquals(body, "unknown_search_page", read(body, continuation).error?.code)
    }

    @Test fun exactZeroAndOnlyKnownAdvertisingSlotsProduceAnOrganicEmptyPageWithoutAdvertisingCapabilities() {
        for (estimate in listOf("\"0\"", "0")) {
            val body = page(listOf(advertisement, advertisement, advertisement).joinToString(","), estimate)
            val result = read(body)
            assertTrue(result.error?.code, result.ok)
            for (value in listOf(result.value!!, searchPage(body))) {
                assertTrue(value.items.isEmpty()); assertTrue(value.groups.isEmpty()); assertTrue(value.actions.isEmpty())
                assertTrue(value.messages.isEmpty()); assertNull(value.nextKey)
            }
        }
    }

    @Test fun advertisementOnlyNeedsAnExactNonnegativeIntegerZeroAndCannotEndAnIssuedOrganicCursor() {
        for (estimate in listOf(null, "\"1\"", "1", "-1", "0.0", "\"0.0\"", "\"-0\"", "\"00\"", "false", "null", "{}"))
            rejected(page(advertisement, estimate))
        rejected(page("$advertisement,{\"continuationItemRenderer\":{\"continuationEndpoint\":{\"continuationCommand\":{\"token\":\"organic-next\"}}}}", "\"0\""))
    }

    @Test fun unknownAndMalformedPrimarySlotsNeverGainTrustFromAnEstimateOrFromNestedEmptyLists() {
        for (slot in listOf("null", "[]", "{}", "{\"futureResultViewModel\":{}}",
            "{\"futureResultViewModel\":{\"sectionListRenderer\":{\"contents\":[]}}}",
            "{\"futureResultViewModel\":{\"itemSectionRenderer\":{\"contents\":[]}}}",
            "{\"itemSectionRenderer\":{\"contents\":{}}}",
            "{\"itemSectionRenderer\":{\"contents\":[]},\"trackingParams\":{\"futureResultViewModel\":{}}}",
            "{\"videoRenderer\":{\"title\":{\"simpleText\":\"Missing identity\"}}}",
            "{\"continuationItemRenderer\":{}}", "{\"searchPyvRenderer\":null}")) {
            rejected(page(slot, "\"0\""))
            rejected(page("$advertisement,$slot", "\"0\""))
            rejected(page("$organic,$slot", "\"0\""))
        }
        val parallel = advertisement.dropLast(1) + "," + organic.drop(1)
        rejected(page(parallel, "\"0\""))
        rejected(page("{\"searchPyvRenderer\":{},\"futureResultViewModel\":{}}", "\"0\""))
    }

    @Test fun organicResultsKeepTheirGroupsAndCommandsAndDoNotRequireAnEstimate() {
        for ((layout, direct) in listOf("twoColumnSearchResultsRenderer" to false, "singleColumnSearchResultsRenderer" to true)) {
            val loaded = read(page("$advertisement,$organic", layout = layout, direct = direct)).value!!
            assertEquals(listOf("organic"), loaded.items.map { it.id })
            assertEquals(listOf("organic"), loaded.groups.single().items.map { it.id })
            assertEquals("organic-command", jsonObject(loaded.items.single().actions.single().endpoint.payload)!!.text("feedbackToken"))
            assertNull(loaded.nextKey)
        }
    }

    @Test fun explicitEmptyAndMessagePagesAreRecognizedOnlyInTheRealPrimaryEnvelope() {
        for (body in listOf(page(""), """{"contents":{"twoColumnSearchResultsRenderer":{"primaryContents":{"sectionListRenderer":{"contents":[]}}}}}"""))
            assertTrue(read(body).value!!.items.isEmpty())
        val message = read(page("""{"messageRenderer":{"text":{"simpleText":"No results"}}}""")).value!!
        assertEquals(listOf("No results"), message.messages)
        for (body in listOf("""{"estimatedResults":"0"}""", """{"sectionListRenderer":{"contents":[]}}""",
            """{"contents":[]}""", """{"messageRenderer":{"text":{"simpleText":"No results"}}}""",
            """{"future":{"contents":{"twoColumnSearchResultsRenderer":{"primaryContents":{"sectionListRenderer":{"contents":[]}}}}}}""")) rejected(body)
        val both = """{"contents":{"twoColumnSearchResultsRenderer":{"primaryContents":{"sectionListRenderer":{"contents":[]}},"sectionListRenderer":{"contents":[]}}}}"""
        rejected(both)
        rejected("""{"contents":{"twoColumnSearchResultsRenderer":{"primaryContents":{},"sectionListRenderer":{"contents":[]}}}}""")
        rejected("""{"contents":{"twoColumnSearchResultsRenderer":{"primaryContents":{"sectionListRenderer":{"contents":[]}},"sectionListRenderer":false}}}""")
    }

    @Test fun continuationUnknownRowsCannotUseNestedEmptyOrAdvertisingActionsAsAnEndMarker() {
        fun continuation(slots: String) = """{"estimatedResults":"0","onResponseReceivedCommands":[{"appendContinuationItemsAction":{"continuationItems":[$slots]}}]}"""
        val next = read(continuation(organic), true).value!!
        assertEquals("organic", next.items.single().id)
        assertTrue(read(continuation(""), true).value!!.items.isEmpty())
        assertTrue(read(continuation(advertisement), true).value!!.actions.isEmpty())
        rejected(continuation("{\"futureResultViewModel\":{\"sectionListRenderer\":{\"contents\":[]}}}"), true)
        rejected(continuation("$advertisement,{\"futureResultViewModel\":{}}"), true)
    }

    @Test fun slotAndDepthLimitsFailClosedInsteadOfAcceptingAPartialEmptyProjection() {
        rejected(page(List(2050) { "{\"searchPyvRenderer\":{}}" }.joinToString(","), "\"0\""))
        var nested = "{\"itemSectionRenderer\":{\"contents\":[]}}"
        repeat(18) { nested = "{\"itemSectionRenderer\":{\"contents\":[$nested]}}" }
        rejected(page(nested, "\"0\""))
    }
}
