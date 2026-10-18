package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class ChatReplayTest {
    private val body = """{"continuationContents":{"liveChatContinuation":{"actions":[{"replayChatItemAction":{"videoOffsetTimeMsec":"1200","actions":[{"liveChatTextMessageRenderer":{"id":"m","message":{"simpleText":"Hi"}}},{"markChatItemAsDeletedAction":{"targetItemId":"m"}}]}}],"continuations":[{"liveChatReplayContinuationData":{"continuation":"next"}}]}}}"""

    @Test fun keepsReplayTimingAndDeletionOrder() {
        val page = chatPage(body)
        assertTrue(page.replay)
        assertEquals(listOf(1200L, 1200L), page.events.map { it.offsetMs })
        assertTrue(page.actions[1] is ChatAction.Remove)
        assertEquals("next", page.nextKey)
    }

    @Test fun advancesDeduplicatesAndRestartsOnSeek() {
        val keys = ArrayList<String>()
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                assertTrue(request.url.endsWith("get_live_chat_replay"))
                val root = jsonObject(request.body)!!
                keys.add(root.text("continuation")!!)
                assertNotNull(root.path("currentPlayerState")!!.text("playerOffsetMs"))
                return HttpReply(200, body)
            }
        }
        val replay = ChatReplay("first", http = http)
        assertEquals(1, replay.read(0).value!!.messages.size)
        assertEquals(0, replay.read(2000).value!!.messages.size)
        assertEquals(1, replay.seek(0).value!!.messages.size)
        assertEquals(listOf("first", "next", "first"), keys)
        replay.close()
        assertEquals(RequestFailure.CANCELLED, replay.read(0).error!!.kind)
        assertEquals(3, keys.size)
    }
}
