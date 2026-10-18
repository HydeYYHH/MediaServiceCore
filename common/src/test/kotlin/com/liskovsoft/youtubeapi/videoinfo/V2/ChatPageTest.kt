package com.liskovsoft.youtubeapi.videoinfo.V2
import org.junit.Assert.*
import org.junit.Test
class ChatPageTest {
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
}
