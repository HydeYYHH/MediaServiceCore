package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

/** Comment parsing and requests stay within the selected response collection. */
class CommentSourceIsolationTest {
    private val context = RequestContext(accountId = "synthetic-account", webSession = WebSession("SAPISID=synthetic"))
    private fun json(value: String) = jsonObject(value)!!
    private fun metadata(root: Json.Obj, value: String) = Json.Obj(root.fields + ("responseContext" to
        json("""{"opaqueMetadata":$value}""")))
    private fun legacy(id: String = "comment", text: String = "shadow", writable: Boolean = true) = """{"commentRenderer":{
        "commentId":"$id","authorText":{"simpleText":"shadow author"},"authorEndpoint":{"browseEndpoint":{"browseId":"UCshadow"}},
        "contentText":{"simpleText":"$text"}${if (writable) """, "createReplyParams":"shadow-reply","actionMenu":{"menuRenderer":{"items":[
            {"menuServiceItemRenderer":{"icon":{"iconType":"DELETE"},"serviceEndpoint":{"performCommentActionEndpoint":{"action":"shadow-delete"}}}}]}}""" else ""}}}"""
    private fun mutation(key: String, role: String, value: String, type: String = "ENTITY_MUTATION_TYPE_REPLACE") =
        """{"entityKey":"$key","type":"$type","payload":{"$role":$value}}"""
    private fun entity(id: String = "comment", author: String = "UCcanonical", text: String = "canonical") =
        """{"key":"key","properties":{"commentId":"$id","content":{"content":"$text"}},"author":{"channelId":"$author","displayName":"canonical author"},"toolbar":{"likeCountNotliked":"7"}}"""
    private fun modern(entities: String = mutation("key", "commentEntityPayload", entity()),
        view: String = """{"commentKey":"key","commentId":"comment","toolbarSurfaceKey":"surface"}""") = json("""{
        "responseContext":{},"onResponseReceivedEndpoints":[
          {"reloadContinuationItemsCommand":{"slot":"RELOAD_CONTINUATION_SLOT_HEADER","targetId":"engagement-panel-comments-section","continuationItems":[{"commentsHeaderRenderer":{}}]}},
          {"reloadContinuationItemsCommand":{"slot":"RELOAD_CONTINUATION_SLOT_BODY","targetId":"engagement-panel-comments-section","continuationItems":[
            {"commentThreadRenderer":{"commentViewModel":{"commentViewModel":$view}}}]}}],
        "frameworkUpdates":{"entityBatchUpdate":{"mutations":[$entities]}}}""")
    private fun page(items: String) = """{"continuationContents":{"itemSectionContinuation":{"contents":[$items]}}}"""
    private fun opening() = """{"currentVideoEndpoint":{"watchEndpoint":{"videoId":"abcdefghijk"}},"videoDetails":{"videoId":"abcdefghijk"},
        "engagementPanelSectionListRenderer":{"panelIdentifier":"engagement-panel-comments-section","content":{
          "continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"root"}}}}}}"""
    private inner class Wire(var document: String, var opened: String = opening()) : HttpClient {
        val requests = ArrayList<HttpRequest>()
        var actionReply = """{"status":"STATUS_SUCCEEDED"}"""
        var detail = page(legacy("other", writable = false))
        val writes get() = requests.count { "/comment/" in it.url }
        override fun execute(request: HttpRequest): HttpReply {
            requests += request
            val root = json(request.body)
            return HttpReply(200, when {
                "/comment/" in request.url -> actionReply
                root.text("videoId") != null -> opened
                root.text("continuation") == "detail" -> detail
                else -> document
            })
        }
        fun thread() = DiscussionSession(AccountSession(context), this).openVideo("abcdefghijk").value!!
    }
    private fun canonical(root: Json.Obj) {
        val wire = Wire(root.encode())
        val loaded = loadComments("abcdefghijk", context, "root", wire)
        assertTrue(loaded.error.toString(), loaded.ok)
        assertEquals("canonical", loaded.value!!.comments.single().text)
        assertEquals("UCcanonical", loaded.value!!.comments.single().authorId)
        assertEquals("7", loaded.value!!.comments.single().likeCount)
        val thread = wire.thread()
        val page = thread.load().value!!
        val handle = page.comments.single()
        assertEquals("canonical", handle.content.text); assertEquals("UCcanonical", handle.content.authorId)
        assertFalse(handle.permissions.canDelete); assertFalse(handle.permissions.canReply)
        assertFalse(handle.permissions.canRate); assertFalse(page.canCreate); assertTrue(page.sortOrders.isEmpty())
        val before = wire.requests.size
        assertFalse(thread.delete(handle).ok); assertFalse(thread.create("synthetic").ok)
        assertEquals(before, wire.requests.size); assertEquals(0, wire.writes)
    }

