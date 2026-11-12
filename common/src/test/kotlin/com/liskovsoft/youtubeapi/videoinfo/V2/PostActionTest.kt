package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class PostActionTest {
    @Test fun pollChoicesKeepTheWebSelectedStateImagesPercentagesAndFeedbackTokens() {
        val poll = postPoll(jsonObject("""{"choices":[{"text":{"runs":[{"text":"Choice"}]},"selected":true,
          "image":{"thumbnails":[{"url":"https://image"}]},"votePercentage":{"simpleText":"60%"},
          "selectServiceEndpoint":{"feedbackEndpoint":{"feedbackToken":"SELECT","clientActions":[{"updateBackstagePollAction":{"totalVotes":{"simpleText":"1001 votes"}}}]}},
          "deselectServiceEndpoint":{"feedbackEndpoint":{"feedbackToken":"DESELECT"}}}],
          "totalVotes":{"simpleText":"1K votes"}}""")!!)
        assertTrue(poll.voted)
        val choice = poll.choices.single()
        assertEquals("Choice", choice.text)
        assertEquals("https://image", choice.image)
        assertEquals("60%", choice.percentage)
        assertEquals("feedback", choice.select?.endpoint)
        assertEquals("1001 votes", choice.select?.pollTotal)
        assertEquals("\"feedbackTokens\":[\"DESELECT\"]", choice.deselect?.payload)
    }

    @Test fun authorizedPostCommentsStayWebAndRetainTheirActionTokens() {
        val context = RequestContext(webSession = WebSession("SAPISID=fixture"), generation = 4)
        var called = false
        val reply = loadPostComments("comments", context, object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                called = true
                assertTrue(request.body.contains("\"clientName\":\"WEB\""))
                assertTrue(request.headers["Authorization"]!!.startsWith("SAPISIDHASH "))
                assertEquals("SAPISID=fixture", request.headers["Cookie"])
                return HttpReply(200, """{"createCommentParams":"CREATE","continuationContents":{"itemSectionContinuation":{"contents":[]}}}""")
            }
        })
        assertTrue(called)
        assertEquals("CREATE", reply.value?.createKey)
    }

    @Test fun aBareCreationTokenWithoutAnOwnedCommentCollectionDoesNotGrantPostCreation() {
        var calls = 0
        val reply = loadPostComments("comments", RequestContext(webSession = WebSession("SAPISID=fixture")), object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                calls++
                return HttpReply(200, """{"createCommentParams":"UNSCOPED_CREATE"}""")
            }
        })
        assertEquals(1, calls)
        assertFalse(reply.ok)
        assertEquals(RequestFailure.PARSE, reply.error?.kind)
        assertNull(reply.value)
    }

    @Test fun pollFeedbackRequiresExplicitSuccessAndDoesNotTreatHttp200AsConfirmation() {
        val context = RequestContext(webSession = WebSession("SAPISID=fixture"))
        val command = postCommand(jsonObject("""{"feedbackEndpoint":{"feedbackToken":"SELECT"}}"""))!!
        val response = performPostCommand(command, context, object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                assertTrue(request.url.endsWith("/feedback"))
                assertTrue(request.body.contains("\"feedbackTokens\":[\"SELECT\"]"))
                return HttpReply(200, """{"actionResults":[{"feedback":"FEEDBACK_SELECT","status":"STATUS_SUCCEEDED"}]}""")
            }
        })
        assertTrue(response.value!!.confirmed)
        val unconfirmed = performPostCommand(command, context, object : HttpClient {
            override fun execute(request: HttpRequest) = HttpReply(200, """{"responseContext":{}}""")
        })
        assertFalse(unconfirmed.value!!.confirmed)
    }

    @Test fun aPostVoteUpdateMustMatchThePostAndRequestedRating() {
        val post = PostItem("UCauthor", "Ugkx_post", null, null, null, null, emptyList(), "10", null, voteStatus = "LIKE")
        fun reply(id: String, status: String) = ActionReply(null, """{"actions":[{"updateCommentVoteAction":{
          "commentId":"$id","voteStatus":"$status","voteCount":{"simpleText":"11"}}}]}""")
        assertEquals("11", updatedPost(reply("Ugkx_post", "LIKE"), post)?.likes)
        assertNull(updatedPost(reply("other", "LIKE"), post))
        assertNull(updatedPost(reply("Ugkx_post", "DISLIKE"), post))
    }

    @Test fun arbitraryUrlsCannotBeUsedAsPostActionEndpoints() {
        assertNull(postCommand(jsonObject("""{"commandMetadata":{"webCommandMetadata":{"apiUrl":"https://other/feedback"}},
          "arbitraryEndpoint":{"token":"secret"}}""")))
        var calls = 0
        val result = performPostCommand(PostCommand("account/delete", "\"token\":\"secret\""),
            RequestContext(webSession = WebSession("SAPISID=fixture")), object : HttpClient {
                override fun execute(request: HttpRequest): HttpReply { calls++; error("No request expected") }
            })
        assertFalse(result.ok)
        assertEquals(0, calls)
    }
}
