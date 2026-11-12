package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test
import java.net.URI

class VisitorIdentityLedgerTest {
    private val tracking = PlayerTracking("https://www.youtube.com/api/stats/playback?ei=fixture",
        "https://www.youtube.com/api/stats/watchtime?of=fixture")
    private fun parameter(request: HttpRequest, name: String) = Regex("[?&]$name=([^&]*)").find(request.url)?.groupValues?.get(1)

    @Test fun visitorRenewalKeepsGenerationNonceAndTheActualWatchtimeInterval() {
        val account = AccountSession()
        val first = account.installVisitor(VisitorSession("VISITOR_INFO1_LIVE=stable-fixture; YSC=old", "visitor-old"))
        val requests = ArrayList<HttpRequest>()
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply { requests += request; return HttpReply(204, "") }
        }
        val history = WatchHistory(http)
        assertTrue(history.update("jNQXAC9IVRw", 1000, 100_000, tracking, first).ok)
        val renewed = account.installVisitor(VisitorSession("VISITOR_INFO1_LIVE=stable-fixture; YSC=new; __Secure-ROLLOUT_TOKEN=new", "visitor-renewed"))
        assertEquals(first.generation, renewed.generation); assertEquals(first.ledgerScope(), renewed.ledgerScope())
        assertTrue(history.update("jNQXAC9IVRw", 6000, 100_000, tracking, renewed).ok)
        assertEquals(listOf("/api/stats/playback", "/api/stats/watchtime"), requests.map { URI(it.url).path })
        assertEquals(parameter(requests[0], "cpn"), parameter(requests[1], "cpn"))
        assertEquals("1.0", parameter(requests[1], "st")); assertEquals("6.0", parameter(requests[1], "et"))
        assertEquals(renewed.visitorCookie, requests[1].headers["Cookie"])
        assertEquals("visitor-renewed", requests[1].headers["X-Goog-Visitor-Id"])
    }

    @Test fun changingStableCookieWithOldVisitorDataCannotReuseAnAnonymousLedger() {
        val first = VisitorSession("VISITOR_INFO1_LIVE=stable-fixture", "same-data").requestContext()
        val other = VisitorSession("VISITOR_INFO1_LIVE=other-fixture", "same-data").requestContext()
        assertNotEquals(first.ledgerScope(), other.ledgerScope())
        val account = AccountSession(first)
        assertEquals(first.generation + 1, account.installVisitor(VisitorSession("VISITOR_INFO1_LIVE=other-fixture", "same-data")).generation)
    }

    @Test fun absentStableCookieUsesVisitorDataWithoutBindingShortLivedCookies() {
        val first = VisitorSession("YSC=old", "data-one").requestContext()
        val renewed = VisitorSession("YSC=new; __Secure-ROLLOUT_TOKEN=new", "data-one").requestContext()
        val other = VisitorSession("YSC=old", "data-two").requestContext()
        assertEquals(first.ledgerScope(), renewed.ledgerScope())
        assertNotEquals(first.ledgerScope(), other.ledgerScope())
        // Prefixes distinguish a cookie identity from a coincidentally identical visitor-data value.
        assertNotEquals(first.ledgerScope(), VisitorSession("VISITOR_INFO1_LIVE=data-one", "irrelevant").requestContext().ledgerScope())
    }

    @Test fun sharedHelperPreservesTheExistingPersistentSearchHistoryKey() {
        // Produced by the previous msc-search-v2 serialization, before sharing the identity helper.
        val legacyKey = "msc-search-v2:554cb4cf794d488744cddef7f9b45b38048a41b32d32f73fcafc08d36c2eb460"
        val stored = hashMapOf(legacyKey to listOf("Existing local query"))
        val store = object : SearchHistoryStore {
            override fun read(account: String) = stored[account].orEmpty()
            override fun write(account: String, entries: List<String>) { stored[account] = entries }
        }
        val history = SearchHistory(store)
        val renewed = VisitorSession("VISITOR_INFO1_LIVE=stable-fixture; YSC=new", "refreshed-data").requestContext()
        assertEquals(listOf("Existing local query"), history.read(renewed))
        history.record("New local query", renewed)
        assertEquals(setOf(legacyKey), stored.keys)
        assertEquals(listOf("New local query", "Existing local query"), stored.getValue(legacyKey))
    }
}
