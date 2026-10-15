package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackSendTest {
    private val menu = """
        {"menu":{"menuRenderer":{"items":[
          {"menuServiceItemRenderer":{"serviceEndpoint":{"feedbackEndpoint":{"feedbackToken":"NOT_INTERESTED"}}}},
          {"menuServiceItemRenderer":{"serviceEndpoint":{"feedbackEndpoint":{"feedbackToken":"NO_CHANNEL"}}}},
          {"menuServiceItemRenderer":{"serviceEndpoint":{"feedbackEndpoint":{"feedbackToken":""}}}}
        ]}}}
    """
    private val reply = """
        {"feedbackResponses":[{"isProcessed":true,"followUpDialog":{"dismissalFollowUpRenderer":{
          "dismissalReasonsPrompt":{"simpleText":"Tell us why"},
          "reasons":[
            {"dismissalReasonTextRenderer":{"text":{"runs":[{"text":"I've "},{"text":"seen it"}]},"feedback":{"feedbackEndpoint":{"feedbackToken":"REASON"}}}},
            {"dismissalReasonTextRenderer":{"text":{"simpleText":"Skip"}}}
          ]
        }}}]}
    """

    @Test
    fun readsTokensInOrder() {
        val tokens = feedbackTokens(menu)
        assertEquals(2, tokens.size)
        assertEquals("NOT_INTERESTED", tokens[0])
        assertEquals("NO_CHANNEL", tokens[1])
    }

    @Test
    fun sendsTheTokenAndReadsTheReply() {
        val parsed = sendFeedback("NOT_INTERESTED", "visitor-1") { query ->
            assertTrue(query.url.contains("/youtubei/v1/feedback"))
            assertEquals("visitor-1", query.headers["X-Goog-Visitor-Id"])
            assertTrue(query.body.contains("\"feedbackTokens\":[\"NOT_INTERESTED\"]"))
            Pair(200, reply)
        }
        assertTrue(parsed.processed)
        assertEquals("Tell us why", parsed.title)
        assertEquals(1, parsed.reasons.size)
        assertEquals("I've seen it", parsed.reasons[0].title)
        assertEquals("REASON", parsed.reasons[0].token)
    }

    @Test
    fun emptyTokenSkipsPost() {
        var called = false
        val parsed = sendFeedback("", null) {
            called = true
            Pair(200, reply)
        }
        assertFalse(called)
        assertFalse(parsed.processed)
        assertNull(parsed.title)
        assertTrue(parsed.reasons.isEmpty())
    }
}
