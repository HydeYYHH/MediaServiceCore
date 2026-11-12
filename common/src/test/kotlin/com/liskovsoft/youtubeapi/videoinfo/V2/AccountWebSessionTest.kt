package com.liskovsoft.youtubeapi.videoinfo.V2

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class AccountWebSessionTest {
    private val owner = """{"accountItem":{"accountId":"owner","accountName":{"simpleText":"Owner"},"isSelected":true}}"""
    private val brand = """{"accountItem":{"accountId":"brand","accountName":{"simpleText":"Brand"},"serviceEndpoint":{"selectActiveIdentityEndpoint":{"supportedTokens":[{"pageIdToken":{"pageId":"brand"}}]}}}}"""

    private fun accountsHttp(): HttpClient = object : HttpClient {
        override fun execute(request: HttpRequest): HttpReply {
            assertTrue(request.headers["Authorization"]!!.startsWith("SAPISIDHASH "))
            val isBrand = request.headers["X-Goog-PageId"] == "brand"
            val active = if (isBrand) brand.replace("\"accountId\":\"brand\"", "\"accountId\":\"brand\",\"isSelected\":true") else owner
            return HttpReply(200, if (request.body.contains("accountReadMask")) """{"items":[$owner,$brand]}"""
                else """{"items":[$active]}""")
        }
    }

    @Test fun installValidatesBothAccountRequestsAndOrdinaryRenewalKeepsGeneration() {
        val session = AccountSession()
        val first = session.install(WebSession("SAPISID=first"), accountsHttp()).value!!
        assertEquals("owner", first.accountId)
        assertEquals(1L, first.generation)
        assertEquals(2, session.accounts().size)
        val renewed = session.update(WebSession("SAPISID=second"), accountsHttp()).value!!
        assertEquals(first.generation, renewed.generation)
        assertEquals(first.ledgerScope(), renewed.ledgerScope())
        assertNotEquals(first.scope(), renewed.scope())
        assertEquals("SAPISID=first", first.webSession!!.cookies)
        assertTrue(session.snapshot(first.generation).ok)
    }

    @Test fun stableAccountStateTokenCorrelatesRealWebAndTvShapesWithoutUsingTheByline() {
        fun ownerItem(byline: String, name: String) = """{"accountItem":{
            "accountName":{"runs":[{"text":"$name"}]},"accountByline":{"simpleText":"$byline"},
            "channelHandle":{"simpleText":"@owner"},"isSelected":true,
            "serviceEndpoint":{"selectActiveIdentityEndpoint":{"supportedTokens":[
                {"accountStateToken":{"obfuscatedGaiaId":"stable-owner"}},
                {"offlineCacheKeyToken":{"clientCacheKey":"channel-cache"}},
                {"datasyncIdToken":{"datasyncIdToken":"channel-datasync"}}]}}}}"""
        var name = "Owner"
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                assertTrue(request.headers["Authorization"]!!.startsWith("SAPISIDHASH "))
                return if (request.body.contains("accountReadMask"))
                    HttpReply(200, """{"actions":[${ownerItem("Public channel details", name)}]}""")
                else HttpReply(200, """{"contents":[${ownerItem("owner@example.test", name)}]}""")
            }
        }
        val session = AccountSession()
        val installed = session.install(WebSession("SAPISID=first"), http).value!!
        assertEquals("gaia:stable-owner", installed.accountId)
        assertNull(installed.onBehalfOfUser)
        assertEquals("owner@example.test", session.accounts().single().email)
        assertEquals("@owner", session.accounts().single().handle)
        name = "Renamed owner"
        val renewed = session.update(WebSession("SAPISID=renewed"), http).value!!
        assertEquals(installed.generation, renewed.generation)
        assertEquals(installed.ledgerScope(), renewed.ledgerScope())
        assertEquals("Renamed owner", session.accounts().single().name)
        assertEquals("@owner", session.accounts().single().handle)
    }

    @Test fun handlesAndNonEmailBylinesRemainDistinctAndWebHandlesSurviveAnOmittedActiveHandle() {
        val token = """"serviceEndpoint":{"selectActiveIdentityEndpoint":{"supportedTokens":[{"accountStateToken":{"obfuscatedGaiaId":"owner"}}]}}"""
        val web = """{"accountItem":{"accountByline":{"simpleText":"Channel details"},"channelHandle":{"simpleText":"@owner"},"isSelected":true,$token}}"""
        val active = """{"accountItem":{"accountByline":{"simpleText":"owner@example.test"},"isSelected":true,$token}}"""
        val listed = readAccounts(web).single()
        assertNull(listed.email)
        assertEquals("@owner", listed.handle)
        assertNull(readAccounts(web.replace("Channel details", "@owner")).single().email)
        val http = object : HttpClient {
            override fun execute(request: HttpRequest) = HttpReply(200, if (request.body.contains("accountReadMask")) web else active)
        }
        val session = AccountSession()
        assertTrue(session.install(WebSession("SAPISID=fixture"), http).ok)
        assertEquals("@owner", session.accounts().single().handle)
        assertEquals("owner@example.test", session.accounts().single().email)
        val legacy = Account::class.java.getConstructor(String::class.java, String::class.java, String::class.java, String::class.java,
            Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, String::class.java)
            .newInstance("owner", "Owner", "owner@example.test", "photo", true, false, null)
        assertNull(legacy.handle)
        assertEquals("owner", legacy.id)
        assertEquals("photo", legacy.photo)
    }

    @Test fun brandPageIdentityTakesPrecedenceOverItsOwnersSharedAccountStateToken() {
        fun item(page: String?) = """{"accountItem":{"accountByline":{"simpleText":"same display"},
            "serviceEndpoint":{"selectActiveIdentityEndpoint":{"supportedTokens":[
                {"accountStateToken":{"obfuscatedGaiaId":"same-google-owner"}}
                ${page?.let { ",{\"pageIdToken\":{\"pageId\":\"$it\"}}" }.orEmpty()}]}}}}"""
        val profiles = readAccounts("""{"items":[${item(null)},${item("brand-one")},${item("brand-two")}]}""")
        assertEquals(listOf("gaia:same-google-owner", "brand-one", "brand-two"), profiles.map { it.id })
        assertEquals(listOf(null, "brand-one", "brand-two"), profiles.map { it.pageId })
    }

    @Test fun brandSelectionSurvivesRenewalButSwitchAndLogoutFenceOldOperations() {
        val session = AccountSession()
        session.install(WebSession("SAPISID=fixture"), accountsHttp())
        val ownerGeneration = session.snapshot().generation
        assertTrue(session.select("brand"))
        val selected = session.snapshot()
        assertEquals("brand", selected.onBehalfOfUser)
        assertEquals("session_changed", session.snapshot(ownerGeneration).error?.code)
        assertTrue(session.update(WebSession("SAPISID=renewed"), accountsHttp()).ok)
        assertEquals(selected.generation, session.snapshot().generation)
        assertEquals("brand", session.snapshot().onBehalfOfUser)
        assertTrue(session.select("brand"))
        assertEquals(selected.generation, session.snapshot().generation)
        session.clear()
        assertFalse(session.snapshot().signedIn)
        assertEquals(RequestFailure.CANCELLED, session.snapshot(selected.generation).error?.kind)
    }

    @Test fun changingTheGoogleAccountIndexChangesIdentityGeneration() {
        val session = AccountSession()
        val first = session.install(WebSession("SAPISID=fixture", 0), accountsHttp()).value!!
        val second = session.install(WebSession("SAPISID=fixture", 1), accountsHttp()).value!!
        assertEquals(first.generation + 1, second.generation)
        assertNotEquals(first.ledgerScope(), second.ledgerScope())
    }

    @Test fun invalidOrMismatchedCredentialsDoNotReplaceTheCurrentSession() {
        val session = AccountSession()
        val first = session.install(WebSession("SAPISID=fixture"), accountsHttp()).value!!
        val unauthenticated = object : HttpClient { override fun execute(request: HttpRequest) = HttpReply(401, "{}") }
        assertEquals(RequestFailure.AUTH, session.update(WebSession("SAPISID=expired"), unauthenticated).error?.kind)
        assertEquals(first, session.snapshot())
        assertEquals(RequestFailure.AUTH, session.install(WebSession("PREF=anonymous"), unauthenticated).error?.kind)
        val mismatched = object : HttpClient {
            override fun execute(request: HttpRequest) = HttpReply(200, if (request.body.contains("accountReadMask"))
                """{"items":[$owner]}""" else """{"accountItem":{"accountId":"another","isSelected":true}}""")
        }
        assertEquals("account_identity_mismatch", session.install(WebSession("SAPISID=other"), mismatched).error?.code)
        assertEquals(first, session.snapshot())
    }

    @Test fun matchingDisplayBylinesCannotAuthorizeDifferentStableIdentities() {
        fun item(gaia: String) = """{"accountItem":{"accountByline":{"simpleText":"same display"},
            "isSelected":true,"serviceEndpoint":{"selectActiveIdentityEndpoint":{"supportedTokens":[
                {"accountStateToken":{"obfuscatedGaiaId":"$gaia"}}]}}}}"""
        val http = object : HttpClient {
            override fun execute(request: HttpRequest) = HttpReply(200, if (request.body.contains("accountReadMask"))
                item("listed-owner") else item("different-active-owner"))
        }
        val session = AccountSession()
        assertEquals("account_identity_mismatch", session.install(WebSession("SAPISID=fixture"), http).error?.code)
        assertFalse(session.snapshot().signedIn)
        assertTrue(session.accounts().isEmpty())
    }

    @Test fun displayNamesAndBylinesWithoutStableIdentityTokensCannotInstallASession() {
        val labels = """{"accountItem":{"accountName":{"simpleText":"Same name"},
            "accountByline":{"simpleText":"owner@example.test"},"channelHandle":{"simpleText":"@owner"},"isSelected":true}}"""
        assertTrue(readAccounts(labels).isEmpty())
        val session = AccountSession()
        val labelsOnly = object : HttpClient { override fun execute(request: HttpRequest) = HttpReply(200, labels) }
        assertEquals("missing_accounts", session.install(WebSession("SAPISID=fixture"), labelsOnly).error?.code)
        val missingActive = object : HttpClient {
            override fun execute(request: HttpRequest) = HttpReply(200, if (request.body.contains("accountReadMask")) owner else labels)
        }
        assertEquals("missing_active_account", session.install(WebSession("SAPISID=fixture"), missingActive).error?.code)
        assertFalse(session.snapshot().signedIn)
        assertTrue(session.accounts().isEmpty())
    }

    @Test fun aBrandRenewalCannotAcceptItsOwnersIdentityWhenTheActiveResponseLacksThePageToken() {
        val session = AccountSession()
        assertTrue(session.install(WebSession("SAPISID=fixture"), accountsHttp()).ok)
        assertTrue(session.select("brand"))
        val selected = session.snapshot()
        val delegate = accountsHttp()
        val missingPage = object : HttpClient {
            override fun execute(request: HttpRequest) = if (request.body.contains("accountReadMask"))
                delegate.execute(request) else HttpReply(200, owner)
        }
        assertEquals("account_identity_mismatch", session.update(WebSession("SAPISID=renewed"), missingPage).error?.code)
        assertEquals(selected, session.snapshot())
        assertEquals("brand", session.snapshot().onBehalfOfUser)
    }

    @Test fun validationDoesNotHoldTheMonitorAndLogoutRejectsItsLateCommit() {
        val session = AccountSession()
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val result = AtomicReference<ReadResult<RequestContext>>()
        val delegate = accountsHttp()
        val blocked = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                entered.countDown()
                assertTrue(resume.await(5, TimeUnit.SECONDS))
                return delegate.execute(request)
            }
        }
        val worker = Thread { result.set(session.install(WebSession("SAPISID=fixture"), blocked)) }
        worker.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        // This would block if validation held AccountSession's monitor.
        session.clear()
        resume.countDown()
        worker.join(5000)
        assertFalse(worker.isAlive)
        assertEquals("session_changed", result.get().error?.code)
        assertFalse(session.snapshot().signedIn)
    }

    @Test fun theNewestValidationWinsEvenWhenAnOlderRequestFinishesLater() {
        val session = AccountSession()
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val old = AtomicReference<ReadResult<RequestContext>>()
        val delegate = accountsHttp()
        val slow = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                entered.countDown()
                assertTrue(resume.await(5, TimeUnit.SECONDS))
                return delegate.execute(request)
            }
        }
        val worker = Thread { old.set(session.install(WebSession("SAPISID=old"), slow)) }
        worker.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        assertTrue(session.install(WebSession("SAPISID=new"), delegate).ok)
        resume.countDown()
        worker.join(5000)
        assertEquals("session_changed", old.get().error?.code)
        assertEquals("SAPISID=new", session.snapshot().webSession!!.cookies)
    }
}
