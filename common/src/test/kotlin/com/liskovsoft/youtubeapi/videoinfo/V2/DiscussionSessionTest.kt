package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class DiscussionSessionTest {
    private class Wire(val session: AccountSession = AccountSession(RequestContext(accountId = "owner",
        webSession = WebSession("SAPISID=fixture", 2), generation = 7))) : HttpClient {
        val requests = ArrayList<HttpRequest>()
        var opening = watch()
        val pages = hashMapOf("root" to page(comment(), next = "next", sorts = true),
            "next" to page(comment("second")), "sorted" to page(comment(liked = true, suffix = "-fresh", writable = false), sorts = true),
            "replies" to page(comment("child", parent = "parent"), "reply-next"), "reply-next" to page(""),
            "detail" to page(comment(liked = true, suffix = "-detail")))
        var action = """{"status":"STATUS_SUCCEEDED"}"""
        var onRequest: ((HttpRequest, Json.Obj) -> Unit)? = null
        override fun execute(request: HttpRequest): HttpReply {
            requests += request
            val root = jsonObject(request.body)!!
            onRequest?.invoke(request, root)
            if (request.url.substringBefore('?').endsWith("account/accounts_list")) return HttpReply(200,
                """{"accountItem":{"accountId":"owner","isSelected":true}}""")
            val continuation = root.text("continuation")
            return HttpReply(200, if (continuation != null) pages[continuation] ?: error("Unexpected continuation")
                else if (root.text("videoId") != null) opening else action)
        }
        fun open(): CommentThread = DiscussionSession(session, this).openVideo("video").value!!
    }

    @Test fun hostConsumesPresentationAndOpaqueServerCapabilitiesWithoutProvidingTokens() {
        val wire = Wire()
        val thread = wire.open()
        val page = thread.load().value!!
        val handle = page.comments.single()
        val content = handle.content
        assertEquals("parent", content.id); assertEquals("Ada", content.author); assertEquals("UCauthor", content.authorId)
        assertEquals("https://image.test/author", content.authorPhoto); assertEquals("Hello", content.text)
        assertEquals("Now", content.published); assertEquals("20", content.likeCount); assertEquals("INDIFFERENT", content.viewerRating)
        assertNull(content.parentId); assertEquals("2", content.replyCount)
        assertTrue(content.isCreator); assertTrue(content.isVerified); assertTrue(content.isPinned); assertEquals(true, content.isHearted)
        assertTrue(content.runs.single().bold)
        val permissions = handle.permissions
        assertTrue(permissions.canReply); assertTrue(permissions.canDelete); assertTrue(permissions.canLoadReplies)
        assertTrue(permissions.canResolveActions); assertTrue(permissions.canRate)
        assertEquals(CommentVote.values().toSet(), permissions.votes)
        assertEquals("45", page.total); assertFalse(page.turnedOff); assertTrue(page.canCreate)
        assertEquals(listOf("服务器顺序", "最新服务器选项"), page.sortOrders.map { it.title })
        assertTrue(page.sortOrders.first().selected); assertFalse(page.sortOrders.last().selected)
        assertNotNull(page.next)
        assertEquals(listOf("next", "browse"), wire.requests.map { it.url.substringBefore('?').substringAfterLast('/') })
        for (request in wire.requests) {
            assertEquals("SAPISID=fixture", request.headers["Cookie"])
            assertEquals("2", request.headers["X-Goog-AuthUser"])
            assertTrue(request.headers["Authorization"]!!.startsWith("SAPISIDHASH "))
            assertEquals("WEB", jsonObject(request.body)!!.path("context", "client")!!.text("clientName"))
        }
        for (value in listOf(thread, page, handle, content, permissions, page.next!!, page.sortOrders.first())) {
            assertFalse(value.toString(), value.toString().contains("root") || value.toString().contains("LIKE-token") || value.toString().contains("sorted"))
        }
    }

    @Test fun serverSortRefreshesExistingHandlesAndExpiresEarlierPageCursors() {
        val wire = Wire()
        val thread = wire.open()
        val first = thread.load().value!!
        val original = first.comments.single()
        assertEquals("second", thread.next(first.next!!).value!!.comments.single().content.id)
        val sorted = thread.sort(first.sortOrders.last()).value!!
        assertEquals("parent", sorted.comments.single().content.id)
        assertEquals("LIKE", original.content.viewerRating)
        assertFalse(original.permissions.canDelete); assertFalse(original.permissions.canReply)
        assertFalse(original.permissions.canResolveActions); assertEquals(setOf(CommentVote.LIKE), original.permissions.votes)
        assertEquals("next", wire.requests.last().url.substringBefore('?').substringAfterLast('/'))
        val count = wire.requests.size
        assertEquals("discussion_page_changed", thread.next(first.next!!).error?.code)
        assertEquals("discussion_page_changed", thread.sort(first.sortOrders.first()).error?.code)
        assertEquals("missing_comment_delete_capability", thread.delete(original).error?.code)
        assertEquals(count, wire.requests.size)
        val rated = thread.rate(original, CommentVote.LIKE).value!!
        assertTrue(rated.confirmed); assertEquals(ActionState.CONFIRMED, rated.state); assertEquals(200, rated.httpStatus)
        assertEquals("LIKE", rated.comment!!.content.viewerRating)
        assertEquals("LIKE-token-fresh", (jsonObject(wire.requests.last().body)!!.arr("actions").single() as Json.Str).value)
    }

    @Test fun repliesAndTheirNextPageFollowTheirOwnServerDeclaredProtocol() {
        val wire = Wire()
        val thread = wire.open()
        val parent = thread.load().value!!.comments.single()
        val replies = thread.replies(parent).value!!
        assertEquals("child", replies.comments.single().content.id)
        assertEquals("parent", replies.comments.single().content.parentId)
        assertTrue(replies.sortOrders.isEmpty())
        assertEquals("browse", wire.requests.last().url.substringBefore('?').substringAfterLast('/'))
        val last = thread.next(replies.next!!).value!!
        assertTrue(last.comments.isEmpty()); assertNull(last.next)
        assertEquals("next", wire.requests.last().url.substringBefore('?').substringAfterLast('/'))
    }

    @Test fun deletingTheLastReplyCanReturnAnOmittedReplyListWithoutMakingRootPagesEmptySuccesses() {
        val wire = Wire()
        val terminal = """{"responseContext":{},"trackingParams":"opaque","onResponseReceivedEndpoints":[{
            "appendContinuationItemsAction":{"targetId":"comment-replies-item-parent"}}]}"""
        wire.pages["replies"] = terminal
        wire.pages["next"] = terminal
        val thread = wire.open()
        val root = thread.load().value!!
        val replies = thread.replies(root.comments.single())
        assertTrue(replies.ok); assertTrue(replies.value!!.comments.isEmpty()); assertNull(replies.value!!.next)
        assertEquals("unknown_comment_page", thread.next(root.next!!).error?.code)
    }

    @Test fun eachExplicitWriteUsesTheOwnedCapabilityExactlyOnceAndPreservesUnknownOutcomes() {
        val wire = Wire()
        val thread = wire.open()
        val parent = thread.load().value!!.comments.single()
        wire.action = """{"commentRenderer":{"commentId":"created","contentText":{"simpleText":"New comment"}}}"""
        val created = thread.create("New comment").value!!
        assertTrue(created.confirmed); assertEquals("created", created.comment!!.content.id)
        assertEquals("create-root", jsonObject(wire.requests.last().body)!!.text("createCommentParams"))
        wire.action = """{"status":"STATUS_SUCCEEDED"}"""
        assertTrue(thread.reply(parent, "Reply").value!!.confirmed)
        assertEquals("reply-create", jsonObject(wire.requests.last().body)!!.text("createReplyParams"))
        val before = parent.content.viewerRating
        wire.action = "{}"
        val count = wire.requests.size
        val unknown = thread.rate(parent, CommentVote.LIKE).value!!
        assertFalse(unknown.confirmed); assertEquals(ActionState.UNCONFIRMED, unknown.state)
        assertEquals(before, parent.content.viewerRating); assertEquals(count + 1, wire.requests.size)
        assertFalse(thread.delete(parent).value!!.confirmed); assertTrue(parent.permissions.canDelete)
        wire.action = """{"status":"STATUS_SUCCEEDED"}"""
        val deleted = thread.delete(parent).value!!
        assertTrue(deleted.confirmed); assertNull(deleted.comment)
        assertFalse(parent.permissions.canDelete); assertFalse(parent.permissions.canRate)
        assertEquals("comment_handle_expired", thread.reply(parent, "After delete").error?.code)
        assertEquals("CommentMutation(state=CONFIRMED)", deleted.toString())
    }

    @Test fun freshRootReadsReplacePermissionsRatherThanResurrectingOldDeleteOrReplyTokens() {
        val wire = Wire()
        val thread = wire.open()
        val parent = thread.load().value!!.comments.single()
        wire.pages["root"] = page(comment(writable = false, suffix = "-new"))
        val refreshed = thread.load().value!!
        assertEquals("parent", refreshed.comments.single().content.id)
        assertFalse(parent.permissions.canDelete); assertFalse(parent.permissions.canReply)
        assertTrue(refreshed.canCreate)
        val count = wire.requests.size
        assertEquals("missing_comment_reply_capability", thread.reply(parent, "No permission").error?.code)
        assertEquals("missing_comment_delete_capability", thread.delete(parent).error?.code)
        assertEquals(count, wire.requests.size)
    }

    @Test fun replacementPagesReleaseDisappearedCommentsWithoutRevivingTheirHandlesOrCursors() {
        val wire = Wire()
        val thread = wire.open()
        val first = thread.load().value!!
        val original = first.comments.single()
        // Continuations retain the currently loaded parent and add their own handles.
        val second = thread.next(first.next!!).value!!.comments.single()
        assertEquals("second", second.content.id)
        assertTrue(original.permissions.canDelete)
        repeat(200) { index ->
            wire.pages["root"] = page(comment("replacement-$index"), next = "next", sorts = true)
            assertEquals("replacement-$index", thread.load().value!!.comments.single().content.id)
        }
        fun retainedEntries(name: String): Int = (CommentThread::class.java.getDeclaredField(name).apply {
            isAccessible = true
        }.get(thread) as Map<*, *>).size
        assertEquals(1, retainedEntries("entries"))
        assertEquals(1, retainedEntries("identities"))
        assertEquals("parent", original.content.id) // Presentation snapshots remain safe to display.
        assertFalse(original.permissions.canDelete)
        assertFalse(second.permissions.canReply)
        val before = wire.requests.size
        assertEquals("comment_handle_expired", thread.delete(original).error?.code)
        assertEquals("discussion_page_changed", thread.next(first.next!!).error?.code)
        assertEquals(before, wire.requests.size)

        // Returning the same server ID later creates a new capability, not an old grant resurrection.
        wire.pages["root"] = page(comment())
        val current = thread.load().value!!.comments.single()
        assertTrue(current.permissions.canDelete)
        assertFalse(original.permissions.canDelete)
        assertEquals("comment_handle_expired", thread.reply(original, "No old permission").error?.code)
        assertEquals(1, retainedEntries("entries"))
        assertEquals(1, retainedEntries("identities"))
    }

    @Test fun tvDetailReadsResolveCapabilitiesWithoutChangingTheCallersVoteIntent() {
        val wire = Wire()
        wire.pages["root"] = page(comment(detailOnly = true))
        val thread = wire.open()
        val parent = thread.load().value!!.comments.single()
        assertFalse(parent.permissions.canRate); assertTrue(parent.permissions.canResolveActions)
        val before = wire.requests.size
        val result = thread.rate(parent, CommentVote.LIKE).value!!
        assertTrue(result.confirmed)
        assertEquals(before + 2, wire.requests.size)
        assertEquals("browse", wire.requests[before].url.substringBefore('?').substringAfterLast('/'))
        assertEquals("LIKE-token-detail", (jsonObject(wire.requests.last().body)!!.arr("actions").single() as Json.Str).value)
    }

    @Test fun aMissingDetailTargetNeverWritesAnotherCommentsAction() {
        val wire = Wire()
        wire.pages["root"] = page(comment(detailOnly = true))
        wire.pages["detail"] = page(comment("unrelated"))
        val thread = wire.open()
        val parent = thread.load().value!!.comments.single()
        val before = wire.requests.size
        assertEquals("missing_comment_action_target", thread.rate(parent, CommentVote.LIKE).error?.code)
        assertEquals(before + 1, wire.requests.size)
    }

    @Test fun logoutDuringADetailReadPreventsTheFollowingWriteAndLateRootPagesAreNotDelivered() {
        val wire = Wire()
        wire.pages["root"] = page(comment(detailOnly = true))
        val thread = wire.open()
        val parent = thread.load().value!!.comments.single()
        wire.onRequest = { _, body -> if (body.text("continuation") == "detail") wire.session.clear() }
        val before = wire.requests.size
        assertEquals("session_changed", thread.rate(parent, CommentVote.LIKE).error?.code)
        assertEquals(before + 1, wire.requests.size)
        assertFalse(parent.permissions.canRate); assertFalse(parent.permissions.canLoadReplies)
        val late = Wire()
        val opened = late.open()
        late.onRequest = { _, body -> if (body.text("continuation") == "root") late.session.clear() }
        assertEquals("session_changed", opened.load().error?.code)
    }

    @Test fun mutationSubmittedBeforeLogoutIsNeverRetriedOrDeliveredAsCurrent() {
        val wire = Wire()
        val thread = wire.open()
        val parent = thread.load().value!!.comments.single()
        wire.onRequest = { request, _ -> if (request.url.contains("perform_comment_action")) wire.session.clear() }
        val before = wire.requests.size
        assertEquals("session_changed", thread.delete(parent).error?.code)
        assertEquals(before + 1, wire.requests.size)
    }

    @Test fun handlesOrdersAndCursorsCannotBeTransferredToAnotherThreadEvenOnTheSameAccount() {
        val wire = Wire()
        val first = wire.open()
        val page = first.load().value!!
        val second = wire.open()
        second.load()
        val before = wire.requests.size
        assertEquals("foreign_comment_handle", second.replies(page.comments.single()).error?.code)
        assertEquals("foreign_comment_handle", second.reply(page.comments.single(), "No").error?.code)
        assertEquals("foreign_comment_handle", second.rate(page.comments.single(), CommentVote.LIKE).error?.code)
        assertEquals("foreign_comment_handle", second.delete(page.comments.single()).error?.code)
        assertEquals("foreign_comment_cursor", second.next(page.next!!).error?.code)
        assertEquals("foreign_comment_sort", second.sort(page.sortOrders.first()).error?.code)
        assertEquals(before, wire.requests.size)
    }

    @Test fun constructorsCannotForgeIssuedCapabilitiesEvenWithTheCorrectOwnerAndRevision() {
        val wire = Wire()
        val thread = wire.open()
        val page = thread.load().value!!
        val actual = page.comments.single()
        val forgedCursor = CommentCursor(thread, page.next!!.revision, DiscussionRoute(YOUTUBE_API + "search", "forged"), false)
        val order = page.sortOrders.last()
        val forgedOrder = CommentSortOrder(thread, order.revision, order.route, order.title, order.selected)
        val forgedHandle = CommentHandle(thread, actual.entry, actual.content)
        val before = wire.requests.size
        assertEquals("unissued_comment_cursor", thread.next(forgedCursor).error?.code)
        assertEquals("unissued_comment_sort", thread.sort(forgedOrder).error?.code)
        assertEquals("unissued_comment_handle", thread.replies(forgedHandle).error?.code)
        assertEquals("unissued_comment_handle", thread.reply(forgedHandle, "No").error?.code)
        assertEquals("unissued_comment_handle", thread.rate(forgedHandle, CommentVote.LIKE).error?.code)
        assertEquals("unissued_comment_handle", thread.delete(forgedHandle).error?.code)
        assertFalse(forgedHandle.permissions.canRate); assertFalse(forgedHandle.permissions.canLoadReplies)
        assertEquals(before, wire.requests.size)
        assertTrue(thread.next(page.next!!).ok)
        assertTrue(actual.permissions.canDelete)
    }

    @Test fun cookieRenewalKeepsTheThreadButUsesTheFreshCredentials() {
        val wire = Wire()
        val thread = wire.open()
        val page = thread.load().value!!
        assertTrue(wire.session.update(WebSession("SAPISID=renewed", 2), wire).ok)
        val result = thread.next(page.next!!)
        assertTrue(result.ok)
        assertEquals("SAPISID=renewed", wire.requests.last().headers["Cookie"])
        assertEquals(7L, wire.session.snapshot().generation)
    }

    @Test fun optionalWatchOpeningsRequireOwnerGenerationAndMatchingVideoAndRefreshMissingRoutingMetadata() {
        val wire = Wire()
        val opening = wire.session.execute { loadWatch("video", it, http = wire) }.value!!
        val factory = DiscussionSession(wire.session, wire)
        assertTrue(factory.openVideo("video", opening).ok)
        assertEquals(2, wire.requests.size)
        assertEquals("comment_video_mismatch", factory.openVideo("other", opening).error?.code)
        assertEquals("foreign_discussion_opening", DiscussionSession(AccountSession(wire.session.snapshot()), wire).openVideo("video", opening).error?.code)
        wire.session.clear()
        assertEquals("session_changed", factory.openVideo("video", opening).error?.code)
        assertEquals(2, wire.requests.size)
    }

    @Test fun unsupportedDestinationsAreNeverGuessedAndNoMissingCreationTokenIsFabricated() {
        val wire = Wire()
        wire.opening = watch("https://external.test/youtubei/v1/browse")
        val unsupported = DiscussionSession(wire.session, wire).openVideo("video")
        assertEquals(RequestFailure.PARSE, unsupported.error?.kind)
        assertEquals("unknown_comment_opening", unsupported.error?.code)
        assertEquals(1, wire.requests.size)
        val missing = Wire()
        missing.opening = """{"videoDetails":{"videoId":"video"},"messageRenderer":{"text":{"simpleText":"Comments are turned off"}}}"""
        val thread = missing.open()
        val off = thread.load().value!!
        assertTrue(off.turnedOff); assertTrue(off.comments.isEmpty()); assertFalse(off.canCreate); assertNull(off.next)
        assertEquals("missing_comment_creation_capability", thread.create("Cannot create").error?.code)
        assertEquals(1, missing.requests.size)
        assertEquals("invalid_comment_text", thread.create(" ").error?.code)
    }

    @Test fun anonymousDiscussionReadsRemainUsableAndWritesRequireAuthentication() {
        val wire = Wire(AccountSession())
        val thread = wire.open()
        val page = thread.load().value!!
        val parent = page.comments.single()
        assertFalse(page.canCreate); assertFalse(parent.permissions.canReply); assertFalse(parent.permissions.canDelete)
        assertTrue(parent.permissions.canLoadReplies); assertTrue(parent.permissions.votes.isEmpty())
        val count = wire.requests.size
        assertEquals("sign_in_required", thread.create("text").error?.code)
        assertEquals("sign_in_required", thread.reply(parent, "text").error?.code)
        assertEquals("sign_in_required", thread.rate(parent, CommentVote.LIKE).error?.code)
        assertEquals("sign_in_required", thread.delete(parent).error?.code)
        assertEquals(count, wire.requests.size)
        assertTrue(wire.requests.all { it.headers["Authorization"] == null })
    }

    @Test fun javaFactoryOverloadsHaveTheExpectedDefaultTransportAndOpeningContract() {
        val wire = Wire()
        val type = DiscussionSession::class.java
        val injected = type.getConstructor(AccountSession::class.java, HttpClient::class.java).newInstance(wire.session, wire)
        val short = type.getMethod("openVideo", String::class.java).invoke(injected, "video") as ReadResult<*>
        assertTrue(short.ok)
        val full = type.getMethod("openVideo", String::class.java, AccountResult::class.java).invoke(injected, "video", null) as ReadResult<*>
        assertTrue(full.ok)
        val defaults = type.getConstructor(AccountSession::class.java).newInstance(wire.session)
        assertEquals(RequestFailure.INVALID, defaults.openVideo("bad id").error?.kind)
        assertEquals(2, wire.requests.size)
    }

    private companion object {
        fun command(key: String, endpoint: String = "/youtubei/v1/next") = """{"commandMetadata":{"webCommandMetadata":{"apiUrl":"$endpoint"}},"continuationCommand":{"token":"$key"}}"""
        fun watch(endpoint: String = "/youtubei/v1/browse") = """{"videoDetails":{"videoId":"video","title":"Video"},
            "commentsEntryPointRenderer":{"commentCount":{"simpleText":"45"}},
            "engagementPanelSectionListRenderer":{"panelIdentifier":"engagement-panel-comments-section","content":{
            "continuationItemRenderer":{"continuationEndpoint":${command("root", endpoint)}}}}}"""
        fun page(comments: String, next: String? = null, sorts: Boolean = false): String {
            val rows = listOfNotNull(comments.takeIf { it.isNotBlank() }, next?.let {
                """{"continuationItemRenderer":{"continuationEndpoint":${command(it)}}}"""
            }).joinToString(",")
            val options = if (!sorts) "" else """, "sortFilterSubMenuRenderer":{"subMenuItems":[
                {"title":"服务器顺序","selected":true,"serviceEndpoint":${command("root", "/youtubei/v1/browse")}},
                {"title":"最新服务器选项","selected":false,"serviceEndpoint":${command("sorted")}}]}"""
            return """{"continuationContents":{"itemSectionContinuation":{"contents":[$rows]}},
                "createCommentEndpoint":{"createCommentParams":"create-root"}$options}"""
        }
        fun comment(id: String = "parent", liked: Boolean = false, suffix: String = "", writable: Boolean = true,
            detailOnly: Boolean = false, parent: String? = null): String {
            fun objectOf(vararg fields: Pair<String, Json>) = Json.Obj(linkedMapOf(*fields))
            fun text(value: String) = objectOf("simpleText" to Json.Str(value))
            fun action(value: String) = objectOf("performCommentActionEndpoint" to objectOf("action" to Json.Str(value)))
            val fields = linkedMapOf<String, Json>(
                "commentId" to Json.Str(id), "authorText" to text("Ada"),
                "authorEndpoint" to objectOf("browseEndpoint" to objectOf("browseId" to Json.Str("UCauthor"))),
                "authorThumbnail" to objectOf("thumbnails" to Json.Arr(listOf(objectOf("url" to Json.Str("https://image.test/author"))))),
                "contentText" to objectOf("runs" to Json.Arr(listOf(objectOf("text" to Json.Str("Hello"), "bold" to Json.Bool(true))))),
                "publishedTimeText" to text("Now"), "voteCount" to text("20"), "isLiked" to Json.Bool(liked), "isDisliked" to Json.Bool(false),
                "replyCount" to Json.Str("2"), "isCreator" to Json.Bool(true), "isVerified" to Json.Bool(true),
                "isPinned" to Json.Bool(true), "isHearted" to Json.Bool(true))
            parent?.let { fields["parentCommentId"] = Json.Str(it) }
            if (!detailOnly) {
                val like = linkedMapOf<String, Json>("defaultServiceEndpoint" to action("LIKE-token$suffix"))
                if (writable) like["toggledServiceEndpoint"] = action("UNLIKE-token$suffix")
                val buttons = linkedMapOf<String, Json>("likeButton" to objectOf("toggleButtonRenderer" to Json.Obj(like)))
                if (writable) buttons["dislikeButton"] = objectOf("toggleButtonRenderer" to objectOf("isToggled" to Json.Bool(false),
                    "defaultServiceEndpoint" to action("DISLIKE-token$suffix"), "toggledServiceEndpoint" to action("UNDISLIKE-token$suffix")))
                fields["actionButtons"] = objectOf("commentActionButtonsRenderer" to Json.Obj(buttons))
            }
            if (writable || detailOnly) {
                val section = objectOf("itemSectionRenderer" to objectOf(
                    "commandMetadata" to objectOf("webCommandMetadata" to objectOf("apiUrl" to Json.Str("/youtubei/v1/browse"))),
                    "continuations" to Json.Arr(listOf(objectOf("reloadContinuationData" to objectOf("continuation" to Json.Str("detail")))))))
                val panel = objectOf("engagementPanelSectionListRenderer" to objectOf("content" to objectOf(
                    "sectionListRenderer" to objectOf("contents" to Json.Arr(listOf(section))))))
                fields["detailViewEndpoint"] = objectOf("showEngagementPanelEndpoint" to objectOf("engagementPanel" to panel))
            }
            if (writable && !detailOnly) {
                fields["createReplyParams"] = Json.Str("reply-create")
                fields["replies"] = objectOf("commentRepliesRenderer" to objectOf("continuationEndpoint" to jsonObject(command("replies", "/youtubei/v1/browse"))!!))
                val remove = objectOf("menuServiceItemRenderer" to objectOf("icon" to objectOf("iconType" to Json.Str("DELETE")),
                    "serviceEndpoint" to action("DELETE-token$suffix")))
                fields["actionMenu"] = objectOf("menuRenderer" to objectOf("items" to Json.Arr(listOf(remove))))
            }
            return objectOf("commentRenderer" to Json.Obj(fields)).encode()
        }
    }
}
