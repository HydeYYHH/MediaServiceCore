package com.liskovsoft.youtubeapi.videoinfo.V2

import java.net.URI
import org.junit.Assert.*
import org.junit.Test

/** App-level flows use issued domain capabilities and one shared presentation preparation pass. */
class PreparedCatalogTest {
    private val channel = "UC_x5XG1OV2P6uZZ5FSM9Ttw"
    private val other = "UCBR8-60-B28hp2BmDPdntcQ"
    private val video = "jNQXAC9IVRw"
    private val postId = "Ugkx_prepared_post"
    private class Wire(private val reply: (HttpRequest, Int) -> HttpReply) : HttpClient {
        val requests = mutableListOf<HttpRequest>()
        override fun execute(request: HttpRequest): HttpReply { requests += request; return reply(request, requests.lastIndex) }
    }
    private fun tab(kind: String, selected: Boolean = false, content: String? = null) =
        """{"tabRenderer":{"tabIdentifier":"$kind","selected":$selected,"endpoint":{"browseEndpoint":{"browseId":"$channel","params":"issued-$kind"}}${content?.let { ",\"content\":$it" }.orEmpty()}}}"""
    private fun landing(tabs: List<String>) = """{"metadata":{"channelMetadataRenderer":{"externalId":"$channel","title":"Lessons"}},"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[${tabs.joinToString(",")}]}}}"""
    private fun card(id: String = video, authorId: String = channel, avatar: Boolean = false) =
        """{"videoRenderer":{"videoId":"$id","title":"Lesson","longBylineText":{"runs":[{"text":"Teacher","navigationEndpoint":{"browseEndpoint":{"browseId":"$authorId"}}}]}${if (avatar) ",\"channelThumbnail\":{\"thumbnails\":[{\"url\":\"https://example.test/avatar\",\"width\":88,\"height\":88}]}" else ""}}}"""
    private fun cursor(key: String) = """{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"$key"}}}}"""
    private fun cards(key: String? = null) = """{"contents":{"richGridRenderer":{"contents":[${card()}${key?.let { "," + cursor(it) }.orEmpty()}]}}}"""
    private fun like(status: String) = """{"likeEndpoint":{"status":"$status","target":{"postId":"$postId"},"likeParams":"private-command"}}"""
    private fun post(avatar: Boolean = true) = """{"postId":"$postId","authorText":"Teacher","authorEndpoint":{"browseEndpoint":{"browseId":"$channel"}},"contentText":"Announcement","publishedTimeText":"Today","voteStatus":"LIKE","voteCount":"12","replyCount":"3",
        ${if (avatar) "\"authorThumbnail\":{\"thumbnails\":[{\"url\":\"https://example.test/post-avatar\"}]}," else ""}
        "actionButtons":{"postActionButtonsRenderer":{"likeButton":{"toggleButtonRenderer":{"defaultServiceEndpoint":${like("LIKE")},"toggledServiceEndpoint":${like("INDIFFERENT")}}},"dislikeButton":{"toggleButtonRenderer":{"defaultServiceEndpoint":${like("DISLIKE")},"toggledServiceEndpoint":${like("INDIFFERENT")}}}}},
        "backstageAttachment":{"pollRenderer":{"totalVotes":"5","choices":[{"text":"One","selected":false,"selectServiceEndpoint":{"feedbackEndpoint":{"feedbackToken":"private-poll","clientActions":[{"updateBackstagePollAction":{"totalVotes":"6"}}]}}},{"text":"Two","selected":true,"deselectServiceEndpoint":{"performCommentActionEndpoint":{"action":"private-deselect"}}}]}}}"""
    private fun postPage(next: String? = null) = """{"contents":[{"backstagePostRenderer":${post()}}${next?.let { "," + cursor(it) }.orEmpty()}]}"""
    private fun document(body: String = post()) = """<script>var ytInitialData = {"contents":[{"backstagePostRenderer":$body}]};</script>"""
    private fun metadata(id: String = channel) = """{"metadata":{"channelMetadataRenderer":{"externalId":"$id","avatar":{"thumbnails":[{"url":"https://example.test/public-avatar","width":176,"height":176}]}}}}"""

