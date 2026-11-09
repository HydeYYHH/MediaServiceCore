package com.liskovsoft.youtubeapi.videoinfo.V2
import org.junit.Assert.*
import org.junit.Test
class ChatPageTest {
    @Test fun preservesMixedMessageAndDeletionOrder() {
        val p=chatPage("""{"actions":[{"liveChatPaidMessageRenderer":{"id":"paid"}},{"markChatItemAsDeletedAction":{"targetItemId":"paid"}},{"liveChatTextMessageRenderer":{"id":"text"}}]}""")
        assertEquals(listOf("paid","text"),p.messages.map {it.id})
        assertTrue(p.actions[1] is ChatAction.Remove)
    }
    @Test fun readsMessagesRemovalsAndTheServersDelay() {
        val page=chatPage("""{"continuationContents":{"liveChatContinuation":{"actions":[{"addChatItemAction":{"item":{"liveChatTextMessageRenderer":{"id":"m","message":{"runs":[{"text":"Hi"}]},"timestampUsec":"123"}}}},{"markChatItemAsDeletedAction":{"targetItemId":"old"}}],"continuations":[{"timedContinuationData":{"continuation":"next","timeoutMs":2500}}]}}}""")
        assertEquals("Hi",page.messages.single().text);assertEquals("old",page.removals.single().messageId)
        assertEquals(2500L,page.waitMs);assertEquals("next",page.nextKey)
    }
    @Test fun closingStopsPaginationWithoutStartingABackgroundThread() {
        var calls=0
        val http=object:HttpClient { override fun execute(request:HttpRequest):HttpReply { calls++;return HttpReply(200,"{\"timedContinuationData\":{\"continuation\":\"next\"}}") } }
        val connection=ChatConnection(http=http)
        assertTrue(connection.listen("first"){connection.close()}.ok);assertEquals(1,calls)
    }

    @Test fun incrementalPollingPreservesTheBlockingConnectionsPagesAndDelay() {
        val bodies = listOf(
            """{"actions":[{"liveChatTextMessageRenderer":{"id":"m","message":{"simpleText":"Hi"}}}],"timedContinuationData":{"continuation":"next","timeoutMs":2500}}""",
            """{"actions":[{"liveChatTextMessageRenderer":{"id":"m","message":{"simpleText":"Hi"}}},{"markChatItemAsDeletedAction":{"targetItemId":"m"}}]}""")
        fun transport(keys: MutableList<String>) = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                keys.add(jsonObject(request.body)!!.text("continuation")!!)
                return HttpReply(200, bodies[keys.lastIndex])
            }
        }
        val blockingKeys = arrayListOf<String>()
        val delays = arrayListOf<Long>()
        val blocking = arrayListOf<ChatPage>()
        assertTrue(ChatConnection(http = transport(blockingKeys), wait = { delays.add(it) })
            .listen("first", blocking::add).ok)
        val pollingKeys = arrayListOf<String>()
        val http = transport(pollingKeys)
        val poller = ChatPoller("first")
        val incremental = listOf(poller.read(http = http).value!!, poller.read(http = http).value!!)
        assertEquals(blocking, incremental)
        assertEquals(listOf("first", "next"), pollingKeys)
        assertEquals(blockingKeys, pollingKeys)
        assertEquals(listOf(2500L), delays)
        assertTrue(incremental.last().messages.isEmpty())
        assertEquals("m", incremental.last().removals.single().messageId)
        assertNull(poller.read(http = http).value!!.nextKey)
        poller.close()
        assertEquals(RequestFailure.CANCELLED, poller.read(http = http).error!!.kind)
        assertEquals(2, pollingKeys.size)
    }

    @Test fun closingAPollerDuringAReadDoesNotDeliverOrFetchAnotherPage() {
        val poller = ChatPoller("first")
        var calls = 0
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                calls++
                poller.close()
                return HttpReply(200, "{}")
            }
        }
        repeat(2) { assertEquals(RequestFailure.CANCELLED, poller.read(http = http).error!!.kind) }
        assertEquals(1, calls)
    }
}
