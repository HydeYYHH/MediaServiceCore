package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

/** Synthetic network responses exercise relative ownership without live credentials or mutations. */
class PostNetworkSourceIsolationTest {
    private val channel = "UC" + "a".repeat(22)
    private val id = "Ugkx_owned_post"
    private fun post(value: String = id, extra: String = "") =
        """{"backstagePostRenderer":{"postId":"$value","contentText":{"simpleText":"Owned"}$extra}}"""
    private fun cursor(value: String, extra: String = "") =
        """{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"$value"}}$extra}}"""
    private fun page(slots: String) = """{"contents":[$slots]}"""
    private fun api(value: String) = """"commandMetadata":{"webCommandMetadata":{"apiUrl":"$value"}}"""
    private fun tab(kind: String, selected: Boolean, content: String? = null) = """{"tabRenderer":{
        "title":"Tab","tabIdentifier":"$kind","selected":$selected,
        "endpoint":{"browseEndpoint":{"browseId":"$channel","params":"route-$kind"},
        "commandMetadata":{"webCommandMetadata":{"url":"/channel/$channel/$kind","apiUrl":"/youtubei/v1/browse"}}}
        ${content?.let { ",\"content\":$it" }.orEmpty()}}}"""
    private fun landing(tabs: String) = """{"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[$tabs]}}}"""
    private fun expandableTab(kind: String, selected: Boolean, content: String? = null) =
        tab(kind, selected, content).replaceFirst("\"tabRenderer\"", "\"expandableTabRenderer\"")
    private fun read(body: String, continuation: Boolean = false): ReadResult<PostPage> {
        var calls = 0
        val result = loadChannelPosts(channel, params = if (continuation) null else "ISSUED_POST_ROUTE",
            continuation = if (continuation) "ISSUED" else null, http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                calls++; assertTrue(request.url.startsWith(YOUTUBE_API + "browse"))
                return HttpReply(200, body)
            }
        })
        assertEquals(1, calls)
        return result
    }
    private fun document(body: String, requested: String = id): ReadResult<PostPage> {
        var calls = 0
        val result = loadPost(requested, object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                calls++; assertEquals("GET", request.method)
                return HttpReply(200, "<script>var ytInitialData = $body;</script>")
            }
        })
        assertEquals(1, calls)
        return result
    }
    private fun webPostDocument(content: String, layout: String = "twoColumnBrowseResultsRenderer") =
        """{"contents":{"$layout":{"tabs":[{"tabRenderer":{"title":"Post","selected":true,"content":$content,"trackingParams":"PRESENTATION"}}]}}}"""
    private fun rejected(body: String, continuation: Boolean = false) {
        val result = read(body, continuation)
        assertFalse(result.ok); assertEquals(RequestFailure.PARSE, result.error?.kind); assertNull(result.value)
    }
    private fun like(status: String = "LIKE", target: String = id) =
        """{"likeEndpoint":{"status":"$status","target":{"postId":"$target"},"likeParams":"OWN_PARAMS"}}"""
    private fun buttons(command: String) = """, "actionButtons":{"commentActionButtonsRenderer":{
        "likeButton":{"toggleButtonRenderer":{"defaultServiceEndpoint":$command}}}}"""

    @Test fun metadataCannotSupplyAChannelPostCollectionOrItsCursor() {
        rejected("""{"responseContext":${page(post() + "," + cursor("FOREIGN"))}}""")
        val result = read("""{"contents":[],"responseContext":${page(post() + "," + cursor("FOREIGN"))}}""").value!!
        assertTrue(result.posts.isEmpty()); assertNull(result.nextKey)
    }

    @Test fun selectedPostsUseOnlyTheirDirectTabContent() {
        val own = page(post() + "," + cursor("OWN_NEXT"))
        val foreign = page(post("Ugkx_foreign") + "," + cursor("FOREIGN_NEXT"))
        val root = landing(tab("posts", true, own) + "," + tab("home", false, foreign))
            .dropLast(1) + ",\"responseContext\":$foreign}"
        val parsed = read(root).value!!
        assertEquals(listOf(id), parsed.posts.map { it.postId }); assertEquals("OWN_NEXT", parsed.nextKey)
        assertEquals(parsed, ownedInitialChannelPosts(jsonObject(root)!!, channel))
    }

    @Test fun initialChannelRoleNeverTreatsAnOrdinaryFeedAsPosts() {
        for (body in listOf(page(post() + "," + cursor("NOT_A_POST_ROUTE")),
            landing(tab("home", true, page(post() + "," + cursor("HOME_CURSOR")))),
            landing(tab("posts", false, page(post() + "," + cursor("UNSELECTED")))))) {
            val initial = ownedInitialChannelPosts(jsonObject(body)!!, channel)!!
            assertTrue(initial.posts.isEmpty()); assertNull(initial.nextKey)
        }
        assertEquals(listOf(id), read(page(post())).value!!.posts.map { it.postId })
    }

    @Test fun directTabsExcludeMetadataAndNestedTabsButPreserveZeroSelectedRoutes() {
        val nested = tab("posts", true, page(post()))
        val root = landing(tab("home", false, """{"responseContext":$nested,"contents":[]}""") + "," + tab("videos", false))
            .dropLast(1) + ",\"responseContext\":$nested}"
        val tabs = ownedChannelTabs(jsonObject(root)!!)!!
        assertEquals(listOf(ChannelTabKind.HOME, ChannelTabKind.VIDEOS), tabs.map { it.kind })
        assertTrue(tabs.none { it.selected })
        assertEquals(emptyList<BrowseTab>(), ownedChannelTabs(jsonObject(page(post()))!!))
    }

    @Test fun conflictingOrMalformedTabLayoutsCannotIssueRoutes() {
        val bothSelected = landing(tab("home", true) + "," + tab("posts", true, page(post())))
        val parallel = """{"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[]},"singleColumnBrowseResultsRenderer":{"tabs":[]}}}"""
        val wrongType = """{"contents":{"twoColumnBrowseResultsRenderer":{"tabs":{}}}}"""
        for (body in listOf(bothSelected, parallel, wrongType)) {
            assertNull(ownedChannelTabs(jsonObject(body)!!)); assertNull(ownedInitialChannelPosts(jsonObject(body)!!, channel))
        }
        val missingContent = landing(tab("posts", true))
        rejected(missingContent)
        val wrongApi = landing(tab("posts", true, page(post()))).replace("/youtubei/v1/browse", "/youtubei/v1/next")
        assertNull(ownedChannelTabs(jsonObject(wrongApi)!!))
        val tabApi = landing(tab("posts", true, page(post()))).replace("\"tabIdentifier\":", api("/youtubei/v1/next") + ",\"tabIdentifier\":")
        assertNull(ownedChannelTabs(jsonObject(tabApi)!!))
    }

    /** Expandable search tabs cannot authorize a posts request. */
    @Test fun directExpandableTabsKeepUnknownSemanticsVisibleWithoutIssuingAPostsRoute() {
        val fake = expandableTab("posts", true, page(post("Ugkx_metadata") + "," + cursor("FOREIGN")))
        val body = landing(tab("home", true, """{"contents":[],"responseContext":$fake}""") + "," + expandableTab("search", false))
            .dropLast(1) + ",\"metadata\":{\"channelMetadataRenderer\":{\"externalId\":\"$channel\",\"title\":\"Channel\"}},\"responseContext\":$fake}"
        var calls = 0
        val wire = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply { calls++; return HttpReply(200, body) }
        }
        val opened = ChannelSession(AccountSession(), wire).open(channel)
        assertTrue(opened.ok)
        val handle = opened.value!!
        assertEquals(listOf(ChannelTabKind.HOME, ChannelTabKind.UNKNOWN), handle.availableTabs)
        assertEquals(channel, handle.channel.page.tabs[1].browseId)
        assertEquals("route-search", handle.channel.page.tabs[1].params)
        assertEquals(RequestFailure.UNSUPPORTED, handle.openPosts().error?.kind)
        assertEquals(RequestFailure.UNSUPPORTED, handle.openTab(ChannelTabKind.UNKNOWN).error?.kind)
        assertEquals(1, calls)
    }

    @Test fun selectedExpandablePostsUseTheSameOwnedContentAndCursorInEitherWebLayout() {
        val own = page(post() + "," + cursor("OWN_NEXT"))
        val body = landing(expandableTab("posts", true, own) + "," + tab("home", false,
            page(post("Ugkx_other") + "," + cursor("FOREIGN"))))
        for (layout in listOf("twoColumnBrowseResultsRenderer", "singleColumnBrowseResultsRenderer")) {
            val root = body.replace("twoColumnBrowseResultsRenderer", layout)
            val result = read(root).value!!
            assertEquals(listOf(id), result.posts.map { it.postId }); assertEquals("OWN_NEXT", result.nextKey)
            assertEquals(result, ownedInitialChannelPosts(jsonObject(root)!!, channel))
            assertEquals(listOf(ChannelTabKind.POSTS, ChannelTabKind.HOME), ownedChannelTabs(jsonObject(root)!!)!!.map { it.kind })
        }
    }

    @Test fun expandableTabSupportDoesNotAdmitAmbiguousUnknownOrMalformedRoles() {
        val ordinary = tab("home", false)
        val expanded = expandableTab("posts", false)
        val dual = ordinary.dropLast(1) + "," + expanded.drop(1)
        val candidates = listOf(dual, expanded.replace("expandableTabRenderer", "futureTabRenderer"),
            """{"expandableTabRenderer":[]}""",
            """{"expandableTabRenderer":{"title":"Tab","selected":false,"endpoint":[]}}""",
            expanded.replace("\"selected\":false", "\"selected\":\"false\""),
            expanded.replace("/youtubei/v1/browse", "/youtubei/v1/next"),
            expanded.replace("browseEndpoint", "searchEndpoint"),
            expanded.replace("\"endpoint\":", "\"navigationEndpoint\":{},\"endpoint\":"))
        for (candidate in candidates) {
            val root = jsonObject(landing(candidate))!!
            assertNull(ownedChannelTabs(root)); assertNull(ownedInitialChannelPosts(root, channel))
        }
        val selectedTwice = jsonObject(landing(tab("home", true) + "," + expandableTab("posts", true, page(post()))))!!
        assertNull(ownedChannelTabs(selectedTwice)); assertNull(ownedInitialChannelPosts(selectedTwice, channel))
        val parallel = landing(expanded).replace("\"twoColumnBrowseResultsRenderer\":", "\"singleColumnBrowseResultsRenderer\":{\"tabs\":[]},\"twoColumnBrowseResultsRenderer\":")
        assertNull(ownedChannelTabs(jsonObject(parallel)!!))
    }

    @Test fun selectedExpandablePostsCannotBorrowMissingUnknownOrParallelContent() {
        for (content in listOf(null, """{"futureRenderer":{"contents":[]}}""",
            """{"contents":[],"onResponseReceivedActions":[{"appendContinuationItemsAction":{"continuationItems":[]}}]}""")) {
            val root = landing(expandableTab("posts", true, content))
            assertNotNull(ownedChannelTabs(jsonObject(root)!!))
            assertNull(ownedInitialChannelPosts(jsonObject(root)!!, channel)); rejected(root)
        }
    }

    @Test fun fixedLegacySectionGridAndThreadRolesKeepOrderWithoutBorrowingReplies() {
        val thread = """{"backstagePostThreadRenderer":{"post":${post("Ugkx_first")},
            "comments":${page(post("Ugkx_not_a_post_reply") + "," + cursor("REPLY_CURSOR"))}}}"""
        val grid = """{"richItemRenderer":{"content":${post("Ugkx_second")}}}"""
        val body = """{"contents":{"sectionListRenderer":{"contents":[{"itemSectionRenderer":{"contents":[$thread,$grid,${cursor("OWN_NEXT")}]}}]}}}"""
        val result = read(body).value!!
        assertEquals(listOf("Ugkx_first", "Ugkx_second"), result.posts.map { it.postId }); assertEquals("OWN_NEXT", result.nextKey)
        val rich = """{"contents":{"richGridRenderer":{"contents":[$grid]}}}"""
        assertEquals("Ugkx_second", read(rich).value!!.posts.single().postId)
    }

    @Test fun fixedContinuationEnvelopesRetainOnlyTheOwnedCollection() {
        val slots = post() + "," + cursor("OWN_NEXT")
        for (name in listOf("onResponseReceivedActions", "onResponseReceivedEndpoints", "onResponseReceivedCommands")) {
            val body = """{"$name":[{"appendContinuationItemsAction":{"continuationItems":[$slots]}}],
                "responseContext":${page(post("Ugkx_foreign") + "," + cursor("FOREIGN"))}}"""
            assertEquals("OWN_NEXT", read(body, true).value!!.nextKey)
        }
        for (name in listOf("sectionListContinuation", "itemSectionContinuation", "richGridContinuation")) {
            val body = """{"continuationContents":{"$name":{"contents":[${post()}],
                "continuations":[{"nextContinuationData":{"continuation":"OWN_NEXT"}}]}}}"""
            assertEquals(listOf(id), read(body, true).value!!.posts.map { it.postId })
            assertEquals("OWN_NEXT", read(body, true).value!!.nextKey)
        }
    }

    @Test fun parallelUnknownOrCommentContinuationRolesCannotBecomeAnEmptyPostSuccess() {
        val action = """{"appendContinuationItemsAction":{"continuationItems":[]}}"""
        for (body in listOf(
            """{"onResponseReceivedActions":[$action,$action]}""",
            """{"onResponseReceivedActions":[$action],"contents":[]}""",
            """{"continuationContents":{"futureContinuation":{"contents":[]}}}""",
            """{"onResponseReceivedActions":[{"reloadContinuationItemsCommand":{"continuationItems":[],"targetId":"comments-section"}}]}""",
            """{"onResponseReceivedActions":[{"reloadContinuationItemsCommand":{"continuationItems":[],"slot":"RELOAD_CONTINUATION_SLOT_HEADER"}}]}""",
            """{"continuationContents":{"itemSectionContinuation":{"contents":[],"sectionIdentifier":"comment-item-section"}}}""")) rejected(body, true)
        val parallel = """{"contents":[],"onResponseReceivedActions":[$action]}"""
        rejected(parallel)
        assertNull(ownedInitialChannelPosts(jsonObject(parallel)!!, channel))
        val selectedParallel = landing(tab("posts", true, parallel))
        rejected(selectedParallel)
        assertNull(ownedInitialChannelPosts(jsonObject(selectedParallel)!!, channel))
        for (field in listOf("targetId", "sectionIdentifier")) {
            for (role in listOf("future-posts", "playlist", "engagement-panel-comments-section"))
                rejected("""{"continuationContents":{"itemSectionContinuation":{"contents":[],"$field":"$role"}}}""", true)
        }
        val bodySlot = """{"onResponseReceivedActions":[{"reloadContinuationItemsCommand":{
            "continuationItems":[${post()}],"slot":"RELOAD_CONTINUATION_SLOT_BODY"}}]}"""
        assertEquals(id, read(bodySlot, true).value!!.posts.single().postId)
    }

    @Test fun unknownOwnedSlotsCannotBeIgnoredEvenBesideUsablePosts() {
        for (slot in listOf("""{"futureRenderer":{"backstagePostRenderer":{"postId":"Ugkx_fake"}}}""",
            """{"videoRenderer":{"videoId":"ordinary"}}""", """{"backstagePostRenderer":{}}""", "null"))
            rejected(page(post() + "," + slot))
        rejected(page(post() + "," + post()))
        rejected(page(post() + "," + cursor("ONE") + "," + cursor("TWO")))
    }

    @Test fun declaredCursorRoutesAreCheckedAtEveryOwningAncestor() {
        val wrong = api("/youtubei/v1/next")
        for (body in listOf(
            page(post() + "," + cursor("CURSOR", ",$wrong")),
            """{"contents":[${post()},${cursor("CURSOR")}],$wrong}""",
            """{"contents":{"sectionListRenderer":{"contents":[${post()},${cursor("CURSOR")}],$wrong}}}""",
            """{"contents":[${post()},{"continuationItemRenderer":{"button":{"buttonRenderer":{
                "command":{"continuationCommand":{"token":"CURSOR"}},$wrong}}}}]}""",
            """{"continuationContents":{"itemSectionContinuation":{"contents":[],
                "continuations":[{"nextContinuationData":{"continuation":"CURSOR"},$wrong}]}}}"""))
            rejected(body, body.contains("continuationContents"))
        val exact = page(post() + "," + cursor("CURSOR", "," + api("/youtubei/v1/browse")))
        assertEquals("CURSOR", read(exact).value!!.nextKey)
    }

    @Test fun aDocumentIgnoresCommentSectionsAndMetadataWithoutBorrowingTheirPostOrCursor() {
        val comments = """{"itemSectionRenderer":{"sectionIdentifier":"comment-item-section",
            "contents":[${post("Ugkx_comment_metadata")},${cursor("COMMENTS")} ]}}"""
        val body = page(post() + "," + comments + "," + cursor("UNRELATED"))
            .dropLast(1) + ",\"responseContext\":${post("Ugkx_metadata")}}"
        val result = document(body).value!!
        assertEquals(listOf(id), result.posts.map { it.postId }); assertNull(result.nextKey)
        assertEquals("COMMENTS", result.commentsKey) // The independent owned comment opening keeps its own cursor.
        assertNull(ownedPostDocument(jsonObject(body)!!, id)!!.commentsKey)
        assertEquals(id, document(post()).value!!.posts.single().postId)
    }

    @Test fun aDocumentRequiresOneDirectPostWithTheRequestedIdentity() {
        for (body in listOf(page(post("Ugkx_other")), page(post() + "," + post()),
            """{"responseContext":${post()}}""", """{"contents":[]}""")) {
            val result = document(body)
            assertFalse(result.ok); assertEquals(RequestFailure.PARSE, result.error?.kind); assertNull(result.value)
        }
    }

    @Test fun nestedButtonsMenusAndAttachmentMetadataCannotLendCommandsOrAuthorization() {
        val fake = """{"likeButton":{"toggleButtonRenderer":{"defaultServiceEndpoint":${like()}}},
            "signInEndpoint":{},"pollRenderer":{"choices":[{"text":{"simpleText":"Fake"}}]}}"""
        val extra = """, "actionButtons":{"responseContext":$fake,"menu":{"menuRenderer":{"items":[$fake]}}},
            "backstageAttachment":{"backstageImageRenderer":{"image":{"thumbnails":[{"url":"https://image"}]},"responseContext":$fake}}"""
        val item = read(page(post(extra = extra))).value!!.posts.single()
        assertNull(item.like); assertNull(item.dislike); assertNull(item.poll); assertFalse(item.authorizationRequired)
        assertEquals(listOf("https://image"), item.images)
    }

    @Test fun ownButtonRolesPreserveRatingsAndTheirExactPostTargets() {
        val extra = """, "actionButtons":{"postActionButtonsRenderer":{
            "likeButton":{"toggleButtonRenderer":{"defaultServiceEndpoint":${like()},"toggledServiceEndpoint":${like("INDIFFERENT")}}},
            "dislikeButton":{"toggleButtonRenderer":{"defaultServiceEndpoint":${like("DISLIKE")},"toggledServiceEndpoint":${like("INDIFFERENT")}}},
            "responseContext":{"likeButton":{"defaultServiceEndpoint":${like(target = "Ugkx_foreign")}}}}}"""
        val item = read(page(post(extra = extra))).value!!.posts.single()
        assertEquals("like/like", item.like!!.endpoint); assertEquals("like/removelike", item.unlike!!.endpoint)
        assertEquals("like/dislike", item.dislike!!.endpoint); assertEquals("like/removelike", item.undislike!!.endpoint)
        assertEquals(id, jsonObject("{" + item.like!!.payload + "}")!!.obj("target")!!.text("postId"))
    }

    @Test fun wrongTargetsDirectionsAmbiguousCommandsAndApiFallbacksNeverBecomeCapabilities() {
        val multiple = """{"likeEndpoint":{"status":"LIKE","target":{"postId":"$id"}},"feedbackEndpoint":{"feedbackToken":"OTHER"}}"""
        val executor = """{"commandExecutorCommand":{"commands":[${like()},${like()}]}}"""
        val fallback = """{${api("/youtubei/v1/feedback")},"futureEndpoint":{"feedbackToken":"NOT_OWN"}}"""
        for (command in listOf(like(target = "Ugkx_other"), like("DISLIKE"), multiple, executor, fallback,
            like().dropLast(1) + "," + api("/youtubei/v1/like/dislike") + "}")) rejected(page(post(extra = buttons(command))))
        val wrappers = """, "actionButtons":{"postActionButtonsRenderer":{},"commentActionButtonsRenderer":{}}"""
        rejected(page(post(extra = wrappers)))
    }

    @Test fun fixedCommandWrappersCheckAncestorRoutesAndKeepOpaqueTokensRelativeToTheirButton() {
        val own = """{"command":{"innertubeCommand":{"performCommentActionEndpoint":{"action":"OWN_ACTION"}}},
            ${api("/youtubei/v1/comment/perform_comment_action")}}"""
        assertEquals("comment/perform_comment_action", read(page(post(extra = buttons(own)))).value!!.posts.single().like!!.endpoint)
        val badWrapper = own.replace("/youtubei/v1/comment/perform_comment_action", "/youtubei/v1/feedback")
        rejected(page(post(extra = buttons(badWrapper))))
        val badButton = buttons(like()).replace("\"toggleButtonRenderer\":{", "\"toggleButtonRenderer\":{${api("/youtubei/v1/feedback")},")
        rejected(page(post(extra = badButton)))
        val badExecutor = """{"commandExecutorCommand":{"commands":[${like()}],${api("/youtubei/v1/feedback")}}}"""
        rejected(page(post(extra = buttons(badExecutor))))
    }

    @Test fun pollChoicesKeepOnlyDirectIssuedFeedbackAndItsDirectClientActionTotal() {
        val extra = """, "backstageAttachment":{"pollRenderer":{"choices":[{
            "text":{"simpleText":"Choice"},"selected":true,"image":{"thumbnails":[{"url":"https://choice"}]},
            "votePercentage":{"simpleText":"60%"},"selectServiceEndpoint":{"feedbackEndpoint":{"feedbackToken":"SELECT",
            "clientActions":[{"updateBackstagePollAction":{"totalVotes":{"simpleText":"1001 votes"}}}]}},
            "deselectServiceEndpoint":{"feedbackEndpoint":{"feedbackToken":"DESELECT"}}}],"totalVotes":{"simpleText":"1K votes"}}}"""
        val poll = read(page(post(extra = extra))).value!!.posts.single().poll!!
        assertTrue(poll.voted); assertTrue(poll.selectionKnown); assertEquals("1K votes", poll.total)
        val choice = poll.choices.single()
        assertEquals("https://choice", choice.image); assertEquals("60%", choice.percentage)
        assertEquals("1001 votes", choice.select!!.pollTotal); assertEquals("feedback", choice.deselect!!.endpoint)
    }

    @Test fun pollMetadataCannotSupplySelectionTokensTotalsOrSignInState() {
        val extra = """, "backstageAttachment":{"pollRenderer":{"choices":[{"text":{"simpleText":"Choice"},"selected":false,
            "responseContext":{"selectServiceEndpoint":{"feedbackEndpoint":{"feedbackToken":"FOREIGN"}},"signInEndpoint":{}},
            "deselectServiceEndpoint":{"feedbackEndpoint":{"feedbackToken":"OWN","responseContext":{
                "updateBackstagePollAction":{"totalVotes":{"simpleText":"FOREIGN TOTAL"}}}}}}]}}"""
        val item = read(page(post(extra = extra))).value!!.posts.single()
        assertFalse(item.authorizationRequired); assertFalse(item.poll!!.voted); assertTrue(item.poll!!.selectionKnown)
        val choice = item.poll!!.choices.single(); assertNull(choice.select); assertNull(choice.deselect!!.pollTotal)
        assertNull(item.poll!!.total)
    }

    @Test fun unknownOrMalformedOwnAttachmentsFailInsteadOfBorrowingNestedPolls() {
        val validPoll = """{"choices":[{"text":{"simpleText":"Choice"}}]}"""
        for (attachment in listOf("""{"futureAttachment":{"pollRenderer":$validPoll}}""",
            """{"pollRenderer":$validPoll,"backstageImageRenderer":{"image":{"thumbnails":[]}}}""",
            """{"pollRenderer":{"choices":[{"text":{"simpleText":"Choice"},"selected":"true"}]}}"""))
            rejected(page(post(extra = ",\"backstageAttachment\":$attachment")))
    }

    @Test fun onlyAnOwnedSignInEndpointDeclaresAuthorizationRequired() {
        val button = buttons("""{"signInEndpoint":{}}""")
        assertTrue(read(page(post(extra = button))).value!!.posts.single().authorizationRequired)
        val poll = """, "backstageAttachment":{"pollRenderer":{"choices":[{"text":{"simpleText":"Choice"},
            "selectServiceEndpoint":{"signInEndpoint":{}}}]}}"""
        assertTrue(read(page(post(extra = poll))).value!!.posts.single().authorizationRequired)
    }

    @Test fun modernBackstageSectionUsesOnlyTheExactRoleAndKeepsItsOwnPostsAndCursor() {
        val section = """{"sectionListRenderer":{"contents":[{"itemSectionRenderer":{
            "targetId":"backstage-item-section","sectionIdentifier":"backstage-item-section",
            "contents":[{"backstagePostThreadRenderer":{"post":${post()},"useUpdatedRepostUi":true}},${cursor("OWN_MODERN_NEXT")}]
        }}]}}"""
        val root = landing(tab("posts", true, section))
        val result = read(root).value!!
        assertEquals(listOf(id), result.posts.map { it.postId }); assertEquals("OWN_MODERN_NEXT", result.nextKey)
        assertEquals(result, ownedInitialChannelPosts(jsonObject(root)!!, channel))
        val continued = """{"onResponseReceivedActions":[{"appendContinuationItemsAction":{
            "targetId":"backstage-item-section","continuationItems":[${post()}]}}]}"""
        assertEquals(id, read(continued, true).value!!.posts.single().postId)
        for (field in listOf("targetId", "sectionIdentifier")) for (value in listOf("\"future-posts\"", "\"comments-section\"", "7", "{}", "null"))
            rejected("""{"contents":{"itemSectionRenderer":{"$field":$value,"contents":[]}}}""")
    }

    @Test fun repostAndButtonStyleAreTypedPresentationWithoutExtraCommands() {
        val styled = post(extra = """, "actionButtons":{"commentActionButtonsRenderer":{"style":"PRESENTATION_ONLY"}}""")
        assertNull(read(page("""{"backstagePostThreadRenderer":{"post":$styled,"useUpdatedRepostUi":false}}""")).value!!.posts.single().like)
        rejected(page("""{"backstagePostThreadRenderer":{"post":${post()},"useUpdatedRepostUi":"false"}}"""))
        rejected(page(post(extra = """, "actionButtons":{"commentActionButtonsRenderer":{"style":{"likeButton":{"defaultServiceEndpoint":${like()}}}}}""")))
        rejected(page("""{"backstagePostThreadRenderer":{"post":${post()},"useUpdatedRepostUi":true,"futureField":{}}}"""))
    }

    @Test fun directAnonymousNavigationSignInIsARestrictionAndCannotSupplyAMutation() {
        val own = """, "actionButtons":{"commentActionButtonsRenderer":{"style":"PRESENTATION_ONLY",
            "likeButton":{"toggleButtonRenderer":{"defaultNavigationEndpoint":{"signInEndpoint":{"nextEndpoint":${like()}}}}}}}"""
        val item = read(page(post(extra = own))).value!!.posts.single()
        assertTrue(item.authorizationRequired); assertNull(item.like); assertNull(item.unlike)
        assertNull(item.dislike); assertNull(item.undislike)
        val metadata = """, "actionButtons":{"responseContext":{"likeButton":{"toggleButtonRenderer":{
            "defaultNavigationEndpoint":{"signInEndpoint":{}}}}}}"""
        assertFalse(read(page(post(extra = metadata))).value!!.posts.single().authorizationRequired)
        for (navigation in listOf("null", "7", like(), """{"signInEndpoint":"wrong"}""")) {
            val extra = """, "actionButtons":{"likeButton":{"toggleButtonRenderer":{"defaultNavigationEndpoint":$navigation}}}"""
            rejected(page(post(extra = extra)))
        }
        val both = """, "actionButtons":{"likeButton":{"toggleButtonRenderer":{
            "defaultServiceEndpoint":${like()},"defaultNavigationEndpoint":{"signInEndpoint":{}}}}}"""
        rejected(page(post(extra = both)))
        val declaredApi = own.replace("\"toggleButtonRenderer\":{", "\"toggleButtonRenderer\":{${api("/youtubei/v1/browse")},")
        rejected(page(post(extra = declaredApi)))
    }

    @Test fun ownedMultiImageAttachmentKeepsEveryDirectImageInOrderAndIgnoresItsPresentationCommands() {
        fun image(url: String) = """{"backstageImageRenderer":{"image":{"thumbnails":[{"url":"$url"}]},
            "command":${like(target = "Ugkx_other")},"responseContext":{"pollRenderer":{"choices":[]}}}}"""
        val attachment = """, "backstageAttachment":{"postMultiImageRenderer":{"images":[${image("https://one")},${image("https://two")}],
            "loggingDirectives":{},"responseContext":{"backstageImageRenderer":{"image":{"thumbnails":[{"url":"https://foreign"}]}}}}}"""
        val item = read(page(post(extra = attachment))).value!!.posts.single()
        assertEquals(listOf("https://one", "https://two"), item.images)
        assertNull(item.like); assertNull(item.poll); assertFalse(item.authorizationRequired)
    }

    @Test fun malformedOrUnknownMultiImageSlotsCannotBeFilteredIntoAPartialSuccess() {
        val image = """{"backstageImageRenderer":{"image":{"thumbnails":[{"url":"https://one"}]}}}"""
        for (images in listOf("[]", "{}", "[null]", "[7]", "[$image,{}]",
            """[$image,{"futureRenderer":$image}]""", """[{"backstageImageRenderer":{}}]"""))
            rejected(page(post(extra = """, "backstageAttachment":{"postMultiImageRenderer":{"images":$images}}""")))
        val tooMany = List(101) { image }.joinToString(",")
        rejected(page(post(extra = """, "backstageAttachment":{"postMultiImageRenderer":{"images":[$tooMany]}}""")))
    }

    @Test fun performActionRejectsAnAlternativeRequestSourceAndUnknownOrMalformedClientFields() {
        for (extra in listOf(", \"actions\":[\"OTHER\"]", ", \"actions\":null", ", \"actions\":\"wrong\"",
            ", \"clientActions\":\"wrong\"", ", \"clientActions\":null", ", \"clientActions\":{}",
            ", \"clientActions\":7", ", \"futureField\":[]")) {
            val command = """{"performCommentActionEndpoint":{"action":"OWN"$extra}}"""
            rejected(page(post(extra = buttons(command))))
        }
        val tooMany = List(65) { "{}" }.joinToString(",")
        rejected(page(post(extra = buttons("""{"performCommentActionEndpoint":{"action":"OWN","clientActions":[$tooMany]}}"""))))
        for (command in listOf("""{"performCommentActionEndpoint":{"clientActions":[]}}""",
            """{"performCommentActionEndpoint":{"actions":["OTHER"]}}""",
            """{"performCommentActionEndpoint":{"action":"","clientActions":[]}}""",
            """{"performCommentActionEndpoint":{"action":{},"clientActions":[]}}"""))
            rejected(page(post(extra = buttons(command))))
    }

    @Test fun boundedClientUiActionsCannotLendCapabilitiesOrChangeTheOuterActionPayload() {
        val client = """[{"likeButton":{"defaultServiceEndpoint":${like(target = "Ugkx_foreign")}},
            "dislikeButton":{"defaultServiceEndpoint":${like("DISLIKE")}},"signInEndpoint":{},
            "pollRenderer":{"choices":[{"text":{"simpleText":"Foreign"}}]},
            "updateBackstagePollAction":{"totalVotes":{"simpleText":"Foreign total"}},
            "performCommentActionEndpoint":{"action":"FOREIGN_ACTION"}}]"""
        val command = """{"performCommentActionEndpoint":{"action":"OWN","clientActions":$client}}"""
        val item = read(page(post(extra = buttons(command)))).value!!.posts.single()
        // The opaque outer action is scoped to its own button, without a new independent ID claim.
        assertEquals("\"actions\":[\"OWN\"]", item.like!!.payload)
        assertNull(item.like!!.pollTotal); assertNull(item.unlike); assertNull(item.dislike)
        assertNull(item.undislike); assertNull(item.poll); assertFalse(item.authorizationRequired)
        val atLimit = List(64) { "null" }.joinToString(",")
        val limitCommand = """{"performCommentActionEndpoint":{"action":"OWN","clientActions":[$atLimit]}}"""
        assertEquals(item.like, read(page(post(extra = buttons(limitCommand)))).value!!.posts.single().like)
    }

    @Test fun aNetworkOwnedPerformCommandDispatchesOnlyTheOuterActionAndNeverItsClientUiCommands() {
        val client = """[{"createCommentEndpoint":{"createCommentParams":"CLIENT_CREATE"},
            "deletePlaylistEndpoint":{"playlistId":"CLIENT_DELETE"},
            "commandMetadata":{"webCommandMetadata":{"apiUrl":"https://foreign.invalid/CLIENT_URL"}},
            "performCommentActionEndpoint":{"action":"CLIENT_ACTION"}}]"""
        val endpoint = """{"performCommentActionEndpoint":{"action":"OWN_ACTION","clientActions":$client},
            ${api("/youtubei/v1/comment/perform_comment_action")}}"""
        val paths = mutableListOf<String>()
        val wire = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                val path = java.net.URI(request.url).path
                paths += path
                return if (path == "/youtubei/v1/browse") HttpReply(200, page(post(extra = buttons(endpoint))))
                else {
                    assertEquals("/youtubei/v1/comment/perform_comment_action", path)
                    val body = jsonObject(request.body)!!
                    assertEquals(listOf("OWN_ACTION"), body.arr("actions").map { (it as? Json.Str)?.value })
                    for (forbidden in listOf("clientActions", "CLIENT_CREATE", "CLIENT_DELETE", "CLIENT_URL", "CLIENT_ACTION"))
                        assertFalse(request.body.contains(forbidden))
                    HttpReply(200, """{"status":"STATUS_SUCCEEDED"}""")
                }
            }
        }
        val context = RequestContext(webSession = WebSession("SAPISID=fixture"))
        val item = loadChannelPosts(channel, context = context, http = wire).value!!.posts.single()
        assertTrue(performPostCommand(item.like, context, wire).ok)
        assertEquals(listOf("/youtubei/v1/browse", "/youtubei/v1/comment/perform_comment_action"), paths)
        val foreignAncestor = endpoint.replace("/youtubei/v1/comment/perform_comment_action", "/youtubei/v1/feedback")
        rejected(page(post(extra = buttons(foreignAncestor))))
    }

    /** Post items and comment continuations belong to separate page sections. */
    @Test fun aWebPostDocumentReadsOnlyItsSelectedPrimaryPostAndKeepsTheCommentsCursorSeparate() {
        val content = """{"sectionListRenderer":{"disablePullToRefresh":true,"contents":[
            {"itemSectionRenderer":{"sectionIdentifier":"backstage-item-section","header":{},
                "contents":[{"backstagePostThreadRenderer":{"post":${post()},"useUpdatedRepostUi":true}}]}},
            {"itemSectionRenderer":{"sectionIdentifier":"comment-item-section","targetId":"comments-section",
                "contents":[${cursor("COMMENTS")} ]}}]}}"""
        for (layout in listOf("twoColumnBrowseResultsRenderer", "singleColumnBrowseResultsRenderer")) {
            val body = webPostDocument(content, layout)
            val result = document(body)
            assertTrue(result.ok); assertEquals(id, result.value!!.posts.single().postId); assertNull(result.value!!.nextKey)
            val owned = ownedPostDocument(jsonObject(body)!!, id)!!
            assertNull(owned.nextKey); assertNull(owned.commentsKey)
        }
    }

    @Test fun webPostDocumentsNeverBorrowPostsOrCommandsFromUnselectedTabsAndMetadata() {
        val foreign = post("Ugkx_foreign", buttons(like(target = "Ugkx_foreign")))
        val content = page(post()).dropLast(1) + ",\"responseContext\":${page(foreign + "," + cursor("FOREIGN"))}}"
        val selected = """{"tabRenderer":{"title":"Post","selected":true,"content":$content}}"""
        val unselected = """{"tabRenderer":{"selected":false,"content":${page(foreign)}}}"""
        val body = """{"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[$selected,$unselected]}},
            "responseContext":${page(foreign + "," + cursor("FOREIGN"))}}"""
        val result = document(body).value!!
        assertEquals(listOf(id), result.posts.map { it.postId }); assertNull(result.nextKey)
        assertNull(result.posts.single().like); assertFalse(result.posts.single().authorizationRequired)
    }

    @Test fun aSelectedWebDocumentCannotFallBackWhenItsPrimaryContentIsMissingMalformedOrUnknown() {
        val valid = webPostDocument(page(post()))
        val base = listOf(
            valid.replace("\"selected\":true", "\"selected\":false"),
            valid.replace("\"selected\":true", "\"selected\":\"true\""),
            valid.replace("\"selected\":true,", ""),
            webPostDocument("null"), webPostDocument("[]"), webPostDocument("{}"),
            webPostDocument("""{"futureContainer":${page(post())}}"""),
            webPostDocument("""{"responseContext":${page(post())}}"""),
            webPostDocument("""{"contents":[]}"""))
        for (body in base) {
            val withFallback = body.dropLast(1) + ",\"responseContext\":${page(post())}}"
            val result = document(withFallback)
            assertFalse(result.ok); assertEquals(RequestFailure.PARSE, result.error?.kind); assertNull(result.value)
        }
    }

    @Test fun webPostDocumentsRejectConflictingLayoutsSelectedTabsAndParallelBusinessEnvelopes() {
        val selected = """{"tabRenderer":{"selected":true,"content":${page(post())}}}"""
        val layout = """{"tabs":[$selected]}"""
        val bodies = listOf(
            """{"contents":{"twoColumnBrowseResultsRenderer":$layout,"singleColumnBrowseResultsRenderer":$layout}}""",
            """{"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[$selected,$selected]}}}""",
            """{"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"selected":true,"content":${page(post())}},"expandableTabRenderer":{}}]}}}""",
            webPostDocument(page(post())).dropLast(1) + ",\"continuationContents\":{}}",
            webPostDocument("""{"contents":[${post()}],"onResponseReceivedActions":[]}"""))
        for (body in bodies) {
            val result = document(body)
            assertFalse(result.ok); assertEquals(RequestFailure.PARSE, result.error?.kind); assertNull(result.value)
        }
    }

    @Test fun theSelectedWebDocumentRequiresOneExactRequestedPostWithoutDeduplicatingDuplicates() {
        for (slots in listOf(post("Ugkx_other"), post() + "," + post(), cursor("NOT_A_POST"))) {
            val body = webPostDocument(page(slots)).dropLast(1) + ",\"responseContext\":${page(post())}}"
            val result = document(body)
            assertFalse(result.ok); assertEquals(RequestFailure.PARSE, result.error?.kind); assertNull(result.value)
        }
        val foreignRoute = webPostDocument(page(post())).replace("\"selected\":true,",
            "\"selected\":true,${api("/youtubei/v1/feedback")},")
        assertEquals(RequestFailure.PARSE, document(foreignRoute).error?.kind)
    }

    @Test fun unknownDocumentSectionsCannotBeIgnoredAlongsideAnOtherwiseValidPost() {
        for (field in listOf("sectionIdentifier", "targetId")) for (value in listOf("\"future-section\"", "7", "{}")) {
            val content = """{"sectionListRenderer":{"contents":[${post()},
                {"itemSectionRenderer":{"$field":$value,"contents":[${cursor("FOREIGN")} ]}}]}}"""
            val result = document(webPostDocument(content))
            assertFalse(result.ok); assertEquals(RequestFailure.PARSE, result.error?.kind); assertNull(result.value)
        }
    }

    @Test fun unknownMetadataNeverBypassesTheFiniteResponseBudget() {
        var nested = "{}"
        repeat(100) { nested = "{\"responseContext\":$nested}" }
        assertNull(ownedChannelPosts(jsonObject(page(post()).dropLast(1) + ",\"responseContext\":$nested}")!!, channel, false))
    }
}
