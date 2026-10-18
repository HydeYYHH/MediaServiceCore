package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class ActionStateTest {
    @Test fun distinguishesAnAcknowledgementFromAnExplicitConfirmation() {
        var body = "{}"
        val http = object : HttpClient { override fun execute(request: HttpRequest) = HttpReply(200, body) }
        val context = RequestContext("account", "token")
        assertEquals(ActionState.UNCONFIRMED, voteVideo("v", VideoVote.LIKE, context, http).value!!.state)
        body = "{\"success\":true}"
        assertTrue(voteVideo("v", VideoVote.LIKE, context, http).value!!.confirmed)
        body = "{\"actionResults\":[{\"status\":\"STATUS_SUCCEEDED\"},{\"status\":\"PENDING\"}]}"
        assertFalse(voteVideo("v", VideoVote.LIKE, context, http).value!!.confirmed)
        body = "{\"actionResults\":[{\"status\":\"STATUS_FAILED\"}]}"
        assertFalse(voteVideo("v", VideoVote.LIKE, context, http).ok)
        body = "{\"actionResults\":[{\"success\":false}]}"
        assertFalse(voteVideo("v", VideoVote.LIKE, context, http).ok)
    }

    @Test fun treatsReturnedEntitiesAsConfirmation() {
        val root = jsonObject("{\"commentEntityPayload\":{\"properties\":{\"commentId\":\"created\"}}}")!!
        assertTrue(actionReply(root).value!!.confirmed)
        assertFalse(actionReply(jsonObject("{\"commentEntityPayload\":{}}")!!).value!!.confirmed)
    }
}
