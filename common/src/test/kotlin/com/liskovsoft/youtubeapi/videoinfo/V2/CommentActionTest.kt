package com.liskovsoft.youtubeapi.videoinfo.V2

import java.net.URLDecoder
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class CommentActionTest {
    private fun ratingRenderer(liked: Boolean = false, count: String = "20") = """{"commentRenderer":{
        "commentId":"parent","isLiked":$liked,"voteCount":{"simpleText":"$count"},
        "actionButtons":{"commentActionButtonsRenderer":{
          "likeButton":{"toggleButtonRenderer":{
            "defaultServiceEndpoint":{"performCommentActionEndpoint":{"action":"LIKE"}},
            "toggledServiceEndpoint":{"performCommentActionEndpoint":{"action":"UNLIKE"}}}},
          "dislikeButton":{"toggleButtonRenderer":{
            "defaultServiceEndpoint":{"performCommentActionEndpoint":{"action":"DISLIKE"}},
            "toggledServiceEndpoint":{"performCommentActionEndpoint":{"action":"UNDISLIKE"}}}}
        }}}}"""

    private fun detail() = """{"continuationContents":{"itemSectionContinuation":{"contents":[${ratingRenderer()}]}}}"""

    @Test fun detailCapabilitiesCannotReinterpretAnExplicitRatingIntent() {
        for (vote in CommentVote.values()) {
            val writes = mutableListOf<String>()
            val original = commentPage(ratingRenderer(liked = true, count = "11")).comments.single()
                .copy(likeKey = null, unlikeKey = null, dislikeKey = null, undislikeKey = null, detailKey = "detail",
                    deleteKey = "DELETE", replyCreateKey = "REPLY", replyKey = "replies")
            val http = object : HttpClient {
                override fun execute(request: HttpRequest): HttpReply {
                    return if (request.body!!.contains("\"continuation\"")) HttpReply(200, detail())
                    else {
                        writes += request.body!!
                        HttpReply(200, """{"status":"STATUS_SUCCEEDED"}""")
                    }
                }
            }
            val result = rateComment(original, vote, RequestContext(webSession = WebSession("SAPISID=token")), http).value!!.comment!!
            assertTrue(writes.single().contains("\"actions\":[\"${vote.name}\"]"))
            assertEquals(vote == CommentVote.LIKE, result.liked)
            assertEquals(vote == CommentVote.DISLIKE, result.disliked)
            // The fresh detail count and viewer state form one coherent fallback snapshot.
            assertEquals(if (vote == CommentVote.LIKE) "21" else "20", result.likeCount)
            assertEquals("UNLIKE", result.unlikeKey)
            assertEquals("DELETE", result.deleteKey)
            assertEquals("REPLY", result.replyCreateKey)
            assertEquals("replies", result.replyKey)
            assertEquals("detail", result.detailKey)
        }
    }

    @Test fun legacyTogglesRetainTheirResolvedServerStateCompatibility() {
        val original = commentPage(ratingRenderer(liked = true)).comments.single()
            .copy(unlikeKey = null, detailKey = "detail")
        val requests = mutableListOf<String>()
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                requests += request.body!!
                return HttpReply(200, if (request.body!!.contains("\"continuation\"")) detail()
                    else """{"status":"STATUS_SUCCEEDED"}""")
            }
        }
        assertTrue(likeComment(original, RequestContext(webSession = WebSession("SAPISID=token")), http).value!!.comment!!.liked)
        assertEquals(2, requests.size)
        assertTrue(requests.last().contains("\"actions\":[\"LIKE\"]"))
    }

    @Test fun confirmedServerCountsTakePriorityOverTheLocalRatingDelta() {
        val original = commentPage(ratingRenderer()).comments.single()
        val http = object : HttpClient {
            override fun execute(request: HttpRequest) = HttpReply(200,
                """{"status":"STATUS_SUCCEEDED","commentRenderer":{"commentId":"parent","voteCount":{"simpleText":"923"}}}""")
        }
        val confirmed = rateComment(original, CommentVote.LIKE, RequestContext(webSession = WebSession("SAPISID=token")), http).value!!.comment!!
        assertTrue(confirmed.liked)
        assertEquals("923", confirmed.likeCount)
    }

    @Test fun videoCommentsBuildMissingCreationParametersAndRetainExplicitOnes() {
        val requests = mutableListOf<HttpRequest>()
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                requests += request
                assertTrue(request.headers["Authorization"]!!.startsWith("SAPISIDHASH "))
                assertEquals("WEB", jsonObject(request.body!!)!!.path("context", "client")!!.text("clientName"))
                return HttpReply(200, """{"actionResult":{"status":"STATUS_SUCCEEDED"}}""")
            }
        }
        val context = RequestContext(accountId = "account", webSession = WebSession("SAPISID=token"))
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
            assertTrue(request.headers["Authorization"]!!.startsWith("SAPISIDHASH "))
            return HttpReply(200, """{"commentRenderer":{"commentId":"created","contentText":{"simpleText":"hello"}}}""")
        }}
        assertEquals("created", createComment("hello", "key", RequestContext(accountId = "a", webSession = WebSession("SAPISID=t")), good).value!!.comment!!.id)
        val bad = object : HttpClient { override fun execute(request: HttpRequest) = HttpReply(200, """{"actionResults":[{"status":"STATUS_FAILED"}]}""") }
        assertFalse(createComment("hello", "key", RequestContext(accountId = "a", webSession = WebSession("SAPISID=t")), bad).ok)
        assertFalse(sendComment(createCommentQuery("hello","key",null)) { 200 to "{\"error\":{\"code\":403}}" })
    }
}