    @Test fun canonicalModernAndSameIdMetadataLegacyRemainSourceIsolated() {
        canonical(modern())
        val contaminated = metadata(modern(), """{"reloadContinuationItemsCommand":{"continuationItems":[${legacy()}]}}""")
        assertEquals("shadow", commentPage(contaminated).comments.single().text) // Fragment API remains compatible.
        canonical(contaminated)
    }

    @Test fun metadataEntityIsNeverAFallbackForAMissingOwnedEntity() {
        val root = metadata(modern(entities = ""), """{"commentEntityPayload":${entity(author = "UCshadow", text = "shadow")}}""")
        assertEquals("shadow", commentPage(root).comments.single().text)
        val wire = Wire(root.encode())
        assertEquals(RequestFailure.PARSE, loadComments("abcdefghijk", context, "root", wire).error?.kind)
        assertEquals(RequestFailure.PARSE, wire.thread().load().error?.kind)
        assertEquals(0, wire.writes)
    }

    @Test fun directCanonicalEntityAndToolbarCannotBorrowMetadataDataOrCommands() {
        canonical(metadata(modern(), """{"commentEntityPayload":${entity(author = "UCshadow", text = "shadow")}}"""))
        canonical(metadata(modern(), """{"engagementToolbarSurfaceEntityPayload":{"key":"surface","menuRenderer":{"items":[
          {"menuServiceItemRenderer":{"icon":{"iconType":"DELETE"},"serviceEndpoint":{"performCommentActionEndpoint":{"action":"shadow-delete"}}}}]}}}"""))
    }

    @Test fun metadataCreateAndSortNeverIssueDomainCapabilities() {
        canonical(metadata(modern(), """{"createCommentParams":"shadow-create"}"""))
        canonical(metadata(modern(), """{"sortFilterSubMenuRenderer":{"subMenuItems":[{"title":"shadow order",
          "serviceEndpoint":{"continuationCommand":{"token":"shadow-sort"}}}]}}"""))
    }

    @Test fun conflictingMalformedDuplicateOrForeignModernSourcesFailClosed() {
        val ordinary = mutation("key", "commentEntityPayload", entity())
        val invalid = listOf(
            modern(ordinary + "," + ordinary),
            modern(mutation("key", "commentEntityPayload", entity(id = "foreign"))),
            modern(mutation("key", "commentEntityPayload", entity(), type = "ENTITY_MUTATION_TYPE_DELETE")),
            modern(mutation("foreign-key", "commentEntityPayload", entity())),
            modern(view = """{"commentKey":"key","commentId":null}"""),
            modern(view = """{"commentKey":"key","commentId":"comment","commentViewModel":{"commentKey":"foreign-key","commentId":"foreign"}}"""),
            json("""{"onResponseReceivedEndpoints":[{"appendContinuationItemsAction":{"targetId":"foreign-list","continuationItems":[]}}]}"""),
            json("""{"continuationContents":{"itemSectionContinuation":{"contents":[{"commentThreadRenderer":{}}]}}}"""),
            json("""{"continuationContents":{"itemSectionContinuation":{"contents":[]}},"onResponseReceivedEndpoints":[{"appendContinuationItemsAction":{"continuationItems":[]}}]}""")
        )
        for (root in invalid) {
            val wire = Wire(root.encode())
            assertEquals(root.encode(), RequestFailure.PARSE, loadComments("abcdefghijk", context, "root", wire).error?.kind)
            assertEquals(RequestFailure.PARSE, wire.thread().load().error?.kind)
            assertEquals(0, wire.writes)
        }
    }

    @Test fun openingMetadataPanelsAndForeignVideoCannotIssueACommentCursor() {
        val wire = Wire(modern().encode(), """{"videoDetails":{"videoId":"abcdefghijk"},"responseContext":{"metadata":${opening()}}}""")
        val thread = wire.thread(); val page = thread.load().value!!
        assertTrue(page.comments.isEmpty()); assertFalse(page.canCreate); assertEquals(1, wire.requests.size)
        val mismatch = Wire(modern().encode(), opening().replace("\"currentVideoEndpoint\":{\"watchEndpoint\":{\"videoId\":\"abcdefghijk\"}",
            "\"currentVideoEndpoint\":{\"watchEndpoint\":{\"videoId\":\"foreign-video\"}"))
        assertEquals(RequestFailure.PARSE, DiscussionSession(AccountSession(context), mismatch).openVideo("abcdefghijk").error?.kind)
        assertEquals(1, mismatch.requests.size)
    }

    @Test fun legacyCallbackLoaderUsesTheSameNetworkProjection() {
        val root = metadata(modern(), """{"reloadContinuationItemsCommand":{"continuationItems":[${legacy()}]},"createCommentParams":"shadow-create"}""")
        var calls = 0
        val result = loadComments("abcdefghijk", null) { request -> calls++; 200 to
            if (json(request.body).text("videoId") != null) opening() else root.encode() }
        assertEquals(2, calls); assertEquals("canonical", result.comments.single().text)
        assertNull(result.createKey); assertNull(result.comments.single().deleteKey)
    }

