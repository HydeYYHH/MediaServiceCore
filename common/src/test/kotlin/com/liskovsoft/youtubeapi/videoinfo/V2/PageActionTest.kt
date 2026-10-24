package com.liskovsoft.youtubeapi.videoinfo.V2
import org.junit.Assert.*
import org.junit.Test
class PageActionTest {
    @Test fun extractsTheFeedbackTokenFromHistoryItemMenus() {
        val action = PageAction("Remove from watch history",
            ActionEndpoint("feedbackEndpoint", """{"feedbackTokens":["token-one"]}"""))
        assertEquals("token-one", action.feedbackToken())
    }
    @Test fun findsTokensNestedDeeperInThePayload() {
        val action = PageAction(null,
            ActionEndpoint("feedbackEndpoint", """{"undoFeedbackEndpoint":{"feedbackTokens":[" ","token-two"]}}"""))
        assertEquals("token-two", action.feedbackToken())
    }
    @Test fun ignoresOtherEndpointsAndBrokenPayloads() {
        assertNull(PageAction("Share", ActionEndpoint("shareEntityServiceEndpoint", """{"serializedShareEntity":"x"}""")).feedbackToken())
        assertNull(PageAction(null, ActionEndpoint("feedbackEndpoint", "not json")).feedbackToken())
        assertNull(PageAction(null, ActionEndpoint("feedbackEndpoint", """{"feedbackTokens":[]}""")).feedbackToken())
    }
}
