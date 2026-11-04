package com.liskovsoft.youtubeapi.videoinfo.V2
import org.junit.Assert.*
import org.junit.Test
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

class RequestContextTest {
    @Test fun visitorIsConsistentBetweenTheClientBodyAndHeader() {
        val query = contextualQuery(playerQuery(PlayerClient.TV, "video", "old"), RequestContext(visitor = "paired"))
        assertEquals("paired", query.headers["X-Goog-Visitor-Id"])
        assertEquals("paired", jsonObject(query.body)?.path("context", "client")?.text("visitorData"))
    }
    @Test fun visitorCookiesStayOnAnonymousApiRequests() {
        val context = RequestContext(visitor = "visitor", visitorCookie = "VISITOR_INFO1_LIVE=cookie")
        val query = homeQuery(context = context)
        assertEquals(context.visitorCookie, query.headers["Cookie"])
        assertNull(contextualQuery(query, context.copy(accessToken = "account")).headers["Cookie"])
        assertNull(contextualQuery(query.copy(url = "https://example.com/youtubei/v1/browse"), context).headers["Cookie"])
        assertNotEquals(context.scope(), context.copy(visitorCookie = "other").scope())
        assertFalse(context.toString().contains("cookie"))
    }
    @Test fun keepsAndIsolatesIdentity() {
        val c = RequestContext("a", "secret", "v", "zh", "CN")
        val q = youtubeQuery(YOUTUBE_API + "browse", payload("browseId" to "home"), c)
        assertEquals("Bearer secret", q.headers["Authorization"])
        assertEquals("zh", jsonObject(q.body)!!.path("context", "client")!!.text("hl"))
        assertNull(youtubeQuery(YOUTUBE_API + "browse", "", c.anonymous()).headers["Authorization"])
        assertNull(contextualQuery(q,c.anonymous()).headers["Authorization"])
        assertFalse(c.toString().contains("secret"))
        assertNotEquals(c.scope(), c.copy(accountId = "b").scope())
        assertNull(contextualQuery(q.copy(url = "https://example.com/youtubei/x", headers = emptyMap()), c).headers["Authorization"])
    }
    @Test fun distinguishesErrorsFromEmptyValues() {
        fun call(reply: HttpReply) = requestObject(PlayerQuery("https://x", emptyMap(), "{}"), object : HttpClient { override fun execute(request: HttpRequest) = reply })
        assertTrue(call(HttpReply(200, "{}")).ok)
        assertEquals(RequestFailure.PARSE, call(HttpReply(200, "oops")).error!!.kind)
        assertEquals(2000L, call(HttpReply(429, "{}", mapOf("retry-after" to "2"))).error!!.retryAfterMs)
        assertEquals(RequestFailure.AUTH, call(HttpReply(200, "{\"error\":{\"code\":401,\"status\":\"UNAUTHENTICATED\"}}")).error!!.kind)
    }
    @Test fun defaultHttpKeepsHeadersAndDoesNotFollowRedirects() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var agent: String? = null
        server.createContext("/") { e -> agent = e.requestHeaders.getFirst("User-Agent"); e.responseHeaders.add("Retry-After", "3"); e.responseHeaders.add("Location", "/next"); e.sendResponseHeaders(302, -1); e.close() }
        server.start()
        try {
            val reply = JvmHttpClient().execute(HttpRequest("GET", "http://127.0.0.1:${server.address.port}/"))
            assertEquals(302, reply.status)
            assertEquals("3", reply.header("retry-after"))
            assertEquals("MediaServiceCore", agent)
        } finally { server.stop(0) }
    }
}
