package com.liskovsoft.youtubeapi.videoinfo.V2

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class SearchFilterStateTest {
    @Test fun officialWebEnumsRetainTheWholeCumulativeSelectionWithoutDependingOnLabels() {
        val filters = listOf(8, 5, 16, 1, 24, 4, 32, 1, 40, 1, 48, 1, 56, 1, 64, 1, 72, 1, 112, 1, 120, 1,
            184, 1, 1, 200, 1, 1, 208, 1, 1).map(Int::toByte).toByteArray()
        val params = Base64.getEncoder().encodeToString(byteArrayOf(8, 3, 18, filters.size.toByte()) + filters)
        val state = requireNotNull(searchFilterState(params))
        assertEquals(SearchFilterType.VIDEO, state.type)
        assertEquals(SearchFilterDate.THIS_YEAR, state.uploaded)
        assertEquals(SearchFilterDuration.UNDER_THREE_MINUTES, state.duration)
        assertEquals(SearchFilterOrder.POPULARITY, state.order)
        assertEquals(SearchFilterFeature.entries.toSet(), state.features)
        assertEquals(state, searchFilterState(params.replace("=", "%3D")))
        assertEquals(SearchFilterType.SHORTS, searchFilterState("EgIQCQ==")?.type)
        assertEquals(SearchFilterDuration.THREE_TO_TWENTY_MINUTES, searchFilterState("EgIYBQ==")?.duration)
        assertEquals(SearchFilterDuration.OVER_TWENTY_MINUTES, searchFilterState("EgIYAg==")?.duration)
        assertEquals(SearchFilterState(), searchFilterState(null))
    }

    @Test fun unknownConflictingAndMalformedWireStatesStayOpaqueInsteadOfBecomingDefaults() {
        fun encoded(vararg bytes: Int) = Base64.getEncoder().encodeToString(bytes.map(Int::toByte).toByteArray())
        for (params in listOf("", "not-base64", encoded(18, 2, 16, 99), encoded(18, 4, 16, 1, 16, 2),
            encoded(8, 3, 8, 3), encoded(18, 2, 32, 2), encoded(18, 2, 136, 1), encoded(18, 2, 15, 0),
            encoded(18, 8, 16, 1), encoded(18, 2, 10, 0), encoded(8, 1), encoded(24, 1))) {
            assertNull("Unexpected semantic state for $params", searchFilterState(params))
        }
    }

    @Test fun queryBoundFiltersCannotBeLaunderedIntoAnotherSearchButUnknownSemanticOptionsRemainOwned() {
        var calls = 0
        val valid = root("rose", "opaque-new-format")
        val http = wire { calls++; HttpReply(200, valid) }
        val catalog = CatalogSession(AccountSession(), http)
        val page = catalog.search("rose").value!!
        val filter = page.filters.single()
        assertTrue(filter.available)
        assertNull(filter.state)
        assertTrue(catalog.filter(filter).ok)
        assertEquals(2, calls)
        val foreign = loadSearch("other", http = wire { HttpReply(200, valid) })
        assertEquals("search_filter_query_mismatch", foreign.error?.code)
        assertNull(foreign.value)
    }

    @Test fun localizedGroupsKeepTheirOwnIndexAndTypedEndpointsDoNotReadResultTreeDecoys() {
        val group = """{"searchFilterGroupRenderer":{"title":{"simpleText":"任意名称"},"filters":[
            {"searchFilterRenderer":{"label":{"simpleText":"任意选项"},"navigationEndpoint":{"searchEndpoint":{"query":"rose","params":"EgIQCQ=="}}}}]}}"""
        val response = """{"header":{"searchHeaderRenderer":{"searchFilterButton":{"buttonRenderer":{"command":{"openPopupAction":{
            "popup":{"searchFilterOptionsDialogRenderer":{"groups":[$group,$group]}}}}}}}},
            "contents":{"twoColumnSearchResultsRenderer":{"primaryContents":{"sectionListRenderer":{"contents":[
                {"itemSectionRenderer":{"contents":[{"videoRenderer":{"videoId":"aaaaaaaaaaa","diagnostic":$group}}]}}
            ]}}}}}"""
        val page = loadSearch("rose", http = wire { HttpReply(200, response) }).value!!
        assertEquals(2, page.filters.size)
        assertEquals(listOf(0, 1), page.filters.map { it.groupIndex })
        assertTrue(page.filters.all { it.state?.type == SearchFilterType.SHORTS })
    }

    private fun wire(response: () -> HttpReply) = object : HttpClient {
        override fun execute(request: HttpRequest) = response()
    }

    private fun root(query: String, params: String) = """{
        "header":{"searchHeaderRenderer":{"searchFilterButton":{"buttonRenderer":{"command":{"openPopupAction":{
            "popup":{"searchFilterOptionsDialogRenderer":{"groups":[{"searchFilterGroupRenderer":{
                "title":{"simpleText":"类型"},"filters":[{"searchFilterRenderer":{"label":{"simpleText":"选项"},
                "navigationEndpoint":{"searchEndpoint":{"query":"$query","params":"$params"}}}}]
            }}]}}}}}}}},
        "contents":{"twoColumnSearchResultsRenderer":{"primaryContents":{"sectionListRenderer":{"contents":[
            {"itemSectionRenderer":{"contents":[{"videoRenderer":{"videoId":"aaaaaaaaaaa"}}]}}
        ]}}}}
    }"""
}