    @Test fun channelCardsKeepIssuedTabRouteAndCursorWithoutAppProtocolWork() {
        val wire = Wire { request, index -> when (index) {
            0 -> HttpReply(200, landing(listOf(tab("home", true), tab("videos"))))
            1 -> { val body = jsonObject(request.body)!!; assertEquals("issued-videos", body.text("params")); assertEquals(channel, body.text("browseId")); HttpReply(200, cards("private-tab-next")) }
            else -> { val body = jsonObject(request.body)!!; assertEquals("private-tab-next", body.text("continuation")); assertNull(body.text("browseId")); assertNull(body.text("params")); HttpReply(200, """{"onResponseReceivedActions":[{"appendContinuationItemsAction":{"continuationItems":[${card()}]}}]}""") }
        } }
        val catalog = CatalogSession(AccountSession(), wire)
        val page = catalog.channel(channel, ChannelTabKind.VIDEOS).value!!
        assertEquals(video, page.items.single().item.id); assertEquals(channel, page.items.single().author.channelId)
        assertEquals("request_budget_exhausted", page.items.single().author.unavailableReason)
        assertNull(page.items.single().post); assertEquals(0, page.preparationRequestCount)
        assertFalse(page.next.toString().contains("private"))
        assertEquals(video, catalog.next(page.next!!).value!!.items.single().item.id)
        assertEquals(3, wire.requests.size)
    }

    @Test fun channelPostsRetainTheCompleteNativePostAndUseTheirOwnIssuedContinuation() {
        val wire = Wire { request, index -> when (index) {
            0 -> HttpReply(200, landing(listOf(tab("home", true), tab("posts"))))
            1 -> { assertEquals("issued-posts", jsonObject(request.body)!!.text("params")); HttpReply(200, postPage("private-post-next")) }
            else -> { assertEquals("private-post-next", jsonObject(request.body)!!.text("continuation")); HttpReply(200, postPage()) }
        } }
        val catalog = CatalogSession(AccountSession(), wire)
        val first = catalog.channel(channel, ChannelTabKind.POSTS).value!!
        val item = first.items.single(); val prepared = item.post!!; val raw = prepared.post
        assertSame(item.item.post, raw); assertSame(item.author, prepared.author)
        assertEquals(postId, raw.postId); assertEquals("Announcement", raw.text); assertEquals("LIKE", raw.voteStatus)
        assertNotNull(raw.like); assertNotNull(raw.unlike); assertNotNull(raw.dislike); assertNotNull(raw.undislike)
        assertEquals("private-poll", (jsonObject("{" + raw.poll!!.choices[0].select!!.payload + "}")!!.arr("feedbackTokens").first() as Json.Str).value)
        assertNotNull(raw.poll!!.choices[1].deselect)
        assertEquals("https://example.test/post-avatar", prepared.author.avatar!!.best!!.url)
        assertEquals(postId, catalog.next(first.next!!).value!!.items.single().post!!.post.postId)
        assertEquals(3, wire.requests.size)
    }

    @Test fun singlePostUsesTheOwnedDocumentAndHasPureSafePresentationGetters() {
        val wire = Wire { request, _ -> assertEquals("GET", request.method); assertEquals("/post/$postId", URI(request.url).path); HttpReply(200, document()) }
        val result = CatalogSession(AccountSession(), wire).post(postId).value!!
        assertEquals("PreparedPost(authorArtworkAvailable=true)", result.toString())
        assertFalse(result.toString().contains("private-command")); assertFalse(result.toString().contains(postId))
        assertEquals(channel, result.author.channelId); assertEquals("Teacher", result.author.name)
        assertEquals("https://example.test/post-avatar", result.author.avatar!!.best!!.url)
        assertNull(result.author.unavailableReason); assertNotNull(result.post.poll)
        val copy = result.copy(); assertSame(result.post, copy.post); assertSame(result.author, copy.author)
        assertEquals(result, copy); assertEquals(result.hashCode(), copy.hashCode())
        val (raw, author) = copy; assertSame(result.post, raw); assertSame(result.author, author)
        assertEquals(1, wire.requests.size)
    }

    @Test fun selectedChannelSurfaceReusesItsOpeningWithoutAnotherBrowse() {
        val wire = Wire { _, _ -> HttpReply(200, landing(listOf(tab("videos", true, """{"richGridRenderer":{"contents":[${card(avatar = true)}]}}""")))) }
        val page = CatalogSession(AccountSession(), wire).channel(channel, ChannelTabKind.VIDEOS).value!!
        assertNotNull(page.items.single().author.avatar); assertNull(page.next); assertEquals(1, wire.requests.size)
    }

    @Test fun selectedPostsUseTheSamePreparationPassAndNoExtraBrowse() {
        val wire = Wire { _, _ -> HttpReply(200, landing(listOf(tab("posts", true, """{"sectionListRenderer":{"contents":[{"backstagePostRenderer":${post()}}]}}""")))) }
        val page = CatalogSession(AccountSession(), wire).channel(channel, ChannelTabKind.POSTS).value!!
        assertEquals(postId, page.items.single().post!!.post.postId); assertNotNull(page.items.single().author.avatar)
        assertEquals(0, page.preparationRequestCount); assertEquals(1, wire.requests.size)
    }

