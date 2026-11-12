package com.liskovsoft.youtubeapi.videoinfo.V2
import org.junit.Assert.*
import org.junit.Test
class SearchSuggestTest {
    @Test fun readsBothSuggestionShapesAndKeepsEncoding() {
        assertEquals(listOf("one","two"), searchSuggestions("""["q",[["one",0],["two",0],["one",0]]]"""))
        assertEquals(listOf("a"), searchSuggestions("""["q",["a"]]""")); assertNull(searchSuggestions("bad"))
        assertEquals(listOf("one"), searchSuggestions("""window.google.ac.h(["q",[["one",0]]]);"""))
        assertNull(searchSuggestions("""window.google.ac.h(["q",["one"]]);alert(1);"""))
        assertNull(searchSuggestions("""otherCallback(["q",["one"]])"""))
        val http = object : HttpClient { override fun execute(request: HttpRequest): HttpReply {
            assertEquals("你好 & music", urlParameter(request.url, "q")); assertFalse(request.headers.containsKey("Authorization"))
            return HttpReply(200, "[\"q\",[]]")
        }}
        assertTrue(loadSearchSuggestions("你好 & music", RequestContext(webSession = WebSession("SAPISID=s")), http).ok)
    }
    @Test fun preservesOpaqueFilterParameters() {
        val filters = searchFilters("""{"searchFilterGroupRenderer":{"title":{"simpleText":"Sort"},"filters":[{"searchFilterRenderer":{"label":{"simpleText":"Date"},"status":"FILTER_STATUS_SELECTED","navigationEndpoint":{"searchEndpoint":{"params":"opaque%2B"}}}}]}}""")
        assertEquals("opaque%2B", filters.single().params); assertTrue(filters.single().selected)
    }
}
