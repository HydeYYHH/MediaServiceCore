package com.liskovsoft.youtubeapi.videoinfo.V2
import org.junit.Assert.*
import org.junit.Test
class CommentActionTest {
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
