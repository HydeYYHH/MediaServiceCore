package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountProfileTest {
    private val owner = Account("owner", "Owner", null, null, true, false, null)
    private val brand = Account("brand-page", "Brand", null, null, false, false, "brand-page")
    private fun installed(): AccountSession {
        val ownerItem = """{"accountItem":{"accountId":"owner","accountName":{"simpleText":"Owner"},"isSelected":true}}"""
        val brandItem = """{"accountItem":{"accountName":{"simpleText":"Brand"},"serviceEndpoint":{"selectActiveIdentityEndpoint":{
            "supportedTokens":[{"pageIdToken":{"pageId":"brand-page"}}]}}}}"""
        val http = object : HttpClient {
            override fun execute(request: HttpRequest) = HttpReply(200, if (request.body.contains("accountReadMask"))
                """{"items":[$ownerItem,$brandItem]}""" else ownerItem)
        }
        return AccountSession().also { assertTrue(it.install(WebSession("SAPISID=fixture"), http).ok) }
    }

    @Test fun rawCredentialsCannotImportUnverifiedProfiles() {
        val session = AccountSession(RequestContext(accountId = "owner", webSession = WebSession("SAPISID=fixture")))
        assertTrue(session.snapshot().signedIn)
        assertFalse(session.updateAccounts(listOf(owner, brand)))
        assertEquals("owner", session.snapshot().accountId)
        assertEquals("SAPISID=fixture", session.snapshot().webSession!!.cookies)
        assertTrue(session.accounts().isEmpty())
        assertFalse(session.select(brand.id))
    }

    @Test fun refreshingProfilesPreservesSelectionAndIdentity() {
        val session = installed()
        assertTrue(session.updateAccounts(listOf(owner, brand)))
        session.select(brand.id)
        val generation = session.snapshot().generation
        assertTrue(session.updateAccounts(listOf(owner, brand)))
        assertEquals(brand.id, session.snapshot().accountId)
        assertEquals("brand-page", session.snapshot().onBehalfOfUser)
        assertEquals(generation, session.snapshot().generation)
        assertTrue(session.accounts().single { it.id == brand.id }.selected)
    }

    @Test fun profileRefreshCannotAddUnknownIdentitiesSubstituteDelegationOrDuplicateIds() {
        val session = installed()
        val before = session.snapshot()
        val profiles = session.accounts()
        assertFalse(session.updateAccounts(profiles + Account("injected", "Unverified", null, null, false, false, "injected-page")))
        assertFalse(session.updateAccounts(profiles.map { if (it.id == brand.id) it.copy(pageId = "substituted-page") else it }))
        assertFalse(session.updateAccounts(profiles + profiles.first().copy(name = "Duplicate")))
        assertEquals(profiles, session.accounts())
        assertEquals(before, session.snapshot())
        assertFalse(session.select("injected"))
    }

    @Test fun verifiedProfileLabelsAndDisabledFlagsCanChangeWithoutChangingIdentity() {
        val session = installed()
        val before = session.snapshot()
        assertTrue(session.updateAccounts(session.accounts().map { it.copy(name = "Updated", disabled = true) }))
        assertEquals(listOf("Updated", "Updated"), session.accounts().map { it.name })
        assertTrue(session.accounts().all { it.disabled })
        assertFalse(session.select(brand.id))
        assertEquals(before, session.snapshot())
    }

    @Test fun anAnonymousSessionRejectsProfiles() {
        val session = AccountSession()
        assertFalse(session.updateAccounts(listOf(owner)))
        assertFalse(session.snapshot().signedIn)
        assertTrue(session.accounts().isEmpty())
    }
}
