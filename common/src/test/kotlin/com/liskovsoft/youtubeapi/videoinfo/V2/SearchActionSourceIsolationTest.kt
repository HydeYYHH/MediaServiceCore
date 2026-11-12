package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class SearchActionSourceIsolationTest {
    private val video = """{"videoRenderer":{"videoId":"fixture-video"}}"""
    private fun button(id: String = "FEissued", route: String = "/youtubei/v1/browse") =
        """{"buttonRenderer":{"text":{"simpleText":"Issued action"},"isSelected":true,"navigationEndpoint":{
            "commandMetadata":{"webCommandMetadata":{"apiUrl":"$route"}},"browseEndpoint":{"browseId":"$id","params":"issued-params"}}}}"""
    private fun chip(token: String = "issued-chip") = """{"chipCloudChipRenderer":{"text":{"simpleText":"Issued chip"},"isSelected":true,
        "navigationEndpoint":{"commandMetadata":{"webCommandMetadata":{"apiUrl":"/youtubei/v1/search"}},
        "continuationCommand":{"token":"$token","request":"CONTINUATION_REQUEST_TYPE_SEARCH"}}}}"""
    private fun chipHeader(chips: String) = """{"searchHeaderRenderer":{"chipBar":{"chipCloudRenderer":{"chips":[$chips]}}}}"""
    private fun page(slots: String = video, layoutExtra: String = "", rootExtra: String = "", sectionExtra: String = "") =
        """{"contents":{"twoColumnSearchResultsRenderer":{"primaryContents":{"sectionListRenderer":{"contents":[$slots]$sectionExtra}}$layoutExtra}}$rootExtra}"""
    private fun read(body: String, continued: Boolean = false): ReadResult<SearchPage> {
        var calls = 0
        val result = loadSearch(if (continued) null else "fixture query", continuation = if (continued) "issued-cursor" else null,
            http = object : HttpClient {
                override fun execute(request: HttpRequest): HttpReply {
                    calls++
                    assertTrue(request.url.startsWith("https://www.youtube.com/youtubei/v1/search"))
                    return HttpReply(200, body)
                }
            })
        assertEquals(1, calls)
        return result
    }
    private fun native(body: String, continued: Boolean = false): SearchPage {
        val loaded = read(body, continued)
        assertTrue(loaded.error?.code, loaded.ok)
        return loaded.value!!.also { assertEquals(searchPage(body), it) }
    }

    @Test fun fullWebSearchDoesNotBorrowTopbarMetadataOrSidebarButtons() {
        val body = page(rootExtra = """, "topbar":{"desktopTopbarRenderer":{"backButton":${button("FEglobal")}}},
            "responseContext":{"unowned":${button("FEmetadata")}},"sidebar":{"content":${button("FEsidebar")}}""")
        val parsed = native(body)
        assertEquals(listOf("fixture-video"), parsed.items.map { it.id })
        assertTrue(parsed.actions.isEmpty())
    }

    @Test fun popupUiIsNotANetworkCommandAndCannotPromoteItsNestedButtons() {
        val popup = """{"buttonRenderer":{"text":{"simpleText":"Filters"},"command":{"openPopupAction":{"popup":{
            "menuRenderer":{"items":[${button("FEnested")},{"searchFilterGroupRenderer":{"title":{"simpleText":"Type"},"filters":[
                {"searchFilterRenderer":{"label":{"simpleText":"Video"},"navigationEndpoint":{"searchEndpoint":{"params":"issued-filter"}}}}]}}]}}}}}}"""
        val body = page(rootExtra = """, "header":{"searchHeaderRenderer":{"searchFilterButton":$popup}}""")
        val parsed = native(body)
        assertTrue(parsed.actions.isEmpty())
        assertEquals("issued-filter", parsed.filters.single().params)
    }

    @Test fun layoutHeaderNativeActionRetainsExactServerPayloadAndSelection() {
        val action = native(page(layoutExtra = ",\"header\":" + button())).actions.single()
        assertEquals("browseEndpoint", action.endpoint.name)
        assertEquals("/youtubei/v1/browse", action.endpoint.apiUrl)
        assertEquals("FEissued", jsonObject(action.endpoint.payload)!!.text("browseId"))
        assertEquals("issued-params", jsonObject(action.endpoint.payload)!!.text("params"))
        assertTrue(action.selected)
    }

    @Test fun ownedSubmenuPreservesDirectSearchActionButNotDescendantPopupControls() {
        val search = """{"buttonRenderer":{"text":{"simpleText":"Next search"},"serviceEndpoint":{
            "commandMetadata":{"webCommandMetadata":{"apiUrl":"/youtubei/v1/search"}},"searchEndpoint":{"query":"issued query","params":"issued-param"}}}}"""
        val body = page(sectionExtra = """, "subMenu":{"searchSubMenuRenderer":{"button":$search}}""")
        val action = native(body).actions.single()
        assertEquals("searchEndpoint", action.endpoint.name)
        assertEquals("issued query", jsonObject(action.endpoint.payload)!!.text("query"))
    }

    @Test fun actualOwnedHeaderChipRoleKeepsFourNativeCommandsWithoutGlobalOrPopupBorrowing() {
        val chips = (0 until 4).joinToString(",") { chip("issued-$it") }
        val header = chipHeader(chips).replace("\"chips\":[", "\"unownedPresentation\":${button("FEhidden")},\"chips\":[")
        val body = page(rootExtra = ",\"header\":" + header + ",\"topbar\":" + button("FEglobal"))
        val actions = native(body).actions
        assertEquals(4, actions.size)
        assertTrue(actions.all { it.endpoint.name == "continuationCommand" && it.endpoint.apiUrl == "/youtubei/v1/search" && it.selected })
        assertEquals((0 until 4).map { "issued-$it" }, actions.map { jsonObject(it.endpoint.payload)!!.text("token") })
        assertEquals("CONTINUATION_REQUEST_TYPE_SEARCH", jsonObject(actions.first().endpoint.payload)!!.text("request"))
    }

    @Test fun chipCommandsRequireEveryOwnedAncestorRouteAndExclusiveTypedDirectCarrier() {
        val original = chipHeader(chip())
        val foreign = """"commandMetadata":{"webCommandMetadata":{"apiUrl":"/youtubei/v1/browse"}},"""
        val bodies = ArrayList<String>()
        for (ancestor in listOf("chipBar", "chipCloudRenderer", "chipCloudChipRenderer", "navigationEndpoint", "continuationCommand"))
            for (declaration in listOf(foreign, "\"commandMetadata\":\"wrong\","))
                bodies += if (ancestor == "navigationEndpoint") original.replace(
                    "\"commandMetadata\":{\"webCommandMetadata\":{\"apiUrl\":\"/youtubei/v1/search\"}},", declaration)
                else original.replace("\"$ancestor\":{", "\"$ancestor\":{$declaration")
        bodies += original.replace("\"token\":\"issued-chip\"", "\"token\":\"\"")
        bodies += original.replace("\"token\":\"issued-chip\"", "\"token\":false")
        bodies += original.replace("\"navigationEndpoint\":{", "\"command\":{},\"navigationEndpoint\":{")
        bodies += original.replace("\"continuationCommand\":{", "\"browseEndpoint\":{\"browseId\":\"FEforeign\"},\"continuationCommand\":{")
        bodies += original.replace("\"request\":\"CONTINUATION_REQUEST_TYPE_SEARCH\"", "\"request\":{}")
        bodies += original.replace("\"commandMetadata\":{\"webCommandMetadata\":{\"apiUrl\":\"/youtubei/v1/search\"}},", "")
        bodies += original.replace("\"chips\":[", "\"chips\":null,\"ignoredChips\":[")
        for ((ordinal, header) in bodies.withIndex()) {
            val body = page(rootExtra = ",\"header\":" + header)
            assertEquals("counterexample $ordinal", RequestFailure.PARSE, read(body).error?.kind)
            assertTrue(searchPage(body).actions.isEmpty())
        }
        assertEquals(RequestFailure.PARSE, read(page(rootExtra = ",\"header\":" + chipHeader((0 until 129).joinToString(",") { chip("issued-$it") }))).error?.kind)
    }

    @Test fun ownedChipOpaqueCommandCannotLendActionsMenuSemanticsOrAnotherToken() {
        val opaque = """{"commandMetadata":{"webCommandMetadata":{"apiUrl":"https://foreign.test/mutate"}},
            "feedbackEndpoint":{"feedbackToken":"opaque-feedback","uiActions":{"hideEnclosingContainer":true}},
            "continuationCommand":{"token":"nested-token"},"buttonRenderer":${button("FEnested")}}"""
        val source = chip().replace("\"token\":\"issued-chip\"", "\"token\":\"issued-chip\",\"command\":$opaque")
        val parsed = native(page(rootExtra = ",\"header\":" + chipHeader(source)))
        val action = parsed.actions.single()
        assertEquals("continuationCommand", action.endpoint.name)
        assertEquals("/youtubei/v1/search", action.endpoint.apiUrl)
        val payload = jsonObject(action.endpoint.payload)!!
        assertEquals("issued-chip", payload.text("token"))
        assertEquals(jsonObject(opaque)!!.encode(), payload.obj("command")!!.encode())
        assertNull(action.kind)
        assertNull(action.kindToken)
        assertNull(action.feedbackToken())
    }

    @Test fun opaqueChipCommandRequiresObjectAndCannotReplaceTheDirectSearchContract() {
        for (wrong in listOf("null", "false", "0", "\"wrong\"", "[]")) {
            val bad = chip().replace("\"token\":\"issued-chip\"", "\"token\":\"issued-chip\",\"command\":$wrong")
            assertEquals(RequestFailure.PARSE, read(page(rootExtra = ",\"header\":" + chipHeader(bad))).error?.kind)
        }
        val opaque = """{"navigationEndpoint":{"commandMetadata":{"webCommandMetadata":{"apiUrl":"/youtubei/v1/search"}},
            "continuationCommand":{"token":"nested-token","request":"CONTINUATION_REQUEST_TYPE_SEARCH"}}}"""
        val base = chip().replace("\"token\":\"issued-chip\"", "\"token\":\"issued-chip\",\"command\":$opaque")
        for (bad in listOf(base.replace("\"token\":\"issued-chip\"", "\"token\":\"\""),
            base.replace("\"request\":\"CONTINUATION_REQUEST_TYPE_SEARCH\"", "\"request\":false"),
            base.replace("\"apiUrl\":\"/youtubei/v1/search\"", "\"apiUrl\":\"/youtubei/v1/browse\""))) {
            val body = page(rootExtra = ",\"header\":" + chipHeader(bad))
            assertEquals(RequestFailure.PARSE, read(body).error?.kind)
            assertTrue(searchPage(body).actions.isEmpty())
        }
    }

    @Test fun chipLikeContinuationOutsideTheOwnedHeaderBarDoesNotBecomeAPageCommand() {
        val body = page(rootExtra = """, "topbar":${chip()},"responseContext":{"chipBar":${chipHeader(chip())}}""")
        assertTrue(native(body).actions.isEmpty())
        val popup = """{"buttonRenderer":{"command":{"openPopupAction":{"popup":${chipHeader(chip())}}}}}"""
        assertTrue(native(page(rootExtra = """, "header":{"searchHeaderRenderer":{"searchFilterButton":$popup}}""")).actions.isEmpty())
    }

    @Test fun resultCtaIsOwnedButCollectionContinuationControlsAreNotPageCommands() {
        val cta = """{"buttonCardViewModel":{"title":"Issued CTA","rendererContext":{"commandContext":{"onTap":{"innertubeCommand":{
            "browseEndpoint":{"browseId":"FEcta"}}}}}}}"""
        val cursor = """{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"next"}},
            "button":${button("FEcursor-child")}}}"""
        val parsed = native(page("$video,$cta,$cursor"))
        assertEquals("FEcta", jsonObject(parsed.actions.single().endpoint.payload)!!.text("browseId"))
        assertEquals("next", parsed.nextKey)
    }

    @Test fun issuedContinuationKeepsItsOwnHeaderActionAndExcludesGlobalUi() {
        val body = """{"onResponseReceivedCommands":[{"appendContinuationItemsAction":{"continuationItems":[$video]}}],
            "header":${button()},"topbar":{"content":${button("FEglobal")}}}"""
        assertEquals(1, native(body, true).actions.size)
    }

    @Test fun pureHeaderReloadDoesNotChangeTheExistingNetworkEnvelopeContract() {
        val body = """{"onResponseReceivedCommands":[{"reloadContinuationItemsCommand":{"slot":"RELOAD_CONTINUATION_SLOT_HEADER",
            "continuationItems":[{"searchSubMenuRenderer":{"button":${button()}}}]}},
            {"appendContinuationItemsAction":{"continuationItems":[$video]}}]}"""
        assertEquals(1, searchPage(body).actions.size)
        assertEquals("unknown_search_page", read(body, true).error?.code)
    }

    @Test fun onlyExplicitLegacyFragmentsKeepBroadFragmentActionCompatibility() {
        assertEquals(1, searchPage(button()).actions.size)
        assertEquals(1, searchPage("""{"contents":[${button()}]}""").actions.size)
        for (body in listOf("""{"topbar":${button()}}""", """{"responseContext":${button()}}""",
            """{"futureDocument":{"contents":[${button()}]}}""")) assertTrue(searchPage(body).actions.isEmpty())
    }

    @Test fun foreignAncestorAndCompetingOrMalformedCommandCarriersFailWithoutFallback() {
        val malformed = button().replace("\"navigationEndpoint\":{", "\"serviceEndpoint\":\"wrong\",\"navigationEndpoint\":{")
        val foreignAncestor = """{"commandMetadata":{"webCommandMetadata":{"apiUrl":"/youtubei/v1/like/like"}},"buttonRenderer":${jsonObject(button())!!.obj("buttonRenderer")!!.encode()}}"""
        for (header in listOf(malformed, foreignAncestor, button(route = "/youtubei/v1/search"),
            button(route = "https://foreign.test/youtubei/v1/browse"))) {
            val body = page(layoutExtra = ",\"header\":" + header)
            assertEquals(RequestFailure.PARSE, read(body).error?.kind)
            assertTrue(searchPage(body).actions.isEmpty())
        }
    }

    @Test fun contextualResultCtaKeepsEveryDeclaredAncestorRouteAndType() {
        val metadata = """"commandMetadata":{"webCommandMetadata":{"apiUrl":"/youtubei/v1/like/like"}},"""
        for (ancestor in listOf("rendererContext", "commandContext", "onTap")) for (declaration in listOf(metadata, "\"commandMetadata\":\"wrong\",")) {
            val cta = """{"buttonCardViewModel":{"title":"Issued CTA","rendererContext":{"commandContext":{"onTap":{"innertubeCommand":{
                "browseEndpoint":{"browseId":"FEcta"}}}}}}}"""
                .replace("\"$ancestor\":{", "\"$ancestor\":{$declaration")
            val body = page("$video,$cta")
            assertEquals(RequestFailure.PARSE, read(body).error?.kind)
            assertTrue(searchPage(body).actions.isEmpty())
        }
    }

    @Test fun ambiguousOrUnknownOwnedHeaderCannotFallBackToGlobalActions() {
        val headers = listOf("""{"searchHeaderRenderer":{"futureControl":${button()}}}""",
            """{"searchHeaderRenderer":{"button":${button()},"filterButton":${button()}}}""",
            """{"buttonRenderer":{},"gridButtonRenderer":{}}""")
        for (header in headers) {
            val body = page(layoutExtra = ",\"header\":" + header, rootExtra = """, "topbar":${button("FEglobal")}""")
            assertEquals("unknown_search_action_source", read(body).error?.code)
            assertTrue(searchPage(body).actions.isEmpty())
        }
    }

    @Test fun adOnlyTerminalPageDoesNotAcquireActionsFromAdsOrGlobalUi() {
        val ad = """{"searchPyvRenderer":{"content":${button("FEad")}}}"""
        val body = page(ad, rootExtra = """, "estimatedResults":"0","topbar":${button("FEglobal")}""")
        val parsed = native(body)
        assertTrue(parsed.items.isEmpty())
        assertTrue(parsed.actions.isEmpty())
    }

    @Test fun unknownResultSlotsKeepTheirExistingBusinessParseFailure() {
        assertEquals("unknown_search_page", read(page("""{"futureResultRenderer":{}}""")).error?.code)
    }

    @Test fun legacyMetadataOnlyEndRetainsItsExistingEmptyContractWithoutBorrowingActions() {
        val body = """{"responseContext":{"hiddenUi":${button("FEhidden")}},"trackingParams":"tracking"}"""
        val parsed = native(body, true)
        assertTrue(parsed.actions.isEmpty())
        assertTrue(parsed.items.isEmpty())
        assertNull(parsed.nextKey)
        assertEquals("unknown_search_page", read(body).error?.code)
    }

    @Test fun ownedHeaderAndSlotBudgetsFailClosed() {
        val buttons = (0 until 33).joinToString(",") { button("FE$it") }
        val header = """{"searchSubMenuRenderer":{"buttons":[$buttons]}}"""
        assertEquals("unknown_search_action_source", read(page(sectionExtra = ",\"subMenu\":" + header)).error?.code)
        var nested = button()
        repeat(34) { nested = """{"searchHeaderRenderer":{"button":$nested}}""" }
        assertEquals("unknown_search_action_source", read(page(layoutExtra = ",\"header\":" + nested)).error?.code)
    }
}
