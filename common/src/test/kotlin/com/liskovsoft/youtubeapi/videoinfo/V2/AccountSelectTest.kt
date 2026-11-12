package com.liskovsoft.youtubeapi.videoinfo.V2
import org.junit.Assert.*
import org.junit.Test
class AccountSelectTest {
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