    @Test fun detailMetadataSameIdCannotBorrowAnotherCommentsMutationCapability() {
        val owned = json(legacy(writable = false)).obj("commentRenderer")!!
        val renderer = Json.Obj(owned.fields + ("detailViewEndpoint" to json("""{"continuationEndpoint":{"continuationCommand":{"token":"detail"}}}""")))
        val wire = Wire(page(Json.Obj(mapOf("commentRenderer" to renderer)).encode()))
        wire.detail = metadata(json(page(legacy("other", writable = false))), """{"appendContinuationItemsAction":{"continuationItems":[${legacy()}]}}""").encode()
        val thread = wire.thread(); val handle = thread.load().value!!.comments.single()
        assertTrue(handle.permissions.canResolveActions)
        assertEquals("missing_comment_action_target", thread.rate(handle, CommentVote.LIKE).error?.code)
        assertEquals(0, wire.writes)
    }

    @Test fun confirmedMutationMetadataCannotMintOrRefreshACommentModel() {
        val wire = Wire(modern().encode())
        wire.actionReply = """{"status":"STATUS_SUCCEEDED","responseContext":{"metadata":${legacy()}}}"""
        val result = createComment("synthetic", "explicit-input", context, wire)
        assertTrue(result.ok); assertTrue(result.value!!.action.confirmed); assertNull(result.value!!.comment)
        val writable = json(legacy(text = "canonical legacy", writable = false)).obj("commentRenderer")!!
        val buttons = json("""{"commentActionButtonsRenderer":{"likeButton":{"toggleButtonRenderer":{"defaultServiceEndpoint":{"performCommentActionEndpoint":{"action":"owned-like"}}}}}}""")
        wire.document = page(Json.Obj(mapOf("commentRenderer" to Json.Obj(writable.fields + ("actionButtons" to buttons) +
            ("authorEndpoint" to json("""{"browseEndpoint":{"browseId":"UCcanonical"}}"""))))).encode())
        val thread = wire.thread(); val handle = thread.load().value!!.comments.single()
        val rated = thread.rate(handle, CommentVote.LIKE)
        assertTrue(rated.ok); assertTrue(rated.value!!.confirmed)
        assertEquals("canonical legacy", handle.content.text); assertEquals("UCcanonical", handle.content.authorId)
        assertFalse(handle.permissions.canDelete); assertFalse(handle.permissions.canReply)
    }

    @Test fun canonicalProjectionIsIdempotentWithHeaderRepliesAndFramework() {
        for (root in listOf(modern(), json(page(legacy())), json("""{"continuationContents":{"itemSectionContinuation":{
          "header":{"commentsHeaderRenderer":{"createCommentEndpoint":{"createCommentParams":"owned-create"},"countText":{"simpleText":"3"}}},"contents":[]}}}"""))) {
            val once = ownedCommentResponse(root)!!
            assertEquals(once.encode(), ownedCommentResponse(once)?.encode())
        }
        val metadataOnly = Wire("""{"responseContext":{}}""")
        assertEquals(RequestFailure.PARSE, loadComments("abcdefghijk", context, "root", metadataOnly).error?.kind)
    }

    @Test fun undeclaredToolbarScalarsCannotIssueCreationCapabilities() {
        val entity = entity().replace("\"likeCountNotliked\":\"7\"", "\"likeCountNotliked\":\"7\",\"createCommentParams\":\"shadow-create\"")
        val root = modern(mutation("key", "commentEntityPayload", entity))
        assertEquals("shadow-create", commentPage(root).createKey)
        canonical(root)
    }

    @Test fun nestedPresentationIconsAndActionRenderersCannotIssueDeleteCapabilities() {
        val menu = """{"menuRenderer":{"items":[{"menuServiceItemRenderer":{
          "icon":{"iconType":"LIKE","future":{"iconType":"DELETE"}},
          "serviceEndpoint":{"performCommentActionEndpoint":{"action":"owned-report"}}}}]}}"""
        val renderer = json(legacy(writable = false)).obj("commentRenderer")!!
        val root = json(page(Json.Obj(mapOf("commentRenderer" to Json.Obj(renderer.fields + ("actionMenu" to json(menu))))).encode()))
        assertEquals("owned-report", commentPage(root).comments.single().deleteKey)
        for (candidate in listOf(root, json(page(legacy(writable = false).replace("\"simpleText\":\"shadow\"",
            "\"simpleText\":\"shadow\",\"future\":{\"buttonRenderer\":{\"serviceEndpoint\":{\"performCommentActionEndpoint\":{\"action\":\"shadow\"}}}}"))))) {
            val wire = Wire(candidate.encode())
            assertEquals(RequestFailure.PARSE, loadComments("abcdefghijk", context, "root", wire).error?.kind)
            assertEquals(RequestFailure.PARSE, wire.thread().load().error?.kind)
            assertEquals(0, wire.writes)
        }
    }

