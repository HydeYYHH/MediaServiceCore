package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class CommentMutationEnvelopeTest {
    private val video = "abcdefghijk"
    private val context = RequestContext(accountId = "account-fixture", webSession = WebSession("SAPISID=fixture"), generation = 17)
    private fun json(value: String) = requireNotNull(jsonObject(value))
    private fun creation(contents: String = modernContents()) = """{"clickTrackingParams":"tracking-fixture",
        "createCommentAction":{"contents":$contents}}"""
    private fun modernContents(id: String = "created-comment", key: String = "comment-key") = """{
        "commentThreadRenderer":{"trackingParams":"tracking-fixture","renderingPriority":"RENDERING_PRIORITY_HIGH",
            "commentViewModel":{"commentViewModel":{"commentKey":"$key","commentId":"$id",
                "toolbarSurfaceKey":"toolbar-key","toolbarStateKey":"state-key","commentSurfaceKey":"surface-key"}}}}"""
    private fun replyContents(): String = Json.Obj(mapOf("commentViewModel" to
        json(modernContents()).path("commentThreadRenderer", "commentViewModel", "commentViewModel")!!)).encode()
    private fun replyCreation(contents: String = replyContents(), targets: String = "\"parentCommentId\":\"parent-comment\"") =
        """{"clickTrackingParams":"tracking-fixture","createCommentReplyAction":{"contents":$contents,$targets}}"""
    private fun entity(key: String, role: String, body: String) =
        """{"entityKey":"$key","type":"ENTITY_MUTATION_TYPE_REPLACE","payload":{"$role":$body}}"""
    private fun framework(commentId: String = "created-comment", key: String = "comment-key") = """{
        "entityBatchUpdate":{"mutations":[
            ${entity(key, "commentEntityPayload", """{"key":"$key","properties":{"commentId":"$commentId",
                "content":{"content":"Created content"}},"author":{"displayName":"Author","channelId":"UCfixture"},
                "toolbar":{"likeCountNotliked":"0"}}""")},
            ${entity("state-key", "engagementToolbarStateEntityPayload", """{"key":"state-key","likeState":"TOOLBAR_LIKE_STATE_INDIFFERENT"}""")},
            ${entity("surface-key", "commentSurfaceEntityPayload", """{"key":"surface-key"}""")},
            ${entity("toolbar-key", "engagementToolbarSurfaceEntityPayload", """{"key":"toolbar-key","menuCommand":{
                "innertubeCommand":{"menuEndpoint":{"menu":{"menuRenderer":{"items":[{"menuServiceItemRenderer":{
                    "icon":{"iconType":"DELETE"},"serviceEndpoint":{"commandMetadata":{"webCommandMetadata":{
                        "apiUrl":"/youtubei/v1/comment/perform_comment_action","sendPost":true}},
                        "performCommentActionEndpoint":{"action":"delete-created"}}}}]}}}}}}""")}
        ]}}"""
    private fun response(actions: String = creation(), entities: String = framework()) =
        """{"responseContext":{},"actionResult":{"status":"STATUS_SUCCEEDED"},"actions":[$actions],"frameworkUpdates":$entities}"""

    private inner class Wire(var creationResponse: String) : HttpClient {
        val requests = mutableListOf<HttpRequest>()
        var listedComment: String? = null
        var firstPage: CommentThreadPage? = null
        override fun execute(request: HttpRequest): HttpReply {
            requests += request
            val response = when (request.url.substringBefore('?')) {
                YOUTUBE_API + "comment/create_comment", YOUTUBE_API + "comment/create_comment_reply" -> creationResponse
                YOUTUBE_API + "comment/perform_comment_action" -> """{"status":"STATUS_SUCCEEDED"}"""
                YOUTUBE_API + "next" -> """{"videoDetails":{"videoId":"$video"},
                    "engagementPanelSectionListRenderer":{"panelIdentifier":"engagement-panel-comments-section","content":{
                        "continuationItemRenderer":{"continuationEndpoint":{"commandMetadata":{"webCommandMetadata":{
                            "apiUrl":"/youtubei/v1/browse"}},"continuationCommand":{"token":"comments-root"}}}}}}"""
                YOUTUBE_API + "browse" -> """{"continuationContents":{"itemSectionContinuation":{"contents":[${listedComment.orEmpty()}]}},
                    "createCommentEndpoint":{"createCommentParams":"create-fixture"}}"""
                else -> error("Unexpected request")
            }
            return HttpReply(200, response)
        }
        fun thread() = DiscussionSession(AccountSession(context), this).openVideo(video).value!!.also {
            firstPage = it.load().value!!
            assertTrue(firstPage!!.canCreate)
        }
        fun create() = createComment("Created content", "create-fixture", context, this)
    }

    @Test fun declaredCreateActionJoinsOnlyItsModernViewAndMatchingEntities() {
        val result = Wire(response()).create()
        assertTrue(result.error.toString(), result.ok)
        assertTrue(result.value!!.action.confirmed)
        val comment = requireNotNull(result.value!!.comment)
        assertEquals("created-comment", comment.id)
        assertEquals("Created content", comment.text)
        assertEquals("Author", comment.author)
        assertEquals("delete-created", comment.deleteKey)
        assertEquals("INDIFFERENT", comment.viewerRating)
    }

    @Test fun declaredLegacyCreateActionStillReturnsItsOwnComment() {
        val contents = """{"commentThreadRenderer":{"comment":{"commentRenderer":{"commentId":"legacy-created",
            "contentText":{"simpleText":"Legacy content"}}}}}"""
        val result = Wire(response(creation(contents))).create()
        assertTrue(result.ok)
        assertEquals("legacy-created", result.value!!.comment!!.id)
        assertEquals("Legacy content", result.value!!.comment!!.text)
    }

    @Test fun aCreatedDiscussionHandleCanDeleteTheSameCommentWithoutListingItAgain() {
        val wire = Wire(response())
        val thread = wire.thread()
        val result = thread.create("Created content")
        assertTrue(result.error.toString(), result.ok)
        assertTrue(result.value!!.confirmed)
        val handle = requireNotNull(result.value!!.comment)
        assertEquals("created-comment", handle.content.id)
        assertTrue(handle.permissions.canDelete)
        val reads = wire.requests.size
        val deleted = thread.delete(handle)
        assertTrue(deleted.ok)
        assertTrue(deleted.value!!.confirmed)
        assertEquals(reads + 1, wire.requests.size)
        assertEquals(listOf("delete-created"), (json(wire.requests.last().body).fields["actions"] as Json.Arr).items.map { (it as Json.Str).value })
        assertEquals(1, wire.requests.count { "/comment/create_comment" in it.url })
    }

    @Test fun aPostCreationAttestationFailureRetainsTheCreatedHandleForCleanupWithoutReposting() {
        val command = """{"runAttestationCommand":{"engagementType":"ENGAGEMENT_TYPE_COMMENT","ids":[{"commentId":"created-comment"}]}}"""
        val wire = Wire(response("$command,${creation()}"))
        val thread = wire.thread()
        val result = thread.create("Created content")
        assertFalse(result.ok)
        assertEquals("attestation_required", result.error!!.code)
        assertTrue(result.value!!.confirmed)
        val handle = requireNotNull(result.value!!.comment)
        assertEquals("created-comment", handle.content.id)
        assertTrue(handle.permissions.canDelete)
        assertTrue(thread.delete(handle).value!!.confirmed)
        assertEquals(1, wire.requests.count { "/comment/create_comment" in it.url })
        assertEquals(1, wire.requests.count { "/comment/perform_comment_action" in it.url })
    }

    @Test fun declaredReplyActionsAcceptTheOfficialDirectViewAndSubThreadShapes() {
        for (contents in listOf(replyContents(), modernContents())) for (targets in listOf(
            "\"parentCommentId\":\"parent-comment\"", "\"replyToCommentId\":\"parent-comment\"",
            "\"parentCommentId\":\"root-comment\",\"replyToCommentId\":\"parent-comment\"")) {
            val wire = Wire(response(replyCreation(contents, targets)))
            val result = replyComment("Created content", "reply-fixture", context, wire)
            assertTrue(result.error.toString(), result.ok)
            assertTrue(result.value!!.action.confirmed)
            assertEquals("created-comment", result.value!!.comment!!.id)
            assertEquals("Created content", result.value!!.comment!!.text)
            assertEquals("delete-created", result.value!!.comment!!.deleteKey)
            assertEquals(1, wire.requests.size)
        }
    }

    @Test fun aRepliedDiscussionHandleCanBeCleanedUpEvenWhenAttestationRemainsPending() {
        for (pending in listOf(false, true)) {
            val command = """{"runAttestationCommand":{"engagementType":"ENGAGEMENT_TYPE_COMMENT_REPLY","ids":[]}}"""
            val wire = Wire(response(if (pending) "$command,${replyCreation()}" else replyCreation()))
            wire.listedComment = """{"commentRenderer":{"commentId":"parent-comment",
                "contentText":{"simpleText":"Parent"},"createReplyParams":"reply-fixture"}}"""
            val thread = wire.thread()
            val parent = wire.firstPage!!.comments.single()
            assertTrue(parent.permissions.canReply)
            val result = thread.reply(parent, "Created content")
            assertEquals(!pending, result.ok)
            assertEquals(if (pending) "attestation_required" else null, result.error?.code)
            assertTrue(result.value!!.confirmed)
            val reply = requireNotNull(result.value!!.comment)
            assertEquals("created-comment", reply.content.id)
            assertTrue(reply.permissions.canDelete)
            assertTrue(thread.delete(reply).value!!.confirmed)
            assertEquals(1, wire.requests.count { "/comment/create_comment_reply" in it.url })
            assertEquals(1, wire.requests.count { "/comment/perform_comment_action" in it.url })
            assertEquals(4, wire.requests.size)
        }
    }

    @Test fun malformedNestedOrCompetingReplyActionsCannotIssueCommentModels() {
        val reply = replyCreation()
        val cases = listOf(
            response("""{"diagnostic":$reply}"""),
            response("""{"runAttestationCommand":{"challenge":"fixture","payload":$reply}}"""),
            response("""{"createCommentReplyAction":{"contents":${replyContents()}}}"""),
            response(replyCreation(targets = "\"parentCommentId\":null")),
            response(replyCreation(targets = "\"parentCommentId\":\" \"")),
            response(replyCreation(targets = "\"parentCommentId\":\"parent-comment\",\"replyToCommentId\":false")),
            response(replyCreation(contents = """{"commentRenderer":{"commentId":"legacy-reply"}}""")),
            response("$reply,$reply"),
            response("$reply,${creation()}"),
            response("""{"createCommentAction":{"contents":${modernContents()}},
                "createCommentReplyAction":{"contents":${replyContents()},"parentCommentId":"parent-comment"}}"""))
        for (body in cases) {
            val wire = Wire(body)
            val result = replyComment("Created content", "reply-fixture", context, wire)
            assertTrue(result.value!!.action.confirmed)
            assertNull(result.value!!.comment)
            assertEquals(1, wire.requests.size)
        }
    }

    @Test fun metadataDiagnosticsUnknownCommandsAndAttestationPayloadsCannotIssueCreatedHandles() {
        val hidden = creation()
        val bodies = listOf(
            """{"status":"SUCCESS","diagnostic":$hidden,"frameworkUpdates":${framework()}}""",
            """{"status":"SUCCESS","responseContext":{"actions":[$hidden]},"frameworkUpdates":${framework()}}""",
            response("""{"unknownCommand":$hidden}"""),
            response("""{"runAttestationCommand":{"challenge":"fixture","payload":$hidden}}"""),
            response("""{"showToastCommand":{"toast":$hidden}}"""))
        for (body in bodies) {
            val wire = Wire(body)
            val result = wire.thread().create("Created content")
            assertNull(result.value!!.comment)
            assertEquals(1, wire.requests.count { "/comment/create_comment" in it.url })
        }
    }

    @Test fun missingForeignConflictingOrMalformedCreationSourcesDoNotIssueCommentsOrHandles() {
        val duplicate = response("${creation()},${creation()}")
        val directConflict = json(response()).let { Json.Obj(it.fields +
            ("commentRenderer" to json("""{"commentId":"shadow-comment"}"""))) }.encode()
        val continuationConflict = json(response()).let { Json.Obj(it.fields +
            ("continuationContents" to json("""{"itemSectionContinuation":{"contents":[]}}"""))) }.encode()
        val cases = listOf(
            duplicate, directConflict, continuationConflict,
            response(entities = framework(commentId = "foreign-comment")),
            response(entities = framework(key = "foreign-key")),
            response(entities = """{"entityBatchUpdate":{"mutations":[]}}"""),
            response("""{"createCommentAction":{"contents":{"itemSectionRenderer":{"contents":[${modernContents()}]}}}}"""),
            response("""{"createCommentAction":{"contents":null}}"""),
            response("""{"createCommentAction":{"contents":${modernContents()},"diagnostic":${modernContents()}}}"""),
            response("""{"createCommentAction":{"contents":${modernContents()}},"unknownCommand":{}}"""))
        for (body in cases) {
            assertNull(ownedCommentMutation(json(body)))
            val wire = Wire(body)
            val result = wire.thread().create("Created content")
            assertTrue(result.value!!.confirmed)
            assertNull(result.value!!.comment)
            assertEquals(1, wire.requests.count { "/comment/create_comment" in it.url })
        }
    }
}
