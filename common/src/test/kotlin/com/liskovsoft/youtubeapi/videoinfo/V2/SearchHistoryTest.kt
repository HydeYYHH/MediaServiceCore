package com.liskovsoft.youtubeapi.videoinfo.V2

import java.io.IOException

import org.junit.Assert.*
import org.junit.Test

class SearchHistoryTest {
    private val personal = RequestContext(accountId = "owner", webSession = WebSession("SAPISID=first", 0))
    private class Store : SearchHistoryStore {
        val entries = HashMap<String, List<String>>()
        val writes = ArrayList<Pair<String, List<String>>>()
        override fun read(account: String) = entries[account].orEmpty()
        override fun write(account: String, entries: List<String>) {
            this.entries[account] = entries.toList()
            writes += account to entries.toList()
        }
    }
    private fun http(status: Int, body: String) = object : HttpClient {
        override fun execute(request: HttpRequest) = HttpReply(status, body)
    }

    @Test fun recentEntriesDeduplicateRespectCapacityAndKeepAccountsSeparate() {
        val history = SearchHistory(capacity = 2)
        val other = personal.copy(accountId = "other")
        history.record(" one ", personal); history.record("two", personal)
        history.record("one", personal); history.record("three", other); history.record(" ", personal)
        assertEquals(listOf("one", "two"), history.read(personal))
        assertEquals(listOf("three"), history.read(other))
        history.record("four", personal)
        assertEquals(listOf("four", "one"), history.read(personal))
        history.remove("one", personal)
        assertEquals(listOf("four"), history.read(personal))
    }

    @Test fun confirmedRemoteClearWritesOnlyTheSelectedLocalIdentity() {
        for (ack in listOf("""{"success":true}""", """{"actionResults":[{"status":"STATUS_SUCCEEDED"}]}""")) {
            val store = Store(); val history = SearchHistory(store)
            val brand = personal.copy(onBehalfOfUser = "brand")
            val index = personal.copy(webSession = WebSession("SAPISID=first", 1))
            history.record("personal", personal); history.record("brand", brand); history.record("index-one", index)
            val before = store.writes.size
            val reply = history.clearRemote(personal, http(200, ack))
            assertTrue(reply.ok); assertTrue(reply.value!!.confirmed)
            assertTrue(history.read(personal).isEmpty())
            assertEquals(listOf("brand"), history.read(brand))
            assertEquals(listOf("index-one"), history.read(index))
            assertEquals(before + 1, store.writes.size)
            assertTrue(store.writes.last().second.isEmpty())
            assertEquals(listOf("brand"), SearchHistory(store).read(brand))
        }
    }

    @Test fun unconfirmedAndUnrelatedEntityRepliesNeverWriteTheLocalStore() {
        for (body in listOf("{}", """{"responseContext":{},"actions":[]}""",
            """{"playlistId":"unrelated"}""", """{"commentRenderer":{"commentId":"unrelated"}}""")) {
            val store = Store(); val history = SearchHistory(store)
            history.record("keep first", personal); history.record("keep second", personal)
            val before = store.writes.toList()
            val reply = history.clearRemote(personal, http(200, body))
            assertTrue(reply.ok); assertFalse(reply.value!!.confirmed)
            assertEquals(listOf("keep second", "keep first"), history.read(personal))
            assertEquals(before, store.writes)
        }
    }

    @Test fun remoteHttpActionParseAndTransportFailuresPreserveLocalEntries() {
        val failures = listOf(http(503, "{}"), http(200, """{"success":false}"""), http(200, "not JSON"),
            object : HttpClient { override fun execute(request: HttpRequest): HttpReply = throw IOException("fixture") })
        for (transport in failures) {
            val store = Store(); val history = SearchHistory(store)
            history.record("retain", personal)
            val before = store.writes.toList()
            assertFalse(history.clearRemote(personal, transport).ok)
            assertEquals(listOf("retain"), history.read(personal))
            assertEquals(before, store.writes)
        }
        val history = SearchHistory(); history.record("anonymous")
        val denied = history.clearRemote(RequestContext(), object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply = error("Anonymous clear must fail before IO")
        })
        assertEquals(RequestFailure.AUTH, denied.error!!.kind)
        assertEquals(listOf("anonymous"), history.read())
    }

    @Test fun verifiedIdentityIsolatesBrandAndIndexButSurvivesCookieAndGenerationChanges() {
        val store = Store(); val history = SearchHistory(store)
        val brand = personal.copy(onBehalfOfUser = "brand")
        val otherBrand = personal.copy(onBehalfOfUser = "other-brand")
        val index = personal.copy(webSession = WebSession("SAPISID=first", 1))
        history.record("personal", personal); history.record("brand", brand)
        history.record("other-brand", otherBrand); history.record("index-one", index)
        assertEquals(listOf("personal"), history.read(personal.copy(generation = 12,
            visitor = "renewed-visitor", webSession = WebSession("SAPISID=renewed", 0))))
        assertEquals(listOf("brand"), history.read(brand.copy(generation = 13, webSession = WebSession("SAPISID=renewed", 0))))
        history.clear(brand)
        assertTrue(history.read(brand).isEmpty())
        assertEquals(listOf("personal"), history.read(personal))
        assertEquals(listOf("other-brand"), history.read(otherBrand))
        assertEquals(listOf("index-one"), history.read(index))
        assertTrue(store.entries.keys.all { it.matches(Regex("msc-search-v2:[a-f0-9]{64}")) })
    }

    @Test fun stableVisitorCookieSurvivesDataAndTransientCookieRefreshWithoutMergingVisitors() {
        val history = SearchHistory()
        val first = VisitorSession("VISITOR_INFO1_LIVE=stable-a; YSC=old; __Secure-ROLLOUT_TOKEN=old", "data-old").requestContext()
        val renewed = VisitorSession("VISITOR_INFO1_LIVE=stable-a; YSC=new; __Secure-ROLLOUT_TOKEN=new", "data-new").requestContext(generation = 9)
        val second = VisitorSession("VISITOR_INFO1_LIVE=stable-b; YSC=old", "data-old").requestContext()
        history.record("first visitor", first); history.record("second visitor", second); history.record("no visitor")
        assertEquals(listOf("first visitor"), history.read(renewed))
        assertEquals(listOf("second visitor"), history.read(second))
        assertEquals(listOf("no visitor"), history.read())
        history.clear(renewed)
        assertTrue(history.read(first).isEmpty())
        assertEquals(listOf("second visitor"), history.read(second))
    }

    @Test fun visitorsWithoutStableCookieAndUnverifiedLoginsRemainConservativelySeparated() {
        val history = SearchHistory()
        val first = VisitorSession("YSC=first", "data-a").requestContext()
        val renewed = VisitorSession("YSC=second", "data-a").requestContext(generation = 4)
        val second = VisitorSession("YSC=first", "data-b").requestContext()
        val login = RequestContext(webSession = WebSession("SAPISID=unverified-one"))
        val otherLogin = RequestContext(webSession = WebSession("SAPISID=unverified-two"))
        history.record("visitor-a", first); history.record("visitor-b", second)
        history.record("login-one", login); history.record("login-two", otherLogin)
        assertEquals(listOf("visitor-a"), history.read(renewed))
        assertEquals(listOf("visitor-b"), history.read(second))
        assertEquals(listOf("login-one"), history.read(login))
        assertEquals(listOf("login-two"), history.read(otherLogin))
    }
}