    @Test fun malformedForeignOrConflictingOwnRouteMetadataNeverFallsBackToNext() {
        val bad = listOf(
            """{"commandMetadata":{"webCommandMetadata":{"apiUrl":"https://foreign.test/youtubei/v1/next"}},"continuationEndpoint":{"continuationCommand":{"token":"next"}}}""",
            """{"continuationEndpoint":{"commandMetadata":{"webCommandMetadata":[]},"continuationCommand":{"token":"next"}}}""",
            """{"continuationEndpoint":{"commandMetadata":{"webCommandMetadata":{"apiUrl":7}},"continuationCommand":{"token":"next"}}}""",
            """{"commandMetadata":{"webCommandMetadata":{"apiUrl":"/youtubei/v1/browse"}},"continuationEndpoint":{"commandMetadata":{"webCommandMetadata":{"apiUrl":"/youtubei/v1/next"}},"continuationCommand":{"token":"next"}}}"""
        )
        for (continuation in bad) {
            val root = json(page("""{"continuationItemRenderer":$continuation}"""))
            val wire = Wire(root.encode())
            assertEquals(RequestFailure.PARSE, loadComments("abcdefghijk", context, "root", wire).error?.kind)
            val thread = wire.thread(); assertEquals(RequestFailure.PARSE, thread.load().error?.kind)
            assertEquals(3, wire.requests.size); assertEquals(0, wire.writes)
        }
        val sort = """{"subMenuItems":[{"commandMetadata":{"webCommandMetadata":{"apiUrl":"https://foreign.test/youtubei/v1/next"}},
          "serviceEndpoint":{"continuationCommand":{"token":"sort"}}}]}"""
        val root = Json.Obj(modern().fields + ("sortFilterSubMenuRenderer" to json(sort)))
        val wire = Wire(root.encode()); assertEquals(RequestFailure.PARSE, wire.thread().load().error?.kind)
        assertEquals(2, wire.requests.size); assertEquals(0, wire.writes)
    }

    @Test fun validOwnedAncestorRouteAndOpeningPresentationArePreserved() {
        val route = """{"continuationItemRenderer":{"commandMetadata":{"webCommandMetadata":{"apiUrl":"/youtubei/v1/browse"}},
          "continuationEndpoint":{"continuationCommand":{"token":"next"}}}}"""
        val wire = Wire(page(route))
        val thread = wire.thread(); val first = thread.load().value!!
        assertTrue(thread.next(first.next!!).ok)
        assertTrue(wire.requests.last().url.substringBefore('?').endsWith("/browse"))
        val root = json(opening())
        val panel = root.obj("engagementPanelSectionListRenderer")!!
        val header = json("""{"engagementPanelTitleHeaderRenderer":{"contextualInfo":{"simpleText":"42"}},
          "commentsHeaderRenderer":{"createCommentEndpoint":{"createCommentParams":"owned-create"}}}""")
        wire.opened = Json.Obj(root.fields + ("engagementPanelSectionListRenderer" to Json.Obj(panel.fields + ("header" to header)))).encode()
        val opened = loadWatch("abcdefghijk", context, http = wire).value!!
        assertEquals("42", opened.commentCount); assertEquals("owned-create", opened.commentCreateKey)
        wire.opened = """{"videoDetails":{"videoId":"abcdefghijk"},"messageRenderer":{"text":{"simpleText":"Comments are turned off"}},
          "responseContext":{"messageRenderer":{"text":{"simpleText":"irrelevant"}}}}"""
        assertTrue(loadWatch("abcdefghijk", context, http = wire).value!!.commentsTurnedOff)
        wire.opened = """{"videoDetails":{"videoId":"abcdefghijk"},"responseContext":{"messageRenderer":{"text":{"simpleText":"Comments are turned off"}}}}"""
        assertFalse(loadWatch("abcdefghijk", context, http = wire).value!!.commentsTurnedOff)
    }

    @Test fun everyAdmittedCursorAncestorValidatesItsDeclaredRoute() {
        val foreign = """{"webCommandMetadata":{"apiUrl":"https://foreign.test/youtubei/v1/next"}}"""
        val ordinary = json(legacy(writable = false)).obj("commentRenderer")!!
        fun render(extra: String) = page(Json.Obj(mapOf("commentRenderer" to Json.Obj(ordinary.fields + json(extra).fields))).encode())
        val replies = """{"replies":{"commandMetadata":$foreign,"commentRepliesRenderer":{"continuationCommand":{"token":"replies"}}}}"""
        val detail = """{"detailViewEndpoint":{"showEngagementPanelEndpoint":{"commandMetadata":$foreign,"engagementPanel":{
          "engagementPanelSectionListRenderer":{"content":{"sectionListRenderer":{"contents":[{"itemSectionRenderer":{
            "continuations":[{"reloadContinuationData":{"continuation":"detail"}}]}}]}}}}}}}"""
        val scalar = """{"commandMetadata":$foreign,"detailViewEndpoint":{"continuationEndpoint":{"continuationCommand":{"token":"detail"}}}}"""
        val sort = Json.Obj(modern().fields + ("sortFilterSubMenuRenderer" to json("""{"commandMetadata":$foreign,"subMenuItems":[{
          "serviceEndpoint":{"continuationCommand":{"token":"sort"}}}]}"""))).encode()
        for (root in listOf(render(replies), render(detail), render(scalar), sort)) {
            val wire = Wire(root)
            assertEquals(root, RequestFailure.PARSE, loadComments("abcdefghijk", context, "root", wire).error?.kind)
            assertEquals(RequestFailure.PARSE, wire.thread().load().error?.kind)
            assertEquals(3, wire.requests.size); assertEquals(0, wire.writes)
        }
    }

