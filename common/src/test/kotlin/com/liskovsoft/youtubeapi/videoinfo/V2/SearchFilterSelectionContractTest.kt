package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class SearchFilterSelectionContractTest {
    @Test fun selectedDisplayOnlyFilterRemainsVisibleWithoutInventingNavigationParameters() {
        val filters = searchFilters("""{"searchFilterGroupRenderer":{"title":{"simpleText":"Type"},"filters":[
            {"searchFilterRenderer":{"label":{"simpleText":"Video"},"status":"FILTER_STATUS_SELECTED"}},
            {"searchFilterRenderer":{"label":{"simpleText":"Playlist"},"navigationEndpoint":{"searchEndpoint":{"params":"opaque%2Bplaylist"}}}},
            {"searchFilterRenderer":{"label":{"simpleText":"Unavailable"},"status":"FILTER_STATUS_DISABLED"}}
        ]}}""")
        assertEquals(2, filters.size)
        val selected = filters.single { it.selected }
        assertEquals("Type", selected.group)
        assertEquals("Video", selected.title)
        assertNull(selected.params)
        val destination = filters.single { !it.selected }
        assertEquals("Playlist", destination.title)
        assertEquals("opaque%2Bplaylist", destination.params)
    }

    @Test fun canonicalizedParametersDoNotReplaceSemanticSelectedState() {
        val response = """{"contents":[{"videoRenderer":{"videoId":"jNQXAC9IVRw","title":{"simpleText":"A reference video"}}}],
            "searchFilterGroupRenderer":{"title":{"simpleText":"Duration"},"filters":[
              {"searchFilterRenderer":{"label":{"simpleText":"Short"},"status":"FILTER_STATUS_SELECTED","navigationEndpoint":{"searchEndpoint":{"params":"server-canonical-value"}}}},
              {"searchFilterRenderer":{"label":{"simpleText":"Long"},"navigationEndpoint":{"searchEndpoint":{"params":"other-navigation-value"}}}}
            ]}}"""
        val requested = SearchFilter("Short", "Duration", "original-opaque-request-value", false)
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                assertTrue(request.url.startsWith("https://www.youtube.com/youtubei/v1/search"))
                assertTrue(request.body.contains("original-opaque-request-value"))
                return HttpReply(200, searchPrimaryWithFilterFixture(response))
            }
        }
        val page = loadSearch("reference", RequestContext(), requested.params, http = http).value!!
        assertEquals("jNQXAC9IVRw", page.items.single().id)
        assertEquals("A reference video", page.items.single().title)
        assertTrue(page.filters.any { it.selected && it.title == requested.title && it.group == requested.group })
        assertNotEquals(requested.params, page.filters.single { it.selected }.params)
    }
}
