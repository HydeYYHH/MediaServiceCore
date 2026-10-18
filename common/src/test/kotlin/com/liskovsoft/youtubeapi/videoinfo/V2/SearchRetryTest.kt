package com.liskovsoft.youtubeapi.videoinfo.V2
import org.junit.Assert.*
import org.junit.Test
class SearchRetryTest {
    @Test fun executesAFreshAnonymousRequestExactlyOnce() {
        val calls = ArrayList<HttpRequest>()
        val http = object : HttpClient { override fun execute(request: HttpRequest): HttpReply {
            calls.add(request)
            return if (calls.size == 1) HttpReply(403, "{}") else HttpReply(200, "{}")
        }}
        assertTrue(loadSearch("q", RequestContext("account", "secret", "visitor"), http = http).ok)
        assertEquals(2, calls.size); assertNotSame(calls[0], calls[1])
        assertEquals("Bearer secret", calls[0].headers["Authorization"]); assertNull(calls[1].headers["Authorization"])
        assertEquals("visitor", calls[1].headers["X-Goog-Visitor-Id"])
    }
    @Test fun doesNotRetrySuccessOrRetryTheAnonymousFailureAgain() {
        var calls = 0
        val http = object : HttpClient { override fun execute(request: HttpRequest): HttpReply { calls++; return HttpReply(403, "{}") } }
        assertFalse(loadSearch("q", RequestContext("a", "s"), http = http).ok); assertEquals(2, calls)
        calls = 0; assertFalse(loadSearch("q", http = http).ok); assertEquals(1, calls)
        calls = 0
        val success = object : HttpClient { override fun execute(request: HttpRequest): HttpReply { calls++; return HttpReply(200, "{}") } }
        assertTrue(loadSearch("q", RequestContext("a", "s"), http = success).ok); assertEquals(1, calls)
    }
}