    @Test fun openingProjectionRetainsCreationSortAndCountOnRepeatedProjection() {
        val raw = json(opening())
        val panel = raw.obj("engagementPanelSectionListRenderer")!!
        val header = json("""{"engagementPanelTitleHeaderRenderer":{"contextualInfo":{"simpleText":"42"}},"commentsHeaderRenderer":{
          "createCommentEndpoint":{"createCommentParams":"owned-create"},"sortFilterSubMenuRenderer":{"subMenuItems":[{
            "title":"server order","serviceEndpoint":{"continuationCommand":{"token":"sort"}}}]}}}""")
        val root = Json.Obj(raw.fields + ("engagementPanelSectionListRenderer" to Json.Obj(panel.fields + ("header" to header))))
        val once = ownedCommentOpening(root, "abcdefghijk")!!
        val twice = ownedCommentOpening(once, "abcdefghijk")!!
        assertEquals(once.encode(), twice.encode())
        val observed = watchPage(twice, "abcdefghijk")
        assertEquals("owned-create", observed.commentCreateKey); assertEquals("sort", observed.commentSortOptions.single().key)
        assertEquals("42", observed.commentCount)
        val wire = Wire(page(""), root.encode())
        val opened = loadWatch("abcdefghijk", context, http = wire).value!!
        val loaded = loadComments("abcdefghijk", context, http = wire, opened = opened).value!!
        assertEquals("owned-create", loaded.createKey); assertEquals("sort", loaded.sortOptions.single().key)
        assertEquals(2, wire.requests.size)
    }

    private fun watchPanel(token: String? = "panel-root"): Json.Obj {
        val cursor = token?.let { json("""{"continuationItemRenderer":{"continuationEndpoint":{
          "commandMetadata":{"webCommandMetadata":{"apiUrl":"/youtubei/v1/next"}},"continuationCommand":{"token":"$it"}}}}""") }
        return Json.Obj(mapOf("panelIdentifier" to Json.Str("engagement-panel-comments-section"), "content" to
            json("""{"sectionListRenderer":{"contents":[{"itemSectionRenderer":{"contents":[${cursor?.encode().orEmpty()}]}}]}}""")))
    }
    private fun watchSection(token: String = "inline-root"): Json.Obj = json("""{"sectionIdentifier":"comment-item-section",
        "header":{"commentsHeaderRenderer":{"countText":{"simpleText":"inline count"},
          "createCommentEndpoint":{"createCommentParams":"inline-create"},"sortFilterSubMenuRenderer":{"subMenuItems":[{
            "title":"inline sort","serviceEndpoint":{"continuationCommand":{"token":"inline-sort"}}}]}}},
        "contents":[{"continuationItemRenderer":{"continuationEndpoint":{"commandMetadata":{"webCommandMetadata":{"apiUrl":"/youtubei/v1/next"}},
          "continuationCommand":{"token":"$token"}}}}]}""")
    private fun dualWatch(panel: Json.Obj = watchPanel(), section: Json.Obj = watchSection(),
        layout: String = "twoColumnWatchNextResults"): Json.Obj = json("""{
        "currentVideoEndpoint":{"watchEndpoint":{"videoId":"abcdefghijk"}},"videoDetails":{"videoId":"abcdefghijk"},
        "engagementPanels":[{"engagementPanelSectionListRenderer":${panel.encode()}}],
        "contents":{"$layout":{"results":{"results":{"contents":[{"itemSectionRenderer":${section.encode()}}]}}}}}""")

