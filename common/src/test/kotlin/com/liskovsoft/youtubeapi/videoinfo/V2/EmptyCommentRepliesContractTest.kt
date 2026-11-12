package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class EmptyCommentRepliesContractTest {
    private val context = RequestContext(webSession = WebSession("SAPISID=fixture"))
    private class Wire(val response: String) : HttpClient {
        val requests = ArrayList<HttpRequest>()
        override fun execute(request: HttpRequest): HttpReply { requests += request; return HttpReply(200, response) }
    }
    @Test fun aScopedReplyAppendWithoutItsEmptyProtobufListIsARealReplyEndOnly() {
        val body = terminal("comment-replies-item-UgExample_parent-1")
        val wire = Wire(body)
        val replies = loadCommentReplies("opaque-replies", context, wire)
        assertTrue(replies.ok)
        assertTrue(replies.value!!.comments.isEmpty()); assertNull(replies.value!!.nextKey); assertNull(replies.value!!.createKey)
        assertEquals("opaque-replies", jsonObject(wire.requests.single().body)!!.text("continuation"))
        assertEquals("SAPISID=fixture", wire.requests.single().headers["Cookie"])
        val root = loadComments("video", context, continuation = "opaque-root", http = Wire(body))
        assertEquals("unknown_comment_page", root.error?.code)
        assertTrue(commentPage(body).comments.isEmpty())
    }
    @Test fun unknownTargetsNullListsExtraPayloadsAndUnscopedOrNestedAppendsRemainParseFailures() {
        val valid = jsonObject(terminal("comment-replies-item-UgParent"))!!
        val bad = listOf("comments-section", "unknown", "comment-replies-item", "comment-replies-item-",
            "comment-replies-item-/private", "comment-replies-item-Ug\nParent").map(::terminal) + listOf(
            terminal("comment-replies-item-UgParent", ",\"continuationItems\":null"),
            terminal("comment-replies-item-UgParent", ",\"unknownPayload\":{}"),
            Json.Obj(valid.fields + ("contents" to Json.Obj(emptyMap()))).encode(),
            Json.Obj(valid.fields + ("onResponseReceivedEndpoints" to Json.Arr(listOf(
                valid.arr("onResponseReceivedEndpoints").single(), Json.Obj(emptyMap()))))).encode(),
            Json.Obj(mapOf("onResponseReceivedActions" to valid.fields.getValue("onResponseReceivedEndpoints"))).encode(),
            Json.Obj(mapOf("unknown" to valid)).encode())
        for (body in bad) {
            val result = loadCommentReplies("opaque", context, Wire(body))
            assertEquals(RequestFailure.PARSE, result.error?.kind)
            assertEquals("unknown_comment_page", result.error?.code)
        }
    }
    @Test fun explicitEmptyListsRetainTheGeneralContinuationContract() {
        val wire = Wire("""{"onResponseReceivedEndpoints":[{"appendContinuationItemsAction":{"continuationItems":[]}}]}""")
        assertTrue(loadCommentReplies("opaque", context, wire).ok)
        assertTrue(loadComments("video", context, continuation = "opaque-root", http = wire).ok)
    }
    private fun terminal(target: String, extra: String = "") = """{"responseContext":{},"trackingParams":"opaque",
        "onResponseReceivedEndpoints":[{"clickTrackingParams":"opaque","appendContinuationItemsAction":{"targetId":${jsonQuote(target)}$extra}}]}"""
}