    @Test fun handlesAreResolvedByTheExistingChannelDomainBeforePreparation() {
        val wire = Wire { request, index -> if (index == 0) {
            assertEquals("/youtubei/v1/navigation/resolve_url", URI(request.url).path)
            assertEquals("https://www.youtube.com/@lessons", jsonObject(request.body)!!.text("url"))
            HttpReply(200, """{"endpoint":{"browseEndpoint":{"browseId":"$channel"}}}""")
        } else HttpReply(200, landing(listOf(tab("videos", true, """{"richGridRenderer":{"contents":[${card()}]}}""")))) }
        val page = CatalogSession(AccountSession(), wire).channel("@lessons", ChannelTabKind.VIDEOS).value!!
        assertEquals(video, page.items.single().item.id); assertEquals(2, wire.requests.size)
    }

    @Test fun positivePostBudgetPreparesOnceAndTheFollowingChannelUsesTheSamePublicCache() {
        val wire = Wire { request, index -> when (index) {
            0 -> { assertEquals("GET", request.method); HttpReply(200, document(post(false))) }
            1 -> { assertTrue(request.url.contains("fields=metadata%2Cheader")); assertEquals(channel, jsonObject(request.body)!!.text("browseId")); HttpReply(200, metadata()) }
            else -> HttpReply(200, landing(listOf(tab("videos", true, """{"richGridRenderer":{"contents":[${card()}]}}"""))))
        } }
        val catalog = CatalogSession(AccountSession(), wire, 1)
        val post = catalog.post(postId).value!!
        assertEquals("https://example.test/public-avatar", post.author.avatar!!.best!!.url)
        val page = catalog.channel(channel, ChannelTabKind.VIDEOS).value!!
        assertEquals(post.author.avatar, page.items.single().author.avatar)
        assertEquals(0, page.preparationRequestCount); assertEquals(3, wire.requests.size)
    }

    @Test fun wholePageBudgetIncludesGroupsAndMetadataDerivesOnlyPublicIdentity() {
        val account = AccountSession(RequestContext(webSession = WebSession("SAPISID=fixture", 1), onBehalfOfUser = "brand", visitor = "visitor", visitorCookie = "YSC=guest"))
        val wire = Wire { request, index -> if (index == 0) {
            assertNotNull(request.headers["Authorization"])
            HttpReply(200, """{"contents":{"sectionListRenderer":{"contents":[${card()}, {"shelfRenderer":{"title":"Group","content":{"horizontalListRenderer":{"items":[${card("second", other)}]}}}}]}}}""")
        } else {
            assertEquals(channel, jsonObject(request.body)!!.text("browseId")); assertTrue(request.url.contains("fields=metadata%2Cheader"))
            for (header in listOf("Authorization", "Cookie", "X-Goog-AuthUser", "X-Goog-PageId")) assertFalse(request.headers.keys.any { it.equals(header, true) })
            val context = jsonObject(request.body)!!.obj("context")!!
            assertNull(context.obj("user")?.text("onBehalfOfUser")); assertEquals("visitor", context.obj("client")!!.text("visitorData"))
            HttpReply(200, metadata())
        } }
        val page = CatalogSession(account, wire, 1).home().value!!
        val all = page.items + page.groups.flatMap { it.items }
        assertEquals(1, page.preparationRequestCount); assertNotNull(all.first { it.item.channelId == channel }.author.avatar)
        assertTrue(all.filter { it.item.channelId == other }.all { it.author.unavailableReason == "request_budget_exhausted" })
        assertEquals(2, wire.requests.size)
    }

    @Test fun channelAndPostShareTheFeedPreparationCacheAndExplicitClearDropsIt() {
        val wire = Wire { request, index -> when (index) {
            0 -> HttpReply(200, cards().replace(card(), card(avatar = true)))
            1, 4 -> HttpReply(200, landing(listOf(tab("videos", true, """{"richGridRenderer":{"contents":[${card()}]}}"""))))
            else -> { assertEquals("GET", request.method); HttpReply(200, document(post(false))) }
        } }
        val catalog = CatalogSession(AccountSession(), wire)
        assertNotNull(catalog.home().value!!.items.single().author.avatar)
        assertNotNull(catalog.channel(channel, ChannelTabKind.VIDEOS).value!!.items.single().author.avatar)
        assertNotNull(catalog.post(postId).value!!.author.avatar)
        catalog.clearPreparedData()
        assertEquals("request_budget_exhausted", catalog.post(postId).value!!.author.unavailableReason)
        assertEquals("request_budget_exhausted", catalog.channel(channel, ChannelTabKind.VIDEOS).value!!.items.single().author.unavailableReason)
        assertEquals(5, wire.requests.size)
    }

