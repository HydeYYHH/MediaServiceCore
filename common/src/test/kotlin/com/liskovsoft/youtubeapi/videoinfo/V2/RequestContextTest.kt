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
        assertEquals("SAPISID=account", contextualQuery(query, context.copy(webSession = WebSession("SAPISID=account"))).headers["Cookie"])
        assertNull(contextualQuery(query.copy(url = "https://example.com/youtubei/v1/browse"), context).headers["Cookie"])
        assertNotEquals(context.scope(), context.copy(visitorCookie = "other").scope())
        assertFalse(context.toString().contains("cookie"))
    }
    @Test fun keepsAndIsolatesIdentity() {
        val c = RequestContext(accountId = "a", webSession = WebSession("SAPISID=secret"), visitor = "v", language = "zh", region = "CN")
        val q = youtubeQuery(YOUTUBE_API + "browse", payload("browseId" to "home"), c)
        assertEquals("SAPISID=secret", q.headers["Cookie"])
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
    @Test fun unverifiedCookieIdentitiesCannotShareAnAnonymousHistoryLedger() {
        val first = RequestContext(webSession = WebSession("SAPISID=first"), visitor = "same-visitor")
        val second = first.copy(webSession = WebSession("SAPISID=second"))
        assertNotEquals(first.ledgerScope(), second.ledgerScope())
        assertNotEquals(first.ledgerScope(), first.anonymous().ledgerScope())
        val verified = first.copy(accountId = "stable-account")
        assertEquals(verified.ledgerScope(), verified.copy(webSession = second.webSession).ledgerScope())
        assertNotEquals(verified.ledgerScope(), verified.copy(webSession = WebSession("SAPISID=first", 1)).ledgerScope())
    }
    @Test fun staleHeadersAndDelegationAreRemovedEvenFromMalformedOrForeignRequests() {
        val context = RequestContext(webSession = WebSession("SAPISID=fixture"), onBehalfOfUser = "brand", visitor = "visitor")
        val signed = contextualQuery(playerQuery(PlayerClient.TV, "video", null), context)
        val foreign = contextualQuery(signed.copy(url = "https://example.test/youtubei/v1/player", body = "not-json"), context)
        for (name in listOf("Cookie", "Authorization", "X-Goog-AuthUser", "X-Goog-PageId", "X-Goog-Visitor-Id", "Origin", "X-Origin"))
            assertNull(name, foreign.headers[name])
        val foreignBody = jsonObject(contextualQuery(signed.copy(url = "https://example.test/youtubei/v1/player"), context).body)!!
        assertNull(foreignBody.path("context", "user")?.text("onBehalfOfUser"))
        assertNull(foreignBody.path("context", "client")?.text("visitorData"))
        val anonymous = contextualQuery(signed, context.anonymous())
        assertNull(jsonObject(anonymous.body)!!.path("context", "user")?.text("onBehalfOfUser"))
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
