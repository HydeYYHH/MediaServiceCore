package com.liskovsoft.youtubeapi.videoinfo.V2

import java.net.URLDecoder
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class CommentActionTest {
    @Test fun videoCommentsBuildMissingCreationParametersAndRetainExplicitOnes() {
        val requests = mutableListOf<HttpRequest>()
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                requests += request
                assertEquals("Bearer token", request.headers["Authorization"])
                assertEquals("TVHTML5", jsonObject(request.body!!)!!.path("context", "client")!!.text("clientName"))
                return HttpReply(200, """{"actionResult":{"status":"STATUS_SUCCEEDED"}}""")
            }
        }
        val context = RequestContext("account", "token")
        assertTrue(createVideoComment("abcdefghijk", "Hello", null, context, http).value!!.action.confirmed)
        val create = jsonObject(requests.last().body!!)!!.text("createCommentParams")!!
        assertArrayEquals(byteArrayOf(18, 11) + "abcdefghijk".toByteArray() + byteArrayOf(42, 0, 80, 7), decode(create))
        assertTrue(replyVideoComment("abcdefghijk", "parent", "Reply", null, context, http).value!!.action.confirmed)
        val reply = jsonObject(requests.last().body!!)!!.text("createReplyParams")!!
        assertArrayEquals(byteArrayOf(18, 11) + "abcdefghijk".toByteArray() + byteArrayOf(34, 6) + "parent".toByteArray() + byteArrayOf(42, 2, 8, 0, 80, 7), decode(reply))
        createVideoComment("abcdefghijk", "Hello", "provided", context, http)
        assertEquals("provided", jsonObject(requests.last().body!!)!!.text("createCommentParams"))
        val count = requests.size
        assertFalse(createVideoComment("invalid/id", "Hello", null, context, http).ok)
        assertFalse(createVideoComment("abcdefghijk", " ", null, context, http).ok)
        assertFalse(createVideoComment("abcdefghijk", "Hello", null, RequestContext(), http).ok)
        assertEquals(count, requests.size)
    }

    private fun decode(value: String): ByteArray = Base64.getDecoder()
        .decode(URLDecoder.decode(value, "UTF-8"))

    @Test fun returnsCreatedEntitiesAndRejectsBusinessFailures() {
        val good = object : HttpClient { override fun execute(request: HttpRequest): HttpReply {
            assertEquals("Bearer t", request.headers["Authorization"])
            return HttpReply(200, """{"commentRenderer":{"commentId":"created","contentText":{"simpleText":"hello"}}}""")
        }}
        assertEquals("created", createComment("hello", "key", RequestContext("a","t"), good).value!!.comment!!.id)
        val bad = object : HttpClient { override fun execute(request: HttpRequest) = HttpReply(200, """{"actionResults":[{"status":"STATUS_FAILED"}]}""") }
        assertFalse(createComment("hello", "key", RequestContext("a","t"), bad).ok)
        assertFalse(sendComment(createCommentQuery("hello","key",null)) { 200 to "{\"error\":{\"code\":403}}" })
    }
}