    @Test fun independentWatchSurfacesUseOnlyTheUniquePanelCursorAndCapabilities() {
        val panel = watchPanel()
        val header = json("""{"engagementPanelTitleHeaderRenderer":{"contextualInfo":{"simpleText":"panel count"}},
          "commentsHeaderRenderer":{"createCommentEndpoint":{"createCommentParams":"panel-create"},
            "sortFilterSubMenuRenderer":{"subMenuItems":[{"title":"panel sort","serviceEndpoint":{"continuationCommand":{"token":"panel-sort"}}}]}}}""")
        for (layout in listOf("twoColumnWatchNextResults", "singleColumnWatchNextResults")) {
            val root = dualWatch(Json.Obj(panel.fields + ("header" to header)), layout = layout)
            val once = ownedCommentOpening(root, "abcdefghijk")!!
            assertEquals(once.encode(), ownedCommentOpening(once, "abcdefghijk")?.encode())
            val watch = watchPage(once, "abcdefghijk")
            assertEquals("panel-root", watch.commentsKey); assertEquals("panel count", watch.commentCount)
            assertEquals("panel-create", watch.commentCreateKey); assertEquals("panel-sort", watch.commentSortOptions.single().key)
            assertFalse(once.encode().contains("inline-")); assertFalse(once.encode().contains("inline count"))
            val wire = Wire(modern().encode(), root.encode())
            val opened = DiscussionSession(AccountSession(context), wire).openVideo("abcdefghijk")
            assertTrue(opened.error.toString(), opened.ok)
            val loaded = opened.value!!.load()
            assertTrue(loaded.error.toString(), loaded.ok); assertTrue(loaded.value!!.canCreate)
            assertEquals(2, wire.requests.size); assertEquals("panel-root", json(wire.requests.last().body).text("continuation"))
            assertTrue(wire.requests.last().url.substringBefore('?').endsWith("/next"))
            assertEquals(0, wire.writes)
        }
    }

    @Test fun secondaryWatchSurfaceCannotSupplyHeadersOrRescueAnAbsentPanelCursor() {
        for (panel in listOf(watchPanel(), watchPanel(null))) {
            val root = dualWatch(panel)
            val scoped = ownedCommentOpening(root, "abcdefghijk")!!
            val observed = watchPage(scoped, "abcdefghijk")
            assertNull(observed.commentCount); assertNull(observed.commentCreateKey); assertTrue(observed.commentSortOptions.isEmpty())
            val wire = Wire(modern().encode(), root.encode()); val thread = wire.thread(); val loaded = thread.load()
            assertTrue(loaded.error.toString(), loaded.ok); assertFalse(loaded.value!!.canCreate); assertTrue(loaded.value!!.sortOrders.isEmpty())
            // Derive the expected route from the selected projection, never the alternative section.
            assertEquals(if (observed.commentsKey == null) 1 else 2, wire.requests.size)
            assertTrue(wire.requests.none { json(it.body).text("continuation")?.startsWith("inline-") == true })
            val before = wire.requests.size; assertFalse(thread.create("synthetic").ok)
            assertEquals(before, wire.requests.size); assertEquals(0, wire.writes)
        }
    }

    @Test fun duplicateMalformedForeignOrUnboundWatchRolesNeverFallThroughToTheAlternative() {
        val base = dualWatch()
        val panelWrapper = json("""{"engagementPanelSectionListRenderer":${watchPanel().encode()}}""")
        val doublePanel = Json.Obj(base.fields + ("engagementPanels" to Json.Arr(listOf(panelWrapper, panelWrapper))))
        val sectionWrapper = json("""{"itemSectionRenderer":${watchSection().encode()}}""")
        val doubleSection = Json.Obj(base.fields + ("contents" to json("""{"twoColumnWatchNextResults":{"results":{"results":{
          "contents":[${sectionWrapper.encode()},${sectionWrapper.encode()}]}}}}""")))
        val missingContent = Json.Obj(watchPanel().fields - "content")
        val invalidContent = Json.Obj(watchPanel().fields + ("content" to Json.Arr(emptyList())))
        val foreignRoute = Json.Obj(watchPanel().fields + ("commandMetadata" to json("""{"webCommandMetadata":{"apiUrl":"https://foreign.test/youtubei/v1/next"}}""")))
        val malformedRoute = Json.Obj(watchPanel().fields + ("commandMetadata" to json("""{"webCommandMetadata":[]} """)))
        val unbound = Json.Obj(base.fields - "currentVideoEndpoint")
        val wrongVideo = Json.Obj(base.fields + ("currentVideoEndpoint" to json("""{"watchEndpoint":{"videoId":"foreign-video"}}""")))
        val unsupportedCanonicalPair = Json.Obj((base.fields - "engagementPanels") + ("engagementPanelSectionListRenderer" to watchPanel()))
        val unsupportedRootSectionPair = Json.Obj((base.fields - "contents") + ("itemSectionRenderer" to watchSection()))
        for (root in listOf(doublePanel, doubleSection, dualWatch(missingContent), dualWatch(invalidContent), dualWatch(foreignRoute),
            dualWatch(malformedRoute), unbound, wrongVideo, unsupportedCanonicalPair, unsupportedRootSectionPair)) {
            val wire = Wire(modern().encode(), root.encode())
            assertEquals(RequestFailure.PARSE, DiscussionSession(AccountSession(context), wire).openVideo("abcdefghijk").error?.kind)
            assertEquals(1, wire.requests.size); assertEquals(0, wire.writes)
        }
    }