    @Test fun unownedGroupContinuationIsVisibleAsUnavailableInsteadOfClaimingGroupEnd() {
        val body = """{"contents":{"sectionListRenderer":{"contents":[{"shelfRenderer":{"title":"Group","content":{"horizontalListRenderer":{"items":[${card()}],"continuations":[{"nextContinuationData":{"continuation":"unowned-group-token"}}]}}}}]}}}"""
        val wire = Wire { _, _ -> HttpReply(200, body) }
        val page = CatalogSession(AccountSession(), wire).home().value!!; val group = page.groups.single()
        assertNull(group.next); assertEquals("continuation_route_unavailable", group.continuationUnavailableReason)
        assertEquals(1, wire.requests.size)
        val noCursor = CatalogSession(AccountSession(), Wire { _, _ -> HttpReply(200, body.replace(",\"continuations\":[{\"nextContinuationData\":{\"continuation\":\"unowned-group-token\"}}]", "")) }).home().value!!.groups.single()
        assertNull(noCursor.next); assertNull(noCursor.continuationUnavailableReason)
    }

    @Test fun issuedChannelCatalogCursorsCannotBeForgedTransferredOrUsedAfterIdentityChanges() {
        val account = AccountSession()
        val wire = Wire { _, index -> HttpReply(200, if (index == 0) landing(listOf(tab("home", true), tab("videos"))) else cards("issued-next")) }
        val catalog = CatalogSession(account, wire); val cursor = catalog.channel(channel, ChannelTabKind.VIDEOS).value!!.next!!
        assertEquals(RequestFailure.INVALID, CatalogSession(account, wire).next(cursor).error?.kind)
        assertEquals(RequestFailure.INVALID, catalog.next(CatalogCursor(catalog, 0, cursor.route, null)).error?.kind)
        account.clear(); assertEquals(RequestFailure.CANCELLED, catalog.next(cursor).error?.kind)
        assertEquals(2, wire.requests.size)
    }

    @Test fun postAndChannelFenceIdentityChangesAcrossOpeningAndAuthorPreparation() {
        val account = AccountSession()
        val postWire = Wire { _, _ -> account.clear(); HttpReply(200, document(post(false))) }
        assertEquals(RequestFailure.CANCELLED, CatalogSession(account, postWire, 1).post(postId).error?.kind)
        assertEquals(1, postWire.requests.size)
        val channelWire = Wire { _, index -> when (index) {
            0 -> HttpReply(200, landing(listOf(tab("videos", true, """{"richGridRenderer":{"contents":[${card()}]}}"""))))
            else -> { account.clear(); HttpReply(200, metadata()) }
        } }
        assertEquals(RequestFailure.CANCELLED, CatalogSession(account, channelWire, 1).channel(channel, ChannelTabKind.VIDEOS).error?.kind)
        assertEquals(2, channelWire.requests.size)
    }

    @Test fun invalidUnsupportedAndUnknownSourcesDoNotTriggerFallbackReads() {
        val wire = Wire { _, _ -> HttpReply(200, "{}") }; val catalog = CatalogSession(AccountSession(), wire, 4)
        assertEquals(RequestFailure.UNSUPPORTED, catalog.channel(channel, ChannelTabKind.UNKNOWN).error?.kind)
        assertEquals(RequestFailure.INVALID, catalog.channel("bad channel", ChannelTabKind.VIDEOS).error?.kind)
        assertEquals(RequestFailure.INVALID, catalog.post("bad id").error?.kind); assertEquals(0, wire.requests.size)
        assertEquals(RequestFailure.PARSE, catalog.post(postId).error?.kind); assertEquals(1, wire.requests.size)
        assertEquals(RequestFailure.PARSE, catalog.channel(channel, ChannelTabKind.VIDEOS).error?.kind); assertEquals(2, wire.requests.size)
    }

