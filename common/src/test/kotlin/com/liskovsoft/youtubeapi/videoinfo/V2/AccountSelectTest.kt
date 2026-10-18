package com.liskovsoft.youtubeapi.videoinfo.V2
import org.junit.Assert.*
import org.junit.Test
class AccountSelectTest {
    @Test fun readsAndSelectsBrandAccountsWithoutChangingOldSnapshots() {
        val body = """{"items":[{"accountItem":{"accountName":{"simpleText":"Owner"},"accountByline":{"simpleText":"owner@example.com"},"isSelected":true}},{"accountItem":{"accountName":{"simpleText":"Brand"},"serviceEndpoint":{"selectActiveIdentityEndpoint":{"supportedTokens":[{"pageIdToken":{"pageId":"brand-id"}}]}}}}]}"""
        val accounts = readAccounts(body); assertEquals(2, accounts.size)
        val session = AccountSession(RequestContext(visitor = "v"))
        session.replace(AccessToken("a", "r", 100), accounts)
        val before = session.snapshot()
        assertTrue(session.select("brand-id")); assertNotEquals(before.scope(), session.snapshot().scope())
        assertEquals("brand-id", session.snapshot().onBehalfOfUser); assertNull(before.onBehalfOfUser)
        assertFalse(session.select("missing"))
        session.clear(); assertNull(session.snapshot().accessToken)
    }
    @Test fun requiresSignInBeforeRequestingAccounts() {
        val never = object : HttpClient { override fun execute(request: HttpRequest): HttpReply = error("must not run") }
        assertEquals(RequestFailure.AUTH, loadAccounts(RequestContext(), never).error!!.kind)
    }
}
