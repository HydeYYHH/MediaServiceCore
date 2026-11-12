package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class ChatSessionTest {
    private val page = """{"continuationContents":{"liveChatContinuation":{"actions":[],
      "continuations":[{"timedContinuationData":{"continuation":"next"}}],
      "sendLiveChatMessageEndpoint":{"params":"server-address"}}}}"""
    private val accounts = object : HttpClient {
        override fun execute(request: HttpRequest) = HttpReply(200, """{"items":[
          {"accountItem":{"accountId":"owner","isSelected":true}},
          {"accountItem":{"accountId":"brand","pageIdToken":{"pageId":"brand"}}}]}""")
    }
    private fun session() = AccountSession().also {
        assertTrue(it.install(WebSession("SAPISID=first"), accounts).ok)
    }
    private fun http(body: String = page) = object : HttpClient {
        override fun execute(request: HttpRequest) = HttpReply(200, body)
    }

    @Test fun renewalUsesFreshCookiesWithoutRestartingContinuation() {
        val session = session()
        val poller = ChatPoller("first", session)
        assertTrue(poller.read(http()).ok)
        val generation = session.snapshot().generation
        assertTrue(session.update(WebSession("SAPISID=renewed"), accounts).ok)
        assertEquals(generation, session.snapshot().generation)
        assertTrue(poller.read(object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                assertEquals("next", jsonObject(request.body)!!.text("continuation"))
                assertEquals("SAPISID=renewed", request.headers["Cookie"])
                return HttpReply(200, page)
            }
        }).ok)
    }

    @Test fun switchingBeforeOrDuringPollingNeverReturnsTheOldAccountsPage() {
        val session = session()
        val before = ChatPoller("first", session)
        assertTrue(session.select("brand"))
        assertEquals("session_changed", before.read(object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply = error("No cross-identity request")
        }).error!!.code)
        val during = ChatPoller("first", session)
        val result = during.read(object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                session.clear()
                return HttpReply(200, page)
            }
        })
        assertEquals("session_changed", result.error!!.code)
    }

    @Test fun replayAndConnectionAlsoFenceLateResponses() {
        val session = session()
        val replay = ChatReplay("first", session)
        assertEquals("session_changed", replay.read(0, object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply { session.clear(); return HttpReply(200, page) }
        }).error!!.code)
        val connection = ChatConnection(session, object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply { session.clear(); return HttpReply(200, page) }
        })
        assertEquals("session_changed", connection.listen("first") { fail("Stale callback") }.error!!.code)
    }

    @Test fun sendingUsesTheBoundAccountAndNeverRetriesAnAmbiguousWrite() {
        val session = session()
        val poller = ChatPoller("first", session)
        assertEquals("chat_read_required", poller.sender("fallback").error!!.code)
        assertTrue(poller.read(http()).ok)
        val sender = poller.sender("unused-fallback").value!!
        assertTrue(session.update(WebSession("SAPISID=renewed"), accounts).ok)
        var calls = 0
        val result = sender.send("Hello", object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                calls++
                assertEquals("SAPISID=renewed", request.headers["Cookie"])
                assertEquals("server-address", jsonObject(request.body)!!.text("params"))
                assertEquals("fixed-id", jsonObject(request.body)!!.text("clientMessageId"))
                return HttpReply(200, "{}")
            }
        }, clientMessageId = "fixed-id")
        assertFalse(result.value!!.confirmed)
        assertEquals(1, calls)
        session.clear()
        assertEquals("session_changed", sender.send("Again", http()).error!!.code)
    }

    @Test fun restrictionsAndFailedRefreshInvalidatePreviouslyAvailableSenders() {
        val poller = ChatPoller("first", session())
        assertTrue(poller.read(http()).ok)
        val sender = poller.sender().value!!
        val restricted = """{"continuationContents":{"liveChatContinuation":{"actions":[],
          "actionPanel":{"liveChatRestrictedParticipationRenderer":{"message":{"simpleText":"Members only"}}},
          "continuations":[{"timedContinuationData":{"continuation":"next"}}]}}}"""
        assertTrue(poller.read(http(restricted)).ok)
        assertEquals("chat_posting_restricted", sender.send("Hello", http()).error!!.code)
        assertEquals("chat_posting_restricted", poller.sender("fallback").error!!.code)
        assertEquals("unknown_chat_page", poller.read(http("{}")).error!!.code)
        assertEquals("chat_read_required", sender.send("Hello", http()).error!!.code)
    }

    @Test fun sendDoesNotConfirmAfterAnIdentityChangeInFlight() {
        val session = session()
        val poller = ChatPoller("first", session)
        poller.read(http())
        val result = poller.sender().value!!.send("Hello", object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                session.clear()
                return HttpReply(200, """{"actions":[{"addChatItemAction":{"item":{"liveChatTextMessageRenderer":{
                    "id":"server-id","message":{"simpleText":"Hello"}}}}}]}""")
            }
        })
        assertEquals("session_changed", result.error!!.code)
    }

    @Test fun fallbackAddressesAreUsedOnlyAfterAReadableUnrestrictedPage() {
        val poller = ChatPoller("first", session())
        assertTrue(poller.read(http("""{"continuationContents":{"liveChatContinuation":{"actions":[]}}}""")).ok)
        assertEquals("missing_chat_key", poller.sender().error!!.code)
        val sender = poller.sender("broadcast-address").value!!
        val result = sender.send("Hello", object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                assertEquals("broadcast-address", jsonObject(request.body)!!.text("params"))
                return HttpReply(200, """{"actions":[{"addChatItemAction":{"item":{"liveChatTextMessageRenderer":{
                    "id":"message","message":{"simpleText":"Hello"}}}}}]}""")
            }
        }, clientMessageId = "broadcast-write")
        assertTrue(result.value!!.confirmed)
        assertNull(poller.read(http()).value!!.nextKey)
        assertEquals("chat_read_required", sender.send("After end", http()).error!!.code)
    }
}