    @Test fun sharedRootCapabilitiesStayAmbiguousAcrossIndependentWatchSurfaces() {
        val base = dualWatch()
        for (field in listOf(
            """{"createCommentParams":"ambiguous-create"}""",
            """{"createCommentEndpoint":{"createCommentParams":"ambiguous-create"}}""",
            """{"sortFilterSubMenuRenderer":{"subMenuItems":[]}}""",
            """{"commentsHeaderRenderer":{"countText":{"simpleText":"ambiguous count"}}}"""
        )) {
            val wire = Wire(modern().encode(), Json.Obj(base.fields + json(field).fields).encode())
            assertEquals(RequestFailure.PARSE, DiscussionSession(AccountSession(context), wire).openVideo("abcdefghijk").error?.kind)
            assertEquals(1, wire.requests.size); assertEquals(0, wire.writes)
        }
        val entry = Json.Obj(base.fields + ("commentsEntryPointRenderer" to json("""{"commentCount":{"simpleText":"ambiguous count"}}""")))
        assertNull(watchPage(ownedCommentOpening(entry, "abcdefghijk"), "abcdefghijk").commentCount)
        val legacyOnly = Json.Obj(base.fields - "engagementPanels")
        assertEquals("inline-root", watchPage(ownedCommentOpening(legacyOnly, "abcdefghijk"), "abcdefghijk").commentsKey)
        val wire = Wire(modern().encode(), legacyOnly.encode()); assertTrue(wire.thread().load().ok)
        assertEquals("inline-root", json(wire.requests.last().body).text("continuation")); assertEquals(0, wire.writes)
    }

    private fun creationHeader(endpoint: Json) = json("""{"createRenderer":{"commentSimpleboxRenderer":{
        "submitButton":{"buttonRenderer":{"serviceEndpoint":${endpoint.encode()}}}}}}""")
    private fun withHeader(root: Json.Obj, header: Json.Obj): Json.Obj {
        val actions = root.arr("onResponseReceivedEndpoints").toMutableList()
        actions[0] = json("""{"reloadContinuationItemsCommand":{"slot":"RELOAD_CONTINUATION_SLOT_HEADER",
          "targetId":"engagement-panel-comments-section","continuationItems":[{"commentsHeaderRenderer":${header.encode()}}]}}""")
        return Json.Obj(root.fields + ("onResponseReceivedEndpoints" to Json.Arr(actions)))
    }
    private fun setupSubmit() = json("""{"clickTrackingParams":"synthetic-tracking",
        "commandMetadata":{"webCommandMetadata":{"apiUrl":"/youtubei/v1/channel/create"}},
        "channelCreationServiceEndpoint":{"zeroStepChannelCreationRenderer":{}}}""")

    @Test fun channelCreationUiKeepsOwnedCommentsReadableWithoutPublishingCapability() {
        val root = withHeader(modern(), creationHeader(setupSubmit()))
        val wire = Wire(root.encode())
        val loaded = loadComments("abcdefghijk", context, "root", wire)
        assertTrue(loaded.error.toString(), loaded.ok); assertEquals("canonical", loaded.value!!.comments.single().text)
        assertNull(loaded.value!!.createKey)
        val thread = wire.thread(); val page = thread.load().value!!
        assertFalse(page.canCreate); assertEquals("canonical", page.comments.single().content.text)
        val before = wire.requests.size
        assertEquals("missing_comment_creation_capability", thread.create("synthetic").error?.code)
        assertEquals(before, wire.requests.size); assertEquals(0, wire.writes)
        val projected = ownedCommentResponse(root)!!
        assertFalse(projected.encode().contains("channelCreation"))
        assertEquals(projected.encode(), ownedCommentResponse(projected)?.encode())
    }

    @Test fun nestedSetupAndToolbarGateCommandsCannotLendCommentCapabilities() {
        val shadow = json("""{"createCommentEndpoint":{"createCommentParams":"shadow-create"},
          "performCommentActionEndpoint":{"action":"shadow-action"},"commentRenderer":${json(legacy()).obj("commentRenderer")!!.encode()}}""")
        val submit = Json.Obj(setupSubmit().fields + ("channelCreationServiceEndpoint" to shadow))
        val gate = """{"innertubeCommand":{"commandMetadata":{"webCommandMetadata":{"apiUrl":"/youtubei/v1/channel/create"}},
          "channelCreationFormEndpoint":{"createCommentEndpoint":{"createCommentParams":"shadow-create"},
            "performCommentActionEndpoint":{"action":"shadow-action"}}}}"""
        val toolbar = """{"key":"surface","prepareAccountCommand":$gate,"replyCommand":$gate}"""
        val root = withHeader(modern(mutation("key", "commentEntityPayload", entity()) + "," +
            mutation("surface", "engagementToolbarSurfaceEntityPayload", toolbar)), creationHeader(submit))
        val wire = Wire(root.encode()); val thread = wire.thread(); val page = thread.load().value!!
        val handle = page.comments.single()
        assertEquals("canonical", handle.content.text); assertFalse(page.canCreate)
        assertFalse(handle.permissions.canRate); assertFalse(handle.permissions.canDelete); assertFalse(handle.permissions.canReply)
        val before = wire.requests.size
        assertFalse(thread.create("synthetic").ok); assertFalse(thread.rate(handle, CommentVote.LIKE).ok)
        assertFalse(thread.delete(handle).ok); assertFalse(thread.reply(handle, "synthetic").ok)
        assertEquals(before, wire.requests.size); assertEquals(0, wire.writes)
        val projected = ownedCommentResponse(root)!!.encode()
        assertFalse(projected.contains("shadow-")); assertFalse(projected.contains("channelCreation"))
    }

