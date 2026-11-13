package com.liskovsoft.youtubeapi.videoinfo.V2

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class AccountProfileContinuityTest {
    private fun item(id: String, photo: String? = null, selected: Boolean = true) = """{"accountItem":{
      "accountId":"$id","accountName":{"simpleText":"$id"},"isSelected":$selected,
      "channelHandle":{"simpleText":"@$id"}
      ${photo?.let { ",\"accountPhoto\":{\"thumbnails\":[{\"url\":\"$it\"}]}" }.orEmpty()}
    }}"""
    private fun http(web: String, active: String = web) = object : HttpClient {
        override fun execute(request: HttpRequest) = HttpReply(200, if (request.body.contains("accountReadMask")) web else active)
    }

    @Test fun webAvatarTakesPrecedenceOverTheValidationProfileAndSurvivesMissingRenewalMetadata() {
        val session = AccountSession()
        val photo = "https://yt3.ggpht.com/owner"
        assertTrue(session.install(WebSession("SAPISID=fixture"), http(item("owner", photo), item("owner", "https://yt3.ggpht.com/active-profile"))).ok)
        assertEquals(photo, session.accounts().single().photo)
        val generation = session.snapshot().generation
        assertTrue(session.update(WebSession("SAPISID=renewed"), http(item("owner"))).ok)
        assertEquals(generation, session.snapshot().generation)
        assertEquals(photo, session.accounts().single().photo)
        val replacement = "https://yt3.ggpht.com/replacement"
        assertTrue(session.update(WebSession("SAPISID=renewed-again"), http(item("owner", replacement))).ok)
        assertEquals(replacement, session.accounts().single().photo)
    }

    @Test fun profileRefreshPreservesOnlyTheSameVerifiedIdentityAndAcceptsANewPhoto() {
        val session = AccountSession()
        val rows = """{"items":[${item("owner", "https://yt3.ggpht.com/owner")},${item("other", selected = false)}]}"""
        assertTrue(session.install(WebSession("SAPISID=fixture"), http(rows, item("owner"))).ok)
        val before = session.snapshot().generation
        assertTrue(session.updateAccounts(session.accounts().map { it.copy(photo = null) }))
        assertEquals("https://yt3.ggpht.com/owner", session.accounts().first { it.id == "owner" }.photo)
        assertNull(session.accounts().first { it.id == "other" }.photo)
        assertEquals(before, session.snapshot().generation)
        assertTrue(session.select("other"))
        assertNull(session.accounts().first { it.selected }.photo)
        assertTrue(session.updateAccounts(session.accounts().map { if (it.id == "other") it.copy(photo = "https://yt3.ggpht.com/other") else it }))
        assertEquals("https://yt3.ggpht.com/other", session.accounts().first { it.selected }.photo)
    }

    @Test fun anotherAccountIndexOrIdentityAndLogoutCannotInheritThePreviousAvatar() {
        val session = AccountSession()
        assertTrue(session.install(WebSession("SAPISID=fixture"), http(item("owner", "https://yt3.ggpht.com/owner"))).ok)
        assertTrue(session.install(WebSession("SAPISID=fixture", 1), http(item("owner"))).ok)
        assertNull(session.accounts().single().photo)
        assertTrue(session.install(WebSession("SAPISID=fixture", 1), http(item("other"))).ok)
        assertNull(session.accounts().single().photo)
        session.clear()
        assertTrue(session.accounts().isEmpty())
        assertFalse(session.snapshot().signedIn)
    }

    @Test fun aFailedRenewalLeavesTheVerifiedPhotoAndIdentityUntouched() {
        val session = AccountSession()
        assertTrue(session.install(WebSession("SAPISID=fixture"), http(item("owner", "https://yt3.ggpht.com/owner"))).ok)
        val before = session.snapshot()
        val failed = object : HttpClient { override fun execute(request: HttpRequest): HttpReply = throw IOException("fixture") }
        assertFalse(session.update(WebSession("SAPISID=renewed"), failed).ok)
        assertEquals(before, session.snapshot())
        assertEquals("https://yt3.ggpht.com/owner", session.accounts().single().photo)
    }

    @Test fun localeChangesRetireOldReadsWhileKeepingTheVerifiedAccountAndDoNotRevalidateIt() {
        val session = AccountSession()
        assertTrue(session.install(WebSession("SAPISID=fixture"), http(item("owner", "https://yt3.ggpht.com/owner"))).ok)
        val before = session.snapshot()
        val accounts = session.accounts()
        val localized = session.updateLocale("zh-CN", "cn")
        assertEquals("zh-CN", localized.language)
        assertEquals("CN", localized.region)
        assertEquals(before.generation + 1, localized.generation)
        assertEquals(before.accountId, localized.accountId)
        assertSame(before.webSession, localized.webSession)
        assertEquals(accounts, session.accounts())
        assertEquals("session_changed", session.snapshot(before.generation).error?.code)
        assertEquals(localized, session.updateLocale("zh-CN", "CN"))
        assertTrue(session.update(WebSession("SAPISID=renewed"), http(item("owner"))).ok)
        assertEquals("zh-CN", session.snapshot().language)
        assertEquals("CN", session.snapshot().region)
    }
}
