package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class ActionStateTest {
    @Test fun preservesHttpStatusWithoutIncludingUnknownResponseFields() {
        val context = RequestContext("account", "token")
        val http = object : HttpClient {
            override fun execute(request: HttpRequest) = HttpReply(200, """{"responseContext":{},"private":"secret"}""")
        }
        val result = requireNotNull(voteVideo("v", VideoVote.LIKE, context, http).value)
        assertEquals(200, result.httpStatus)
        assertEquals("responseContext", result.responseShape)
        assertFalse(result.responseShape.orEmpty().contains("secret"))
    }
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

    @Test fun nestedResultsConfirmOnlyCompletedActionsAndNeverMaskARefusal() {
        val created = actionReply(jsonObject("""{"actions":[{"actionResult":{"status":"STATUS_SUCCEEDED"}}]}""")!!)
        assertTrue(created.value!!.confirmed)
        assertEquals("STATUS_SUCCEEDED", created.value!!.status)
        val refused = actionReply(jsonObject("""{"commentRenderer":{"commentId":"old"},"actionResult":{"status":"STATUS_FAILED"}}""")!!)
        assertFalse(refused.ok)
        val pending = actionReply(jsonObject("""{"actionResult":{"status":"PENDING"},"commentRenderer":{"commentId":"old"}}""")!!)
        assertFalse(pending.value!!.confirmed)
        val mixed = actionReply(jsonObject("""{"actionResults":[{"status":"STATUS_SUCCEEDED"}],"actions":[{"actionResult":{"status":"STATUS_FAILED"}}]}""")!!)
        assertFalse(mixed.ok)
        val malformed = actionReply(jsonObject("""{"actionResults":[{"status":"STATUS_SUCCEEDED"},42]}""")!!)
        assertFalse(malformed.value!!.confirmed)
    }
}