    @Test fun malformedConflictingOrForeignCreationSourcesStillFailClosed() {
        val create = json("""{"createCommentParams":"owned-create"}""")
        val correctMetadata = json("""{"webCommandMetadata":{"apiUrl":"/youtubei/v1/comment/create_comment"}}""")
        val direct = json("""{"commandMetadata":${correctMetadata.encode()},"createCommentEndpoint":${create.encode()}}""")
        val invalidSubmits = listOf(
            Json.Obj(setupSubmit().fields + ("channelCreationServiceEndpoint" to Json.Arr(emptyList()))),
            Json.Obj(setupSubmit().fields + ("channelCreationServiceEndpoint" to Json.Null)),
            Json.Obj(setupSubmit().fields + ("createCommentEndpoint" to create)),
            Json.Obj(setupSubmit().fields + ("performCommentActionEndpoint" to json("""{"action":"shadow-action"}"""))),
            Json.Obj(setupSubmit().fields + ("commandMetadata" to Json.Arr(emptyList()))),
            Json.Obj(setupSubmit().fields + ("commandMetadata" to json("""{"webCommandMetadata":{"apiUrl":7}}"""))),
            Json.Obj(direct.fields + ("createCommentEndpoint" to Json.Arr(emptyList()))),
            Json.Obj(direct.fields + ("commandMetadata" to json("""{"webCommandMetadata":{"apiUrl":"/youtubei/v1/channel/create"}}"""))),
            Json.Obj(direct.fields + ("commandMetadata" to json("""{"webCommandMetadata":{"apiUrl":"https://foreign.test/youtubei/v1/comment/create_comment"}}"""))),
            Json.Obj(direct.fields + ("commandMetadata" to json("""{"webCommandMetadata":[]}""")))
        )
        val setup = withHeader(modern(), creationHeader(setupSubmit()))
        val invalidRoots = invalidSubmits.map { withHeader(modern(), creationHeader(it)) } + listOf(
            Json.Obj(setup.fields + ("createCommentEndpoint" to create)),
            Json.Obj(setup.fields + ("createCommentParams" to Json.Str("shadow-create"))),
            withHeader(modern(), Json.Obj(creationHeader(setupSubmit()).fields + ("createCommentEndpoint" to create)))
        )
        for (root in invalidRoots) {
            val wire = Wire(root.encode())
            assertEquals(RequestFailure.PARSE, loadComments("abcdefghijk", context, "root", wire).error?.kind)
            assertEquals(RequestFailure.PARSE, wire.thread().load().error?.kind); assertEquals(0, wire.writes)
        }
        val opened = json(opening()); val panel = opened.obj("engagementPanelSectionListRenderer")!!
        val contradictory = Json.Obj(opened.fields + ("createCommentEndpoint" to create) +
            ("engagementPanelSectionListRenderer" to Json.Obj(panel.fields +
                ("header" to json("""{"commentsHeaderRenderer":${creationHeader(setupSubmit()).encode()}}""")))))
        val wire = Wire(modern().encode(), contradictory.encode())
        assertEquals(RequestFailure.PARSE, DiscussionSession(AccountSession(context), wire).openVideo("abcdefghijk").error?.kind)
        assertEquals(1, wire.requests.size); assertEquals(0, wire.writes)
    }

    @Test fun directOwnedCreationRetainsItsValidatedNativeCommand() {
        val endpoint = json("""{"commandMetadata":{"webCommandMetadata":{"apiUrl":"/youtubei/v1/comment/create_comment"}},
          "createCommentEndpoint":{"createCommentParams":"owned-create"}}""")
        val root = withHeader(modern(), creationHeader(endpoint))
        val wire = Wire(root.encode()); val loaded = loadComments("abcdefghijk", context, "root", wire)
        assertTrue(loaded.ok); assertEquals("owned-create", loaded.value!!.createKey)
        val thread = wire.thread(); assertTrue(thread.load().value!!.canCreate)
        val created = thread.create("synthetic")
        assertTrue(created.error.toString(), created.ok); assertTrue(created.value!!.confirmed)
        assertEquals(1, wire.writes); assertTrue(wire.requests.last().url.substringBefore('?').endsWith("/comment/create_comment"))
        assertEquals("owned-create", json(wire.requests.last().body).text("createCommentParams"))
    }

}
