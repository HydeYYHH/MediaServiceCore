package com.liskovsoft.youtubeapi.videoinfo.V2

import java.net.URI
import java.net.URLEncoder
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class ActionAttestationTest {
    private val video = "abcdefghijk"
    private val context = RequestContext(webSession = WebSession("SAPISID=fixture"))
    private val command = """{"runAttestationCommand":{"challenge":"synthetic-private-attestation"}}"""
    private class Wire(private val body: String, private val status: Int = 200) : HttpClient {
        val requests = ArrayList<HttpRequest>()
        override fun execute(request: HttpRequest): HttpReply {
            requests += request
            return HttpReply(status, body)
        }
    }
    private fun assertChallenge(body: String) {
        val wire = Wire(body)
        val result = voteVideo(video, VideoVote.DISLIKE, context, wire)
        assertFalse(body, result.ok)
        // The failed overall operation retains any mutation acknowledgement for recovery.
        assertNotNull(result.value)
        val error = result.error!!
        assertEquals(RequestFailure.VERIFICATION, error.kind)
        assertEquals("attestation_required", error.code)
        assertEquals(200, error.status)
        assertNull(error.detail)
        assertNull(error.cause)
        assertFalse(error.toString().contains("synthetic-private-attestation"))
        val request = wire.requests.single()
        assertEquals("/youtubei/v1/like/dislike", URI(request.url).path)
        assertEquals(video, jsonObject(request.body)!!.obj("target")!!.text("videoId"))
        assertEquals("SAPISID=fixture", request.headers["Cookie"])
        assertFalse(request.body.contains("synthetic-private-attestation"))
    }

    @Test fun mutationCommandEnvelopesReturnVerificationAfterExactlyOneVoteSubmission() {
        assertChallenge(command)
        assertChallenge("""{"command":$command}""")
        for (envelope in listOf("actions", "onResponseReceivedActions", "onResponseReceivedEndpoints", "onResponseReceivedCommands"))
            assertChallenge("""{"$envelope":[$command]}""")
        assertChallenge("""{"actions":[{"innertubeCommand":$command}]}""")
        assertChallenge("""{"command":{"commandExecutorCommand":{"commands":[
            {"unknownCommand":{}},{"innertubeCommand":{"commandExecutorCommand":{"commands":[$command]}}}]}}}""")
        assertChallenge("""{"innertubeCommand":{"commandExecutorCommand":{"commands":[$command]}}}""")
    }

    @Test fun apparentSuccessAndMatchingEntityStateCannotAcknowledgeAnAttestationChallenge() {
        for (success in listOf("\"status\":\"SUCCESS\"", "\"success\":true",
            "\"actionResults\":[{\"status\":\"STATUS_SUCCEEDED\"}]",
            "\"commentRenderer\":{\"commentId\":\"created\"}", matchingRating())) {
            assertChallenge("""{$success,"actions":[$command]}""")
        }
    }

    @Test fun arbitraryDiagnosticToastAndPayloadNestingIsNotACommandScope() {
        val unrelated = listOf(
            """{"diagnostic":$command}""",
            """{"diagnostic":{"actions":[$command]}}""",
            """{"diagnostic":{"innertubeCommand":$command}}""",
            """{"actions":[{"showToastCommand":{"toast":$command}}]}""",
            """{"actions":[{"unknownCommand":$command}]}""",
            """{"actions":[{"commandMetadata":$command}]}""",
            """{"commandExecutorCommand":{"payload":{"commands":[$command]}}}""",
            """{"actions":[{"innertubeCommand":{"payload":$command}}]}"""
        )
        for (body in unrelated) {
            val wire = Wire(body)
            val result = voteVideo(video, VideoVote.DISLIKE, context, wire)
            assertTrue(body, result.ok)
            assertFalse(result.value!!.confirmed)
            assertEquals(1, wire.requests.size)
        }
        // Ordinary matching state still works when only unrelated diagnostics contain the same technical name.
        val confirmed = voteVideo(video, VideoVote.DISLIKE, context,
            Wire("""{${matchingRating()},"diagnostic":$command}"""))
        assertTrue(confirmed.value!!.confirmed)
    }

    @Test fun topLevelApiAndHttpErrorsRemainHigherPriorityThanAChallenge() {
        val denied = Wire("""{"error":{"code":403,"status":"PERMISSION_DENIED"},"actions":[$command]}""")
        val api = voteVideo(video, VideoVote.DISLIKE, context, denied)
        val apiFailure = requireNotNull(api.error)
        assertEquals(RequestFailure.AUTH, apiFailure.kind)
        assertEquals("PERMISSION_DENIED", apiFailure.code)
        assertEquals(403, apiFailure.status)
        assertEquals(1, denied.requests.size)
        val stringError = voteVideo(video, VideoVote.DISLIKE, context, Wire("""{"error":"refused","actions":[$command]}"""))
        val stringFailure = requireNotNull(stringError.error)
        assertEquals(RequestFailure.HTTP, stringFailure.kind)
        assertEquals("refused", stringFailure.code)
        assertEquals(200, stringFailure.status)
        val unavailable = Wire("""{"actions":[$command]}""", 503)
        val http = voteVideo(video, VideoVote.DISLIKE, context, unavailable)
        val httpFailure = requireNotNull(http.error)
        assertEquals(RequestFailure.HTTP, httpFailure.kind)
        assertEquals("http_503", httpFailure.code)
        assertEquals(503, httpFailure.status)
        assertEquals(1, unavailable.requests.size)
    }

    @Test fun malformedCommandNamesAndUnknownToastsDoNotManufactureVerificationOrConfirmation() {
        for (body in listOf(
            """{"actions":[{"runAttestationCommand":null}]}""",
            """{"runAttestationCommand":"synthetic-private-attestation"}""",
            """{"actions":[{"commandExecutorCommand":{"commands":"runAttestationCommand"}}]}""",
            """{"actions":[{"innertubeCommand":[$command]}]}""",
            """{"actions":[{"addToToastAction":{"item":{"notificationTextRenderer":{"success":true}}}}]}"""
        )) {
            val result = voteVideo(video, VideoVote.DISLIKE, context, Wire(body))
            assertTrue(body, result.ok)
            assertFalse(result.value!!.confirmed)
        }
    }

    @Test fun anonymousAndInvalidVotesDoNotSubmitAndOrdinaryReadTransportIsUnchanged() {
        val noIo = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply = error("Validation must happen before a mutation")
        }
        assertEquals(RequestFailure.AUTH, voteVideo(video, VideoVote.DISLIKE, RequestContext(), noIo).error?.kind)
        assertEquals(RequestFailure.INVALID, voteVideo("", VideoVote.DISLIKE, context, noIo).error?.kind)
        val wire = Wire("""{"unseenCount":4,"actions":[$command]}""")
        val read = loadNoticeCount(context, wire)
        assertEquals(4L, read.value!!.unseenCount)
        assertEquals(1, wire.requests.size)
        assertEquals("/youtubei/v1/notification/get_unseen_count", URI(wire.requests.single().url).path)
    }

    private fun matchingRating(): String {
        val key = URLEncoder.encode(Base64.getEncoder().encodeToString(byteArrayOf(18, video.length.toByte()) +
            video.toByteArray(Charsets.UTF_8) + byteArrayOf(32, 62, 40, 1)), "UTF-8")
        return """"frameworkUpdates":{"entityBatchUpdate":{"mutations":[{"entityKey":"$key",
            "type":"ENTITY_MUTATION_TYPE_REPLACE","payload":{"likeStatusEntity":{"key":"$key","likeStatus":"DISLIKE"}}}]}}"""
    }
}
