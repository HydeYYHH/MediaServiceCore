package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountProfileTest {
    private val owner = Account("owner", "Owner", null, null, true, false, null)
    private val brand = Account("brand", "Brand", null, null, false, false, "brand-page")

    @Test fun profilesCanArriveAfterCredentials() {
        val session = AccountSession()
        session.replace(AccessToken("access", "refresh", Long.MAX_VALUE), emptyList())
        assertTrue(session.snapshot().signedIn)
        assertTrue(session.updateAccounts(listOf(owner, brand)))
        assertEquals("owner", session.snapshot().accountId)
        assertEquals("access", session.snapshot().accessToken)
    }

    @Test fun refreshingProfilesPreservesSelectionAndIdentity() {
        val session = AccountSession()
        session.replace(AccessToken("access", "refresh", Long.MAX_VALUE), listOf(owner, brand))
        session.select("brand")
        val generation = session.snapshot().generation
        assertTrue(session.updateAccounts(listOf(owner, brand)))
        assertEquals("brand", session.snapshot().accountId)
        assertEquals("brand-page", session.snapshot().onBehalfOfUser)
        assertEquals(generation, session.snapshot().generation)
        assertTrue(session.accounts().single { it.id == "brand" }.selected)
    }

    @Test fun anAnonymousSessionRejectsProfiles() {
        val session = AccountSession()
        assertFalse(session.updateAccounts(listOf(owner)))
        assertFalse(session.snapshot().signedIn)
        assertTrue(session.accounts().isEmpty())
    }
}
