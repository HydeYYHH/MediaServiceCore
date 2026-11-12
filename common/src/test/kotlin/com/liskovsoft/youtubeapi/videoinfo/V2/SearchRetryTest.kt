package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class SearchRetryTest {
    @Test fun authenticationFailureReturnsToTheHostWithoutAnAnonymousRetry() {
        val calls = ArrayList<HttpRequest>()
        val http = object : HttpClient { override fun execute(request: HttpRequest): HttpReply {
            calls.add(request)
            return HttpReply(403, "{}")
        }}
        val context = RequestContext(accountId = "account", webSession = WebSession("SAPISID=fixture"), visitor = "visitor")
        val result = loadSearch("q", context, http = http)
        assertEquals(RequestFailure.AUTH, result.error?.kind)
        assertEquals(1, calls.size)
        assertEquals("SAPISID=fixture", calls.single().headers["Cookie"])
    }

    @Test fun continuationsNeverChangeTheirAccountAndSuccessMakesOneRequest() {
        var calls = 0
        val http = object : HttpClient { override fun execute(request: HttpRequest): HttpReply { calls++; return HttpReply(401, "{}") } }
        val context = RequestContext(webSession = WebSession("SAPISID=fixture"))
        assertEquals(RequestFailure.AUTH, loadSearch("q", context, continuation = "cursor", http = http).error?.kind)
        assertEquals(1, calls)
        calls = 0
        assertFalse(loadSearch("q", http = http).ok)
        assertEquals(1, calls)
        calls = 0
        val success = object : HttpClient { override fun execute(request: HttpRequest): HttpReply { calls++; return HttpReply(200, """{"contents":{"twoColumnSearchResultsRenderer":{"primaryContents":{"sectionListRenderer":{"contents":[]}}}}}""") } }
        assertTrue(loadSearch("q", context, http = success).ok)
        assertEquals(1, calls)
    }
}