    @Test fun ownedChannelPresentationPreparesMatchingCardsAndTheirIssuedNextWithoutExtraIo() {
        val idOnly = """{"videoRenderer":{"videoId":"$video","channelId":"$channel","title":"Lesson"}}"""
        val opening = landing(listOf(tab("home", true), tab("videos")))
            .replace("\"title\":\"Lessons\"", "\"title\":\"Owner\",\"avatar\":{\"thumbnails\":[{\"url\":\"https://example.test/owner\",\"width\":88,\"height\":88}]}")
        val wire = Wire { request, index -> when (index) {
            0 -> HttpReply(200, opening)
            1 -> { assertEquals("issued-videos", jsonObject(request.body)!!.text("params")); HttpReply(200, """{"contents":{"richGridRenderer":{"contents":[$idOnly,${cursor("owner-next")}]}}}""") }
            else -> { assertEquals("owner-next", jsonObject(request.body)!!.text("continuation")); HttpReply(200, """{"onResponseReceivedActions":[{"appendContinuationItemsAction":{"continuationItems":[$idOnly]}}]}""") }
        } }
        val catalog = CatalogSession(AccountSession(), wire)
        val first = catalog.channel(channel, ChannelTabKind.VIDEOS).value!!
        val prepared = first.items.single()
        assertNull(prepared.item.author); assertEquals(channel, prepared.item.channelId)
        assertEquals("Owner", prepared.author.name); assertEquals("https://example.test/owner", prepared.author.avatar!!.best!!.url)
        assertNull(prepared.author.unavailableReason); assertEquals(0, first.preparationRequestCount)
        catalog.clearPreparedData()
        val next = catalog.next(first.next!!).value!!
        assertEquals(prepared.author, next.items.single().author); assertNull(next.items.single().item.author)
        assertEquals(0, next.preparationRequestCount); assertEquals(3, wire.requests.size)
    }

    @Test fun channelOwnerIsNotInferredForMissingOrDifferentAuthorsAndCardArtworkWins() {
        val own = card(avatar = true)
        val foreign = card("foreign", other)
        val unknown = """{"videoRenderer":{"videoId":"unknown","title":"Lesson"}}"""
        val contents = """{"richGridRenderer":{"contents":[$own,$foreign,$unknown]}}"""
        val opening = landing(listOf(tab("videos", true, contents))).replace("\"title\":\"Lessons\"",
            "\"title\":\"Owner\",\"avatar\":{\"thumbnails\":[{\"url\":\"https://example.test/owner\"}]}")
        val wire = Wire { _, _ -> HttpReply(200, opening) }
        val page = CatalogSession(AccountSession(), wire).channel(channel, ChannelTabKind.VIDEOS).value!!
        val byId = page.items.associateBy { it.item.id }
        assertEquals("https://example.test/avatar", byId.getValue(video).author.avatar!!.best!!.url)
        assertEquals("Teacher", byId.getValue(video).author.name)
        assertNull(byId.getValue("foreign").author.avatar)
        assertEquals("request_budget_exhausted", byId.getValue("foreign").author.unavailableReason)
        assertNull(byId.getValue("unknown").author.channelId); assertNull(byId.getValue("unknown").author.name)
        assertEquals("canonical_channel_id_unavailable", byId.getValue("unknown").author.unavailableReason)
        assertEquals(0, page.preparationRequestCount); assertEquals(1, wire.requests.size)
    }

    @Test fun conflictingDirectOwnerIdentityCannotSeedPreparationOrTheSharedCache() {
        val opening = landing(listOf(tab("videos", true, """{"richGridRenderer":{"contents":[${card()}]}}""")))
            .dropLast(1) + """, "header":{"c4TabbedHeaderRenderer":{"channelId":"$other","avatar":{"thumbnails":[{"url":"https://example.test/shadow"}]}}}}"""
        val wire = Wire { _, index -> HttpReply(200, if (index == 0) opening else document(post(false))) }
        val catalog = CatalogSession(AccountSession(), wire)
        val page = catalog.channel(channel, ChannelTabKind.VIDEOS).value!!
        assertNull(page.items.single().author.avatar)
        assertEquals("request_budget_exhausted", page.items.single().author.unavailableReason)
        assertNull(catalog.post(postId).value!!.author.avatar)
        assertEquals(2, wire.requests.size)
    }

    @Test fun seededPresentationDoesNotKeepAnIssuedCursorAliveAcrossIdentityChanges() {
        val opening = landing(listOf(tab("videos", true, """{"richGridRenderer":{"contents":[${card()},${cursor("seeded-next")}]}}""")))
            .replace("\"title\":\"Lessons\"", "\"title\":\"Owner\",\"avatar\":{\"thumbnails\":[{\"url\":\"https://example.test/owner\"}]}")
        val account = AccountSession(); val wire = Wire { _, _ -> HttpReply(200, opening) }
        val catalog = CatalogSession(account, wire); val first = catalog.channel(channel, ChannelTabKind.VIDEOS).value!!
        assertNotNull(first.items.single().author.avatar)
        account.clear()
        assertEquals(RequestFailure.CANCELLED, catalog.next(first.next!!).error?.kind)
        assertEquals(1, wire.requests.size)
    }
}
