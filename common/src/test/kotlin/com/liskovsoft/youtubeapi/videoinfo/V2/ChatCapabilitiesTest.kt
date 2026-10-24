package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class ChatCapabilitiesTest {
    @Test fun postingParamsAreIndependentFromTheReadContinuation() {
        val page = chatPage("""{"continuationContents":{"liveChatContinuation":{
            "continuations":[{"timedContinuationData":{"continuation":"read-key"}}],
            "input":{"liveChatMessageInputRenderer":{"sendButton":{"buttonRenderer":{
                "serviceEndpoint":{"sendLiveChatMessageEndpoint":{"params":"send-key"}}
            }}}},"actions":[{"addChatItemAction":{"item":{"liveChatTextMessageRenderer":{
                "id":"message","authorExternalChannelId":"author","message":{"simpleText":"hello"}
            }}}}]
        }}}""")
        assertEquals("read-key", page.nextKey)
        assertEquals("send-key", page.sendParams)
        assertEquals("author", page.messages.single().authorId)
        assertNull(chatPage("{}").sendParams)
    }

    @Test fun unrelatedVideoTogglesDoNotSetChannelSubscriptionState() {
        val page = channelPage("""{"contents":{"toggleButtonRenderer":{"isToggled":true,
            "defaultServiceEndpoint":{"likeEndpoint":{"status":"LIKE"}}}}}""")
        assertNull(page.subscribed)
    }
}
