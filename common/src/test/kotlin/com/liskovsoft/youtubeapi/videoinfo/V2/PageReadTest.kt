package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class PageReadTest {
    @Test fun distinguishesUnrecognizedContentFromAnExplicitEmptyPage() {
        var body = "{\"contents\":[{\"futureCardViewModel\":{\"title\":\"Unknown\"}}]}"
        val http = object : HttpClient { override fun execute(request: HttpRequest) = HttpReply(200, body) }
        assertEquals("unknown_search_page", loadSearch("q", http = http).error!!.code)
        assertEquals("unknown_browse_page", loadBrowse("home", http = http).error!!.code)
        body = "{\"contents\":[]}"
        assertTrue(loadBrowse("home", http = http).ok)
        body = """{"contents":{"twoColumnSearchResultsRenderer":{"primaryContents":{"sectionListRenderer":{"contents":[{"messageRenderer":{"text":{"simpleText":"No results"}}}]}}}}}"""
        assertEquals(listOf("No results"), loadSearch("q", http = http).value!!.messages)
        body = "{"
        assertEquals(RequestFailure.PARSE, loadSearch("q", http = http).error!!.kind)
    }
}
