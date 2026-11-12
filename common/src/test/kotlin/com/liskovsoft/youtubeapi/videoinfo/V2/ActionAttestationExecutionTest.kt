package com.liskovsoft.youtubeapi.videoinfo.V2

import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class ActionAttestationExecutionTest {
    private val video = "abcdefghijk"
    private val channel = "UC" + "a".repeat(22)
    private val opaqueChallenge = "challenge+/=\\\"\u96ea\n"
    private val opaqueSnapshot = "snapshot+/=\\\"\u96ea\n"
    private val program = "opaque-program+/="
    private val javascript = "window.trayride = { fixture: 'interpreter' };"
    private val ids = """["opaque+/=",{"id":"nested\\value","sequence":[1,true,null]}]"""
    private val command = """{"runAttestationCommand":{"engagementType":"ENGAGEMENT_TYPE_LIKE","ids":$ids}}"""

    private fun context(provider: ActionAttestationProvider? = null, index: Int = 1, brand: String? = "brand-fixture") =
        RequestContext(accountId = "account-fixture", visitor = "visitor-fixture", language = "ja", region = "JP",
            generation = 47, onBehalfOfUser = brand,
            webSession = WebSession("SAPISID=cookie-fixture; SID=other-fixture", index),
            actionAttestationProvider = provider)

    private fun mutation(confirmed: Boolean = true, challengeCommand: String = command) =
        """{${if (confirmed) "\"success\":true," else ""}"responseContext":{},"actions":[$challengeCommand]}"""

    private fun challenge(guard: String = """"botguardData":{"program":${jsonQuote(program)},
        "interpreterSafeScript":{"privateDoNotAccessOrElseSafeScriptWrappedValue":${jsonQuote(javascript)}}}""") =
        """{"challenge":${jsonQuote(opaqueChallenge)},$guard}"""

    private class Wire : HttpClient {
        val requests = mutableListOf<HttpRequest>()
        val events = mutableListOf<String>()
        var mutation = HttpReply(200, "{}")
        var get = HttpReply(200, "{}")
        var log = HttpReply(200, """{"responseContext":{}}""")
        var script = HttpReply(200, "interpreter-source")
        var opening: String? = null
        var comments: String? = null
        override fun execute(request: HttpRequest): HttpReply {
            requests += request
            val path = URI(request.url).path
            events += "${request.method} $path"
            return when {
                request.method == "GET" -> script
                path.endsWith("/att/get") -> get
                path.endsWith("/att/log") -> log
                path.endsWith("/next") && opening != null -> HttpReply(200, opening!!)
                path.endsWith("/browse") && comments != null -> HttpReply(200, comments!!)
                else -> mutation
            }
        }
    }

    private fun wire(confirmed: Boolean = true) = Wire().also {
        it.mutation = HttpReply(200, mutation(confirmed))
        it.get = HttpReply(200, challenge())
    }

    private fun body(request: HttpRequest) = requireNotNull(jsonObject(request.body))
    private fun path(request: HttpRequest) = URI(request.url).path
    private fun assertOneMutation(wire: Wire, endpoint: String = "like/like") =
        assertEquals(1, wire.requests.count { path(it) == "/youtubei/v1/$endpoint" })

    @Test fun inlineInterpreterKeepsOpaqueBindingAndTheOriginalAccountContextAcrossTheProtocol() {
        val wire = wire()
        var supplied: ActionAttestationChallenge? = null
        val context = context(ActionAttestationProvider {
            wire.events += "snapshot"
            supplied = it
            opaqueSnapshot
        })

        val result = voteVideo(video, VideoVote.LIKE, context, wire)

        assertTrue(result.ok)
        assertTrue(result.value!!.confirmed)
        assertEquals(200, result.value!!.httpStatus)
        assertEquals("completed", result.value!!.metadata["attestation"])
        assertEquals(listOf("POST /youtubei/v1/like/like", "POST /youtubei/v1/att/get", "snapshot",
            "POST /youtubei/v1/att/log"), wire.events)
        val (mutation, get, log) = wire.requests
        for (request in listOf(mutation, get, log)) {
            assertEquals("POST", request.method)
            assertEquals("1", request.headers["X-Goog-AuthUser"])
            assertEquals("brand-fixture", request.headers["X-Goog-PageId"])
            assertEquals(context.webSession!!.cookies, request.headers["Cookie"])
            assertTrue(request.headers["Authorization"]!!.startsWith("SAPISIDHASH "))
            assertEquals("visitor-fixture", request.headers["X-Goog-Visitor-Id"])
            assertEquals(mutation.headers, request.headers)
            assertEquals(body(mutation).obj("context")!!.encode(), body(request).obj("context")!!.encode())
            assertFalse(request.headers.values.any { opaqueChallenge in it || opaqueSnapshot in it || javascript in it })
        }
        assertEquals("WEB", body(get).path("context", "client")!!.text("clientName"))
        assertEquals("ja", body(get).path("context", "client")!!.text("hl"))
        assertEquals("JP", body(get).path("context", "client")!!.text("gl"))
        assertEquals("brand-fixture", body(get).path("context", "user")!!.text("onBehalfOfUser"))
        assertEquals("ENGAGEMENT_TYPE_LIKE", body(get).text("engagementType"))
        assertEquals(jsonObject("""{"ids":$ids}""")!!.fields["ids"]!!.encode(), body(get).fields["ids"]!!.encode())
        assertEquals(setOf("engagementType", "ids", "context"), body(get).fields.keys)
        val challenge = requireNotNull(supplied)
        assertEquals(program, challenge.program)
        assertEquals("trayride", challenge.globalName)
        assertEquals(javascript, challenge.interpreterJavascript)
        assertEquals(mapOf("atr_challenge" to opaqueChallenge), challenge.contentBinding)
        assertEquals(mutation.headers["User-Agent"], challenge.userAgent)
        assertEquals(47L, challenge.generation)
        assertEquals(opaqueChallenge, body(log).text("challenge"))
        assertEquals(opaqueSnapshot, body(log).text("webResponse"))
        assertEquals(setOf("context", "challenge", "webResponse"), body(log).fields.keys)
        for (diagnostic in listOf(challenge.toString(), mutation.toString(), get.toString(), log.toString(), result.value.toString())) {
            assertFalse(diagnostic.contains(opaqueChallenge))
            assertFalse(diagnostic.contains(opaqueSnapshot))
            assertFalse(diagnostic.contains("cookie-fixture"))
            assertFalse(diagnostic.contains(program))
        }
        assertOneMutation(wire)
    }

    @Test fun serverEntityTrackingInfoIsTransferredWithoutReinterpretingItsOpaqueShape() {
        val tracking = """{"opaque":["+/=",{"field":null}],"version":7}"""
        val wire = wire()
        wire.mutation = HttpReply(200, mutation(challengeCommand = """{"runAttestationCommand":{
            "engagementType":"ENGAGEMENT_TYPE_LIKE","ids":$ids,"entityTrackingInfo":$tracking}}"""))
        val result = voteVideo(video, VideoVote.LIKE, context(ActionAttestationProvider { opaqueSnapshot }), wire)
        assertTrue(result.ok)
        val get = body(wire.requests[1])
        assertEquals(setOf("context", "entityTrackingInfo"), get.fields.keys)
        assertEquals(jsonObject(tracking)!!.encode(), get.fields["entityTrackingInfo"]!!.encode())
        assertOneMutation(wire)
    }

    @Test fun downloadedFirstPartyInterpretersReceiveOnlyTheOriginalUserAgent() {
        for (url in listOf("//www.gstatic.com/fixture.js", "https://www.google.com:443/fixture.js",
            "https://www.youtube.com/fixture.js")) {
            val wire = wire()
            wire.get = HttpReply(200, challenge(""""bgChallenge":{"program":${jsonQuote(program)},"globalName":"fixtureRuntime",
                "interpreterSafeUrl":{"privateDoNotAccessOrElseTrustedResourceUrlWrappedValue":${jsonQuote(url)}}}"""))
            var supplied: ActionAttestationChallenge? = null
            val result = voteVideo(video, VideoVote.LIKE, context(ActionAttestationProvider {
                wire.events += "snapshot"
                supplied = it
                opaqueSnapshot
            }), wire)
            assertTrue(url, result.ok)
            val source = wire.requests.single { it.method == "GET" }
            assertEquals(if (url.startsWith("//")) "https:$url" else url, source.url)
            assertEquals(mapOf("User-Agent" to wire.requests.first().headers.getValue("User-Agent")), source.headers)
            assertEquals("", source.body)
            assertEquals("fixtureRuntime", supplied!!.globalName)
            assertEquals("interpreter-source", supplied!!.interpreterJavascript)
            assertEquals(listOf("POST /youtubei/v1/like/like", "POST /youtubei/v1/att/get", "GET /fixture.js",
                "snapshot", "POST /youtubei/v1/att/log"), wire.events)
            assertEquals(wire.requests.first().headers, wire.requests.last().headers)
            assertOneMutation(wire)
        }
    }

    @Test fun unsafeInterpreterLocationsNeverReceiveCredentialsOrInvokeTheProvider() {
        val forbidden = listOf("https://attacker.test/fixture.js", "http://www.google.com/fixture.js",
            "https://www.google.com.attacker.test/fixture.js", "https://user:password@www.google.com/fixture.js",
            "https://www.gstatic.com:444/fixture.js", "https://www.youtube.com/fixture.js#fragment", "file:///fixture.js")
        for (url in forbidden) {
            val wire = wire()
            wire.get = HttpReply(200, challenge(""""bgChallenge":{"program":${jsonQuote(program)},"globalName":"runtime",
                "interpreterUrl":${jsonQuote(url)}}"""))
            var calls = 0
            val result = voteVideo(video, VideoVote.LIKE, context(ActionAttestationProvider { calls++; opaqueSnapshot }), wire)
            assertFalse(result.ok)
            assertTrue(result.value!!.confirmed)
            assertEquals("attestation_interpreter_origin", result.error!!.code)
            assertEquals(0, calls)
            assertEquals(2, wire.requests.size)
            assertTrue(wire.requests.none { it.method == "GET" })
            assertOneMutation(wire)
        }
    }

    @Test fun providerFailureKeepsTheMutationAcknowledgementWithoutExposingTheExceptionOrReplaying() {
        val wire = wire()
        val result = voteVideo(video, VideoVote.LIKE, context(ActionAttestationProvider {
            throw IllegalStateException("private-provider-detail-$opaqueChallenge")
        }), wire)
        assertFalse(result.ok)
        assertTrue(result.value!!.confirmed)
        assertEquals(200, result.value!!.httpStatus)
        assertEquals("pending", result.value!!.metadata["attestation"])
        assertEquals("true", result.value!!.metadata["mutation_confirmed"])
        assertEquals("attestation_failed", result.error!!.code)
        assertNull(result.error!!.cause)
        assertNull(result.error!!.detail)
        assertFalse(result.error.toString().contains("private-provider-detail"))
        assertEquals(2, wire.requests.size)
        assertOneMutation(wire)
    }

    @Test fun invalidSnapshotsStopBeforeLogAndRetainTheOriginalAcknowledgement() {
        for (snapshot in listOf(" ", "x".repeat(2 * 1024 * 1024 + 1))) {
            val wire = wire()
            val result = voteVideo(video, VideoVote.LIKE, context(ActionAttestationProvider { snapshot }), wire)
            assertFalse(result.ok)
            assertEquals("invalid_attestation_response", result.error!!.code)
            assertTrue(result.value!!.confirmed)
            assertEquals(2, wire.requests.size)
            assertOneMutation(wire)
        }
    }

    @Test fun getAndLogFailuresRetainTheMutationHttpStatusAndNeverResubmitIt() {
        for (failedStep in listOf("get", "log")) {
            val wire = wire()
            if (failedStep == "get") wire.get = HttpReply(503, "{}") else wire.log = HttpReply(503, "{}")
            val result = voteVideo(video, VideoVote.LIKE, context(ActionAttestationProvider { opaqueSnapshot }), wire)
            assertFalse(result.ok)
            assertTrue(result.value!!.confirmed)
            assertEquals(200, result.value!!.httpStatus)
            assertEquals(503, result.error!!.status)
            assertEquals(RequestFailure.HTTP, result.error!!.kind)
            assertEquals(if (failedStep == "get") 2 else 3, wire.requests.size)
            assertOneMutation(wire)
        }
    }

    @Test fun interpreterDownloadFailureStopsBeforeSnapshotAndLogWithoutLosingTheMutationAcknowledgement() {
        val wire = wire()
        wire.get = HttpReply(200, challenge(""""bgChallenge":{"program":${jsonQuote(program)},"globalName":"runtime",
            "interpreterUrl":"https://www.gstatic.com/fixture.js"}"""))
        wire.script = HttpReply(404, "missing")
        var calls = 0
        val result = voteVideo(video, VideoVote.LIKE, context(ActionAttestationProvider { calls++; opaqueSnapshot }), wire)
        assertFalse(result.ok)
        assertTrue(result.value!!.confirmed)
        assertEquals(200, result.value!!.httpStatus)
        assertEquals("attestation_interpreter_http", result.error!!.code)
        assertEquals(404, result.error!!.status)
        assertEquals(0, calls)
        assertEquals(3, wire.requests.size)
        assertOneMutation(wire)
    }

    @Test fun unknownRejectedAndRepeatedLogRepliesDoNotReplayAnAlreadyAcknowledgedMutation() {
        val replies = listOf("{}" to "unknown_attestation_acknowledgement",
            """{"responseContext":null}""" to "unknown_attestation_acknowledgement",
            """{"responseContext":"unknown"}""" to "unknown_attestation_acknowledgement",
            """{"trackingParams":""}""" to "unknown_attestation_acknowledgement",
            """{"trackingParams":{}}""" to "unknown_attestation_acknowledgement",
            """{"runtimeResult":"unknown"}""" to "unknown_attestation_acknowledgement",
            """{"success":false}""" to "attestation_rejected", """{"actions":[$command]}""" to "attestation_rejected")
        for ((reply, code) in replies) {
            val wire = wire()
            wire.log = HttpReply(200, reply)
            val result = voteVideo(video, VideoVote.LIKE, context(ActionAttestationProvider { opaqueSnapshot }), wire)
            assertFalse(result.ok)
            assertEquals(code, result.error!!.code)
            assertTrue(result.value!!.confirmed)
            assertEquals(3, wire.requests.size)
            assertOneMutation(wire)
        }
    }

    @Test fun successfulAttestationDoesNotUpgradeAnUnknownMutationAcknowledgement() {
        val wire = wire(confirmed = false)
        wire.log = HttpReply(200, """{"success":true}""")
        val result = voteVideo(video, VideoVote.LIKE, context(ActionAttestationProvider { opaqueSnapshot }), wire)
        assertTrue(result.ok)
        assertFalse(result.value!!.confirmed)
        assertEquals("false", result.value!!.metadata["mutation_confirmed"])
        assertEquals("completed", result.value!!.metadata["attestation"])
        assertEquals(3, wire.requests.size)
        assertOneMutation(wire)
    }

    @Test fun absentProviderPreservesBothConfirmedAndUnknownMutationResultsWithoutExtraRequests() {
        for (confirmed in listOf(true, false)) {
            val wire = wire(confirmed)
            val result = voteVideo(video, VideoVote.LIKE, context(), wire)
            assertFalse(result.ok)
            assertEquals(confirmed, result.value!!.confirmed)
            assertEquals("attestation_required", result.error!!.code)
            assertEquals(200, result.value!!.httpStatus)
            assertEquals(1, wire.requests.size)
            assertOneMutation(wire)
        }
    }

    @Test fun allDirectMutationFamiliesPassTheirAccountContextToAttestation() {
        val operations: List<Pair<String, (RequestContext, HttpClient) -> ReadResult<*>>> = listOf(
            "like/like" to { c: RequestContext, h: HttpClient -> voteVideo(video, VideoVote.LIKE, c, h) },
            "subscription/subscribe" to { c: RequestContext, h: HttpClient -> subscribeChannel(channel, true, c, http = h) },
            "comment/create_comment" to { c: RequestContext, h: HttpClient -> createComment("Test", "create-fixture", c, h) },
            "notification/record_interactions" to { c: RequestContext, h: HttpClient -> hideNotice("interaction-fixture", c, h) },
            "notification/modify_channel_preference" to { c: RequestContext, h: HttpClient -> setNoticeState("preference-fixture", c, h) },
            "like/dislike" to { c: RequestContext, h: HttpClient -> performPostCommand(PostCommand("like/dislike", "\"target\":{\"postId\":\"post-fixture\"}"), c, h) },
            "history/clear_search_history" to { c: RequestContext, h: HttpClient -> SearchHistory().clearRemote(c, h) })
        for ((endpoint, operation) in operations) {
            val wire = wire()
            var supplied: ActionAttestationChallenge? = null
            val result = operation(context(ActionAttestationProvider { supplied = it; opaqueSnapshot }), wire)
            assertTrue(endpoint, result.ok)
            assertNotNull(endpoint, supplied)
            assertEquals(47L, supplied!!.generation)
            assertEquals(3, wire.requests.size)
            assertOneMutation(wire, endpoint)
            assertEquals(wire.requests.first().headers, wire.requests[1].headers)
            assertEquals("1", wire.requests.last().headers["X-Goog-AuthUser"])
            assertEquals("brand-fixture", wire.requests.last().headers["X-Goog-PageId"])
        }
    }

    @Test fun ordinaryPrimaryAndSecondaryAccountsKeepTheirOwnIndexWithoutAcquiringBrandHeaders() {
        for (index in listOf(0, 1)) {
            val wire = wire()
            val context = context(ActionAttestationProvider { opaqueSnapshot }, index = index, brand = null)
            val result = voteVideo(video, VideoVote.LIKE, context, wire)
            assertTrue(result.ok)
            for (request in wire.requests) {
                assertEquals(index.toString(), request.headers["X-Goog-AuthUser"])
                assertNull(request.headers["X-Goog-PageId"])
                assertNull(body(request).path("context", "user")?.text("onBehalfOfUser"))
            }
            assertOneMutation(wire)
        }
    }

    @Test fun commentRatingRetainsTheAttestationErrorAlongsideItsUpdatedAcknowledgedComment() {
        val original = Comment("comment-fixture", "Author", "Text", null, "1", false, null, null,
            "like-fixture", "unlike-fixture", "delete-fixture", "reply-fixture", emptyList(), null, null, false)
        val wire = wire()
        val result = rateComment(original, CommentVote.LIKE, context(ActionAttestationProvider { error("provider-fixture") }), wire)
        assertFalse(result.ok)
        assertEquals("attestation_failed", result.error!!.code)
        assertTrue(result.value!!.action.confirmed)
        assertEquals(200, result.value!!.action.httpStatus)
        assertTrue(result.value!!.comment!!.liked)
        assertEquals(2, wire.requests.size)
        assertOneMutation(wire, "comment/perform_comment_action")
    }

    @Test fun chatAttestationFailuresRetainTheAcknowledgedMessageAndCannotBecomePostingRateLimits() {
        for (providerFails in listOf(true, false)) {
            val wire = wire()
            wire.mutation = HttpReply(200, """{"actions":[$command,{"addChatItemAction":{"item":{
                "liveChatTextMessageRenderer":{"id":"message-fixture","message":{"simpleText":"hello"}}}}}]}""")
            wire.log = HttpReply(429, """{"error":{"status":"RESOURCE_EXHAUSTED"}}""")
            val result = sendChatMessage("address-fixture", "hello", context(ActionAttestationProvider {
                if (providerFails) error("provider-fixture") else opaqueSnapshot
            }), wire, clientMessageId = "message-id-fixture")
            assertFalse(result.ok)
            assertTrue(result.value!!.confirmed)
            assertEquals(200, result.value!!.httpStatus)
            assertEquals(if (providerFails) "attestation_failed" else "http_429", result.error!!.code)
            assertEquals(if (providerFails) 200 else 429, result.error!!.status)
            assertNotEquals("chat_rate_limited", result.error!!.code)
            assertEquals(if (providerFails) 2 else 3, wire.requests.size)
            assertOneMutation(wire, "live_chat/send_message")
        }
    }

    @Test fun discussionDeleteUsesTheSameAccountContextAndDoesNotReplayAfterProviderFailure() {
        val wire = wire()
        val context = context(ActionAttestationProvider { error("provider-fixture") })
        wire.opening = """{"videoDetails":{"videoId":"$video","title":"Video"},
            "engagementPanelSectionListRenderer":{"panelIdentifier":"engagement-panel-comments-section","content":{
                "continuationItemRenderer":{"continuationEndpoint":{"commandMetadata":{"webCommandMetadata":{
                    "apiUrl":"/youtubei/v1/browse"}},"continuationCommand":{"token":"comments-fixture"}}}}}}"""
        wire.comments = """{"continuationContents":{"itemSectionContinuation":{"contents":[{"commentRenderer":{
            "commentId":"comment-fixture","contentText":{"simpleText":"Comment"},"actionMenu":{"menuRenderer":{"items":[{
                "menuServiceItemRenderer":{"icon":{"iconType":"DELETE"},"serviceEndpoint":{
                    "performCommentActionEndpoint":{"action":"delete-fixture"}}}}]}}}}]}}}"""
        val thread = DiscussionSession(AccountSession(context), wire).openVideo(video).value!!
        val handle = thread.load().value!!.comments.single()
        assertTrue(handle.permissions.canDelete)
        val result = thread.delete(handle)
        assertFalse(result.ok)
        assertEquals("attestation_failed", result.error!!.code)
        assertNotNull(result.value)
        assertEquals(ActionState.CONFIRMED, result.value!!.state)
        assertEquals(200, result.value!!.httpStatus)
        assertOneMutation(wire, "comment/perform_comment_action")
        assertEquals(4, wire.requests.size)
        assertEquals("1", wire.requests.last().headers["X-Goog-AuthUser"])
        assertEquals("brand-fixture", wire.requests.last().headers["X-Goog-PageId"])
    }
}
