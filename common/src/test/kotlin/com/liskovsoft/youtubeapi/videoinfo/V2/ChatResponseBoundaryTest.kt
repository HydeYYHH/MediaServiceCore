package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class ChatResponseBoundaryTest {
    private fun http(body: String) = object : HttpClient {
        override fun execute(request: HttpRequest) = HttpReply(200, body)
    }

    @Test fun unknownAndMalformedResponsesCannotBecomeSuccessfulChatEnds() {
        val bodies = listOf(
            "{}",
            """{"continuationContents":{"liveChatContinuation":{}}}""",
            """{"actions":[{"futureUnknownAction":{}}]}""",
            """{"actions":[{"futureUnknownAction":{}}],"continuations":[]}""",
            """{"actions":[],"continuations":[{"futureUnknownContinuation":{}}]}""",
            """{"continuationContents":{"liveChatContinuation":{"actions":123}}}""",
            """{"actions":[null]}""",
            """{"continuations":"ended"}""",
            """{"continuationContents":{"liveChatContinuation":null},"actions":[]}""",
            """{"diagnostic":{"timedContinuationData":{"timeoutMs":1000}}}""",
            """{"diagnostic":{"timedContinuationData":{"continuation":"unrelated"}}}""",
            """{"diagnostic":{"liveChatTextMessageRenderer":{"id":"unrelated"}}}""",
            """{"actions":[{"addChatItemAction":{"item":{"futureUnknownRenderer":{}}}}]}""",
            """{"actions":[{"replayChatItemAction":{"actions":[{"futureUnknownAction":{}}]}}]}""",
            """{"timedContinuationData":{}}""",
            """{"actions":[],"continuations":[{"timedContinuationData":{"continuation":" "}}]}"""
        )
        for (body in bodies) {
            val live = loadChat("first", http = http(body))
            assertEquals(body, RequestFailure.PARSE, live.error?.kind)
            assertEquals("unknown_chat_page", live.error?.code)
            assertNull(live.value)
            val replay = loadChatReplay("first", http = http(body))
            assertEquals(body, "unknown_chat_page", replay.error?.code)
            assertNull(replay.value)
        }
    }

    @Test fun aConnectionReportsUnknownActionsWithoutDeliveringAnEmptyPageOrNormalEnd() {
        var requests = 0
        var callbacks = 0
        val connection = ChatConnection(http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                requests++
                return HttpReply(200, """{"actions":[{"futureUnknownAction":{}}]}""")
            }
        })
        val result = connection.listen("first") { callbacks++ }
        assertEquals("unknown_chat_page", result.error?.code)
        assertFalse(result.ok)
        assertNull(result.value)
        assertEquals(0, callbacks)
        assertEquals(1, requests)
    }

    @Test fun explicitEmptyCollectionsRemainReadableEndsWithoutAnotherRequest() {
        for (body in listOf(
            """{"actions":[]}""",
            """{"continuations":[]}""",
            """{"actions":[],"continuations":[]}""",
            """{"continuationContents":{"liveChatContinuation":{"actions":[]}}}""",
            """{"continuationContents":{"liveChatContinuation":{"continuations":[]}}}"""
        )) {
            var requests = 0
            val poller = ChatPoller("first")
            val transport = object : HttpClient {
                override fun execute(request: HttpRequest): HttpReply {
                    requests++
                    return HttpReply(200, body)
                }
            }
            val terminal = poller.read(transport)
            assertTrue(body, terminal.ok)
            val terminalPage = requireNotNull(terminal.value)
            assertTrue(terminalPage.messages.isEmpty())
            assertNull(terminalPage.nextKey)
            assertTrue(poller.read(transport).ok)
            assertEquals(1, requests)
            val replay = loadChatReplay("first", http = http(body))
            assertTrue(replay.ok)
            val replayPage = replay.value!!
            assertTrue(replayPage.replay)
            assertNull(replayPage.nextKey)
        }
    }

    @Test fun realContinuationTypesKeepUnknownControlsPollingWithTheirIssuedDelay() {
        for (kind in listOf("timedContinuationData", "invalidationContinuationData", "reloadContinuationData", "liveChatReplayContinuationData")) {
            val next = """{"$kind":{"continuation":"issued-next","timeoutMs":2400}}"""
            for (body in listOf(next, """{"continuationContents":{"liveChatContinuation":{
                "actions":[{"futureControlAction":{"unknown":true}}],"continuations":[$next]}}}""")) {
                val loaded = loadChat("first", http = http(body))
                assertTrue(body, loaded.ok)
                val page = loaded.value!!
                assertEquals("issued-next", page.nextKey)
                assertEquals(2400L, page.waitMs)
                assertEquals(kind == "liveChatReplayContinuationData", page.replay)
            }
        }
    }

    @Test fun routingCannotBeBorrowedFromAnUnrelatedNestedControl() {
        val terminal = loadChat("first", http = http("""{"actions":[],
            "diagnostic":{"timedContinuationData":{"continuation":"unrelated","timeoutMs":60000}}}"""))
        assertTrue(terminal.ok)
        val terminalPage = requireNotNull(terminal.value)
        assertNull(terminalPage.nextKey)
        assertEquals(1000L, terminalPage.waitMs)
        val loaded = loadChat("first", http = http("""{"continuationContents":{"liveChatContinuation":{
            "diagnostic":{"liveChatReplayContinuationData":{"continuation":"unrelated","timeoutMs":60000}},
            "actions":[{"futureControlAction":{}}],
            "continuations":[{"timedContinuationData":{"continuation":"issued-next","timeoutMs":1800}}]}}}"""))
        assertTrue(loaded.ok)
        val loadedPage = requireNotNull(loaded.value)
        assertEquals("issued-next", loadedPage.nextKey)
        assertEquals(1800L, loadedPage.waitMs)
        assertFalse(loadedPage.replay)
    }

    @Test fun terminalMessagesDeletionsAndKnownNonMessageControlsKeepTheirMeaning() {
        val body = """{"actions":[
            {"addChatItemAction":{"item":{"liveChatTextMessageRenderer":{"id":"message","message":{"simpleText":"hello"}}}}},
            {"markChatItemAsDeletedAction":{"targetItemId":"message"}},
            {"markChatItemsByAuthorAsDeletedAction":{"externalChannelId":"author"}}]}"""
        val page = loadChat("first", http = http(body)).value!!
        assertEquals("hello", page.messages.single().text)
        assertEquals("message", page.removals.first().messageId)
        assertEquals("author", page.removals.last().authorId)
        assertEquals(listOf(ChatAction.Add::class, ChatAction.Remove::class, ChatAction.Remove::class), page.actions.map { it::class })
        assertNull(page.nextKey)
        val controls = listOf(
            """{"addChatItemAction":{"item":{"liveChatViewerEngagementMessageRenderer":{"id":"system"}}}}""",
            """{"replaceChatItemAction":{"targetItemId":"message","replacementItem":{"liveChatPlaceholderItemRenderer":{"id":"replacement"}}}}""",
            """{"removeChatItemAction":{"targetItemId":"message"}}""",
            """{"replayChatItemAction":{"videoOffsetTimeMsec":"1200","actions":[]}}""",
            """{"addLiveChatTickerItemAction":{"item":{"liveChatTickerPaidMessageItemRenderer":{"id":"ticker"}}}}""",
            """{"addBannerToLiveChatCommand":{"bannerRenderer":{"liveChatBannerRenderer":{"actionId":"banner"}}}}""",
            """{"removeBannerForLiveChatCommand":{"targetActionId":"banner"}}""",
            """{"showLiveChatActionPanelAction":{"panelToShow":{"liveChatActionPanelRenderer":{"id":"panel"}}}}""",
            """{"showLiveChatTooltipCommand":{"tooltip":{"tooltipRenderer":{"targetId":"tooltip"}}}}""",
            """{"replaceLiveChatAction":{"toReplace":"chat","replacement":{"liveChatRenderer":{"id":"replacement"}}}}"""
        )
        for (control in controls) {
            val result = loadChat("first", http = http("""{"continuationContents":{"liveChatContinuation":{"actions":[$control]}}}"""))
            assertTrue(control, result.ok)
            assertNull(result.value!!.nextKey)
        }
        val restricted = loadChat("first", http = http("""{"continuationContents":{"liveChatContinuation":{
            "actionPanel":{"liveChatRestrictedParticipationRenderer":{"message":{"simpleText":"Members only"}}}}}}"""))
        assertTrue(restricted.ok)
        assertEquals("Members only", restricted.value!!.postingRestriction)
    }

    @Test fun aFailedRefreshRevokesTheSenderAndRetainsTheLastIssuedContinuation() {
        val poller = ChatPoller("first", AccountSession(RequestContext(webSession = WebSession("SAPISID=fixture"))))
        val valid = """{"continuationContents":{"liveChatContinuation":{"actions":[],
            "sendLiveChatMessageEndpoint":{"params":"issued-address"},
            "continuations":[{"timedContinuationData":{"continuation":"issued-next"}}]}}}"""
        assertTrue(poller.read(http(valid)).ok)
        val sender = poller.sender().value!!
        assertEquals("unknown_chat_page", poller.read(http("""{"actions":[{"futureUnknownAction":{}}]}""")).error?.code)
        assertEquals("chat_read_required", sender.send("hello", object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply = error("A failed refresh must not submit a message")
        }).error?.code)
        val refreshed = poller.read(object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                assertEquals("issued-next", jsonObject(request.body)!!.text("continuation"))
                return HttpReply(200, valid)
            }
        })
        assertTrue(refreshed.ok)
        assertTrue(poller.sender().ok)
    }
}
