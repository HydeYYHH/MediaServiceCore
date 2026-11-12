package com.liskovsoft.youtubeapi.videoinfo.V2
import org.junit.Assert.*
import org.junit.Test
class AccountSelectTest {
    @Test fun invalidationRetainsValidatedIdentityAndRetiresCapabilities() {
        val body = """{"items":[{"accountItem":{"accountId":"owner","accountName":{"simpleText":"Owner"},"isSelected":true}}]}"""
        val session = AccountSession(RequestContext(visitor = "visitor", language = "zh", region = "TW"))
        val http = object : HttpClient { override fun execute(request: HttpRequest) = HttpReply(200, body) }
        assertTrue(session.install(WebSession("SAPISID=fixture", 2), http).ok)
        val before = session.snapshot()
        val accounts = session.accounts()
        val cursor = session.execute { ReadResult("opened") }.value!!.cursor("next")!!
        val after = session.invalidate(20)
        assertEquals(before.copy(generation = 20), after)
        assertEquals(accounts, session.accounts())
        assertEquals(RequestFailure.CANCELLED, session.snapshot(before.generation).error?.kind)
        assertEquals(RequestFailure.CANCELLED, session.execute(cursor) { _, _ -> ReadResult("stale") }.error?.kind)
        assertEquals(21L, session.invalidate().generation)
    }

    @Test fun invalidationRejectsAnInFlightIdentityValidation() {
        val session = AccountSession(RequestContext(visitor = "guest"))
        val before = session.snapshot()
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                session.invalidate(5)
                return HttpReply(200, """{"accountItem":{"accountId":"owner","isSelected":true}}""")
            }
        }
        assertEquals(RequestFailure.CANCELLED, session.install(WebSession("SAPISID=fixture"), http).error?.kind)
        assertEquals(before.copy(generation = 5), session.snapshot())
        assertTrue(session.accounts().isEmpty())
    }

    @Test fun invalidationRejectsInvalidOrExhaustedGenerationsWithoutChangingTheProfile() {
        val session = AccountSession(RequestContext(visitor = "guest", generation = Long.MAX_VALUE))
        val before = session.snapshot()
        assertThrows(IllegalArgumentException::class.java) { session.invalidate(-1) }
        assertThrows(IllegalStateException::class.java) { session.invalidate() }
        assertEquals(before, session.snapshot())
    }
    @Test fun readsAndSelectsBrandAccountsWithoutChangingOldSnapshots() {
        val body = """{"items":[{"accountItem":{"accountId":"owner","accountName":{"simpleText":"Owner"},"accountByline":{"simpleText":"owner@example.com"},"isSelected":true}},{"accountItem":{"accountName":{"simpleText":"Brand"},"serviceEndpoint":{"selectActiveIdentityEndpoint":{"supportedTokens":[{"pageIdToken":{"pageId":"brand-id"}}]}}}}]}"""
        val accounts = readAccounts(body); assertEquals(2, accounts.size)
        val session = AccountSession(RequestContext(visitor = "v"))
        val http = object : HttpClient { override fun execute(request: HttpRequest) = HttpReply(200, body) }
        assertTrue(session.install(WebSession("SAPISID=fixture"), http).ok)
        assertTrue(session.updateAccounts(accounts))
        val before = session.snapshot()
        assertTrue(session.select("brand-id")); assertNotEquals(before.scope(), session.snapshot().scope())
        assertEquals("brand-id", session.snapshot().onBehalfOfUser); assertNull(before.onBehalfOfUser)
        assertFalse(session.select("missing"))
        session.clear(); assertNull(session.snapshot().webSession)
    }
    @Test fun exposesTheCachedAccountListForSwitchers() {
        val body = """{"items":[{"accountItem":{"accountId":"owner","accountName":{"simpleText":"Owner"},"isSelected":true}},{"accountItem":{"accountName":{"simpleText":"Brand"},"serviceEndpoint":{"selectActiveIdentityEndpoint":{"supportedTokens":[{"pageIdToken":{"pageId":"brand-id"}}]}}}}]}"""
        val accounts = readAccounts(body)
        val session = AccountSession()
        assertTrue(session.accounts().isEmpty())
        val http = object : HttpClient { override fun execute(request: HttpRequest) = HttpReply(200, body) }
        assertTrue(session.install(WebSession("SAPISID=fixture"), http).ok)
        assertTrue(session.updateAccounts(accounts))
        assertEquals(listOf("Owner", "Brand"), session.accounts().map { it.name })
        session.select("brand-id")
        assertEquals(2, session.accounts().size)
        session.clear()
        assertTrue(session.accounts().isEmpty())
    }
    @Test fun requiresSignInBeforeRequestingAccounts() {
        val never = object : HttpClient { override fun execute(request: HttpRequest): HttpReply = error("must not run") }
        assertEquals(RequestFailure.AUTH, loadAccounts(RequestContext(), never).error!!.kind)
    }
}
