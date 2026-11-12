package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class ChannelVideoBylineTest {
    private val owner = "UC_x5XG1OV2P6uZZ5FSM9Ttw"
    private val other = "UCBR8-60-B28hp2BmDPdntcQ"
    private val video = "jNQXAC9IVRw"
    private val second = "dQw4w9WgXcQ"
    private val avatar = "https://example.test/owner-avatar"
    private class Wire(val reply: (HttpRequest, Int) -> String) : HttpClient {
        val requests = ArrayList<HttpRequest>()
        override fun execute(request: HttpRequest): HttpReply {
            requests += request
            return HttpReply(200, reply(request, requests.lastIndex))
        }
    }
    private fun card(id: String = video, extra: String = "") =
        """{"richItemRenderer":{"content":{"lockupViewModel":{"contentId":"$id","contentType":"LOCKUP_CONTENT_TYPE_VIDEO","metadata":{"lockupMetadataViewModel":{"title":{"content":"Video"}}}$extra}}}}"""
    private fun cursor(key: String = "issued-next") = """{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"$key"}}}}"""
    private fun grid(vararg slots: String) = """{"richGridRenderer":{"contents":[${slots.joinToString(",")}]}}"""
    private fun tab(kind: String, selected: Boolean, content: String? = null) =
        """{"tabRenderer":{"tabIdentifier":"$kind","selected":$selected,"endpoint":{"browseEndpoint":{"browseId":"$owner","params":"issued-$kind"}}${content?.let { ",\"content\":$it" }.orEmpty()}}}"""
    private fun landing(vararg tabs: String, metadataOwner: String = owner) =
        """{"metadata":{"channelMetadataRenderer":{"externalId":"$metadataOwner","title":"Owner","avatar":{"thumbnails":[{"url":"$avatar","width":88,"height":88}]}}},"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[${tabs.joinToString(",")}]}}}"""
    private fun continued(vararg slots: String) = """{"onResponseReceivedActions":[{"appendContinuationItemsAction":{"continuationItems":[${slots.joinToString(",")}]}}]}"""
    private fun opened(body: String, kind: ChannelTabKind = ChannelTabKind.VIDEOS): Pair<CatalogPage, Wire> {
        val wire = Wire { _, _ -> body }
        return CatalogSession(AccountSession(), wire).channel(owner, kind).value!! to wire
    }
    private fun projection(body: String, role: ChannelVideoResponseRole = ChannelVideoResponseRole.OPENING,
        page: BrowsePage = browsePage(body)): Set<MediaItem> = ownedChannelVideoItems(jsonObject(body)!!, owner, page, role)

    @Test fun selectedOwnedVideosExposeOneZeroIoBylineWithoutInventingAuthorIdentity() {
        val (page, wire) = opened(landing(tab("videos", true, grid(card(), card(second)))))
        assertEquals(2, page.items.size)
        for (prepared in page.items) {
            assertNull(prepared.item.channelId); assertNull(prepared.item.author)
            assertNull(prepared.author.channelId); assertNull(prepared.author.avatar)
            assertEquals("canonical_channel_id_unavailable", prepared.author.unavailableReason)
            assertEquals(DisplayBylineRole.COLLECTION_OWNER, prepared.displayByline.role)
            assertEquals(owner, prepared.displayByline.author.channelId)
            assertEquals("Owner", prepared.displayByline.author.name)
            assertEquals(avatar, prepared.displayByline.author.avatar!!.best!!.url)
            assertNull(prepared.displayByline.author.unavailableReason)
            assertNull(prepared.post)
        }
        assertEquals(0, page.preparationRequestCount); assertEquals(1, wire.requests.size)
    }

    @Test fun actualIssuedVideosRouteAndCursorCarryTheSamePresentationOwner() {
        val wire = Wire { request, index -> when (index) {
            0 -> landing(tab("home", true), tab("videos", false))
            1 -> {
                assertEquals("issued-videos", jsonObject(request.body)!!.text("params"))
                """{"contents":${grid(card(), cursor())}}"""
            }
            else -> {
                val body = jsonObject(request.body)!!
                assertEquals("issued-next", body.text("continuation")); assertNull(body.text("params")); assertNull(body.text("browseId"))
                continued(card(second))
            }
        } }
        val catalog = CatalogSession(AccountSession(), wire)
        val first = catalog.channel(owner, ChannelTabKind.VIDEOS).value!!
        val next = catalog.next(first.next!!).value!!
        assertEquals(DisplayBylineRole.COLLECTION_OWNER, first.items.single().displayByline.role)
        assertEquals(first.items.single().displayByline.author, next.items.single().displayByline.author)
        assertEquals(second, next.items.single().item.id)
        assertEquals(0, next.preparationRequestCount); assertEquals(3, wire.requests.size)
    }

    @Test fun staleOrForeignCatalogCursorsCannotReadOrApplyAnOwnerSeed() {
        val account = AccountSession()
        val wire = Wire { _, _ -> landing(tab("videos", true, grid(card(), cursor()))) }
        val catalog = CatalogSession(account, wire)
        val first = catalog.channel(owner, ChannelTabKind.VIDEOS).value!!
        assertEquals(RequestFailure.INVALID, CatalogSession(account, wire).next(first.next!!).error!!.kind)
        account.clear()
        assertNotNull(catalog.next(first.next!!).error)
        assertEquals(1, wire.requests.size)
    }

    @Test fun explicitForeignChannelTabCannotCarryTheOpeningOwnerIntoItsPageOrCursor() {
        val foreignTab = tab("videos", false).replace("\"browseId\":\"$owner\"", "\"browseId\":\"$other\"")
        val wire = Wire { request, index -> when (index) {
            0 -> landing(tab("home", true), foreignTab)
            1 -> {
                assertEquals(other, jsonObject(request.body)!!.text("browseId"))
                """{"contents":${grid(card(), cursor())}}"""
            }
            else -> continued(card(second))
        } }
        val catalog = CatalogSession(AccountSession(), wire)
        val first = catalog.channel(owner, ChannelTabKind.VIDEOS).value!!
        val next = catalog.next(first.next!!).value!!
        assertNotEquals(DisplayBylineRole.COLLECTION_OWNER, first.items.single().displayByline.role)
        assertNotEquals(DisplayBylineRole.COLLECTION_OWNER, next.items.single().displayByline.role)
        assertEquals(3, wire.requests.size)
        val selected = landing(tab("videos", true, grid(card())).replace("\"browseId\":\"$owner\"", "\"browseId\":\"$other\""))
        assertTrue(projection(selected).isEmpty())
        assertNotEquals(DisplayBylineRole.COLLECTION_OWNER, opened(selected).first.items.single().displayByline.role)
    }

    @Test fun itemOwnedArtworkAndCanonicalAuthorKeepTheirOriginalSemantics() {
        val native = ",\"shortBylineText\":{\"runs\":[{\"text\":\"Owner\",\"navigationEndpoint\":{\"browseEndpoint\":{\"browseId\":\"$owner\"}}}]},\"channelThumbnail\":{\"thumbnails\":[{\"url\":\"https://example.test/item-avatar\"}]}"
        val (page, _) = opened(landing(tab("videos", true, grid(card(extra = native)))))
        val prepared = page.items.single()
        assertEquals(owner, prepared.item.channelId); assertEquals(owner, prepared.author.channelId)
        assertEquals("https://example.test/item-avatar", prepared.author.avatar!!.best!!.url)
        assertSame(prepared.author, prepared.displayByline.author)
        assertEquals(DisplayBylineRole.ITEM_AUTHOR, prepared.displayByline.role)
    }

    @Test fun conflictingAuthorIdentityNameAndCollaborationNeverReceiveCollectionBylines() {
        val cases = listOf(
            ",\"channelId\":\"$other\"",
            ",\"ownerText\":\"Another creator\"",
            ",\"avatar\":{\"avatarStackViewModel\":{\"avatars\":[{},{}]}}",
            ",\"avatarStackViewModel\":{\"avatars\":[{},{}]}",
            ",\"avatar\":{\"avatarStackViewModel\":{\"avatars\":\"wrong\"}}",
            ",\"avatar\":{\"decoratedAvatarViewModel\":{\"avatar\":{\"avatarStackViewModel\":{\"avatars\":[{},{}]}}}}",
            ",\"avatar\":{\"decoratedAvatarViewModel\":\"wrong\"}",
            ",\"isCollaboration\":true", ",\"collaborators\":[]")
        cases.forEach { extra ->
            val (page, wire) = opened(landing(tab("videos", true, grid(card(extra = extra)))))
            assertNotEquals(extra, DisplayBylineRole.COLLECTION_OWNER, page.items.single().displayByline.role)
            assertEquals(1, wire.requests.size)
        }
        val (sameName, _) = opened(landing(tab("videos", true, grid(card(extra = ",\"ownerText\":\"Owner\"")))))
        assertEquals(DisplayBylineRole.COLLECTION_OWNER, sameName.items.single().displayByline.role)
        assertEquals("Owner", sameName.items.single().author.name)
    }

    @Test fun ordinaryHomeShortsPostsAndGroupsDoNotInheritVideoOwnerPresentation() {
        for (kind in listOf(ChannelTabKind.HOME, ChannelTabKind.SHORTS, ChannelTabKind.LIVE, ChannelTabKind.PLAYLISTS)) {
            val (page, _) = opened(landing(tab(kind.name.lowercase(), true, grid(card()))), kind)
            assertNotEquals(DisplayBylineRole.COLLECTION_OWNER, page.items.single().displayByline.role)
        }
        val shelf = """{"shelfRenderer":{"title":"Other","content":{"horizontalListRenderer":{"items":[${card(second)}]}}}}"""
        val (page, _) = opened(landing(tab("videos", true, grid(card(), shelf))))
        assertTrue(page.groups.isNotEmpty())
        assertTrue(page.items.all { it.displayByline.role != DisplayBylineRole.COLLECTION_OWNER })
        assertTrue(page.groups.flatMap { it.items }.all { it.displayByline.role != DisplayBylineRole.COLLECTION_OWNER })
        val post = """{"backstagePostRenderer":{"postId":"Ugkx_byline_post","contentText":"Post","authorText":"Writer","authorThumbnail":{"thumbnails":[{"url":"https://example.test/post"}]}}}"""
        val (posts, _) = opened(landing(tab("posts", true, """{"sectionListRenderer":{"contents":[$post]}}""")), ChannelTabKind.POSTS)
        assertEquals(DisplayBylineRole.ITEM_AUTHOR, posts.items.single().displayByline.role)
        assertSame(posts.items.single().author, posts.items.single().post!!.author)
    }

    @Test fun duplicateUnknownNestedAndContradictorySlotsCannotBorrowPresentation() {
        val valid = landing(tab("videos", true, grid(card())))
        assertEquals(1, projection(valid).size)
        val cases = listOf(
            landing(tab("videos", true, grid(card(), card()))),
            landing(tab("videos", true, grid(card(), "{}"))),
            landing(tab("videos", true, grid(card(), """{"metadataShadow":{"videoRenderer":{"videoId":"$second"}}}"""))),
            landing(tab("videos", true, grid(card().replace("LOCKUP_CONTENT_TYPE_VIDEO", "LOCKUP_CONTENT_TYPE_PLAYLIST")))),
            landing(tab("videos", true, grid(card())), metadataOwner = other))
        cases.forEach { assertTrue(projection(it).isEmpty()) }
    }

    @Test fun selectedScopeAndSameReplyPublicValuesMustMatchExactly() {
        val body = landing(tab("videos", true, grid(card())), tab("home", false, grid(card(second))))
        assertEquals(listOf(video), projection(body).map { it.id })
        val page = browsePage(body)
        assertTrue(projection(body, page = page.copy(items = listOf(page.items.single().copy(title = "Shadow replacement")))).isEmpty())
        assertTrue(projection(body, page = page.copy(items = page.items + page.items)).isEmpty())
        assertTrue(projection(body, page = page.copy(items = List(2001) { page.items.single() })).isEmpty())
        val group = MediaGroup("Group", page.items, null)
        assertTrue(projection(body, page = page.copy(groups = List(129) { group })).isEmpty())
        assertTrue(projection(body, page = page.copy(groups = listOf(group.copy(items = List(2001) { page.items.single() })))).isEmpty())
        for (bad in listOf(
            landing(tab("home", true, grid(card())), tab("videos", false, grid(card(second)))),
            landing(tab("videos", false, grid(card()))),
            landing(tab("videos", true, grid(card())), tab("videos", true, grid(card(second)))),
            body.dropLast(1) + ",\"onResponseReceivedActions\":[]}",
            landing(tab("videos", true, """{"richGridRenderer":{"contents":[]},"onResponseReceivedActions":[]}""")))) {
            assertTrue(projection(bad).isEmpty())
        }
    }

    @Test fun explicitForeignAncestorRoutesAndForeignContinuationRolesDoNotCreateDisplayProof() {
        val good = continued(card())
        assertEquals(1, projection(good, ChannelVideoResponseRole.ISSUED_CONTINUATION).size)
        for (bad in listOf(
            good.replace("\"continuationItems\":", "\"slot\":\"RELOAD_CONTINUATION_SLOT_HEADER\",\"continuationItems\":"),
            good.replace("\"continuationItems\":", "\"targetId\":\"comments-section\",\"continuationItems\":"),
            good.replace("\"continuationItems\":", "\"targetId\":{},\"continuationItems\":"),
            good.replace("\"appendContinuationItemsAction\":", "\"commandMetadata\":{\"webCommandMetadata\":{\"apiUrl\":\"/youtubei/v1/next\"}},\"appendContinuationItemsAction\":"))) {
            assertTrue(projection(bad, ChannelVideoResponseRole.ISSUED_CONTINUATION).isEmpty())
        }
        assertTrue(projection("""{"contents":${grid(card())}}""").isEmpty())
        assertEquals(1, projection("""{"contents":${grid(card())}}""", ChannelVideoResponseRole.ISSUED_TAB).size)
    }

    @Test fun aLaterUnknownCollectionDoesNotReuseEarlierEligibleCardIds() {
        val wire = Wire { _, index -> if (index == 0) landing(tab("videos", true, grid(card(), cursor()))) else
            continued("""{"shelfRenderer":{"content":{"horizontalListRenderer":{"items":[${card()}]}}}}""") }
        val catalog = CatalogSession(AccountSession(), wire)
        val first = catalog.channel(owner, ChannelTabKind.VIDEOS).value!!
        assertEquals(DisplayBylineRole.COLLECTION_OWNER, first.items.single().displayByline.role)
        val next = catalog.next(first.next!!).value!!
        assertTrue(next.items.all { it.displayByline.role != DisplayBylineRole.COLLECTION_OWNER })
        assertTrue(next.groups.flatMap { it.items }.all { it.displayByline.role != DisplayBylineRole.COLLECTION_OWNER })
        assertEquals(2, wire.requests.size)
    }

    @Test fun legacyAndModernContinuationRolesAndDisplayBudgetsAreExplicit() {
        for (body in listOf(
            """{"continuationContents":{"richGridContinuation":{"contents":[${card()}]}}}""",
            """{"continuationContents":{"gridContinuation":{"items":[${card()}]}}}""")) {
            assertEquals(1, projection(body, ChannelVideoResponseRole.ISSUED_CONTINUATION).size)
        }
        assertTrue(projection(landing(tab("videos", true, grid(*Array(2001) { card() })))).isEmpty())
        assertTrue(projection(landing(*Array(129) { tab("videos", it == 0, grid(card())) })).isEmpty())
    }

    @Test fun immutablePreparedModelKeepsConstructorAndCopyContractsWithoutTransferringProof() {
        val click = ",\"rendererContext\":{\"commandContext\":{\"onTap\":{\"innertubeCommand\":{\"watchEndpoint\":{\"videoId\":\"$video\",\"playerParams\":\"native-watch-params\"}}}}}"
        val prepared = opened(landing(tab("videos", true, grid(card(extra = click))))).first.items.single()
        assertNotNull(prepared.item.endpoint)
        val copy = prepared.copy()
        assertSame(prepared.item, copy.item); assertSame(prepared.author, copy.author)
        assertSame(prepared.displayByline, copy.displayByline)
        val (item, author) = copy
        assertSame(prepared.item, item); assertSame(prepared.author, author)
        assertEquals(prepared, copy); assertEquals(prepared.hashCode(), copy.hashCode())
        assertEquals("PreparedMediaItem(item=$item, author=$author)", prepared.toString())
        for (changed in listOf(prepared.copy(item = prepared.item.copy()), prepared.copy(author = prepared.author.copy()),
            PreparedMediaItem(item, author))) {
            assertEquals(prepared.item, changed.item); assertEquals(prepared.author, changed.author)
            assertNotEquals(prepared, changed)
            assertNotEquals(DisplayBylineRole.COLLECTION_OWNER, changed.displayByline.role)
            assertSame(item.endpoint, changed.item.endpoint)
        }
        val raw = MediaItem("video", video, null, null, null, null)
        val unavailable = AuthorInfo(null, null, null, "canonical_channel_id_unavailable")
        assertEquals(DisplayBylineRole.UNAVAILABLE, PreparedMediaItem(raw, unavailable).displayByline.role)
        val byline = DisplayByline(unavailable, DisplayBylineRole.UNAVAILABLE)
        assertSame(unavailable, byline.component1()); assertEquals(DisplayBylineRole.UNAVAILABLE, byline.component2())
        assertEquals(byline, byline.copy()); assertEquals(byline.hashCode(), byline.copy().hashCode())
        assertEquals("DisplayByline(role=UNAVAILABLE, artworkAvailable=false)", byline.toString())
        assertArrayEquals(DisplayBylineRole.values(), arrayOf(DisplayBylineRole.valueOf("ITEM_AUTHOR"), DisplayBylineRole.valueOf("COLLECTION_OWNER"), DisplayBylineRole.valueOf("UNAVAILABLE")))
        assertEquals(DisplayBylineRole.values().toList(), DisplayBylineRole.entries)
    }

    @Test fun catalogRefreshDisplayChangesMustNotDisappearInAnAppValueDiff() {
        val initial = landing(tab("videos", true, grid(card())))
        val changedArtwork = initial.replace(avatar, "https://example.test/changed-owner-avatar")
        val unknownScope = landing(tab("videos", true, grid(card(), "{}")))
        val wire = Wire { _, index -> listOf(initial, changedArtwork, unknownScope)[index] }
        val catalog = CatalogSession(AccountSession(), wire)
        val first = catalog.channel(owner, ChannelTabKind.VIDEOS).value!!.items.single()
        val updated = catalog.channel(owner, ChannelTabKind.VIDEOS).value!!.items.single()
        val unavailable = catalog.channel(owner, ChannelTabKind.VIDEOS).value!!.items.single()
        assertEquals(first.item, updated.item); assertEquals(first.author, updated.author)
        assertNotEquals(first.displayByline, updated.displayByline)
        assertNotEquals(first, updated)
        assertEquals(first.item, unavailable.item); assertEquals(first.author, unavailable.author)
        assertEquals(DisplayBylineRole.UNAVAILABLE, unavailable.displayByline.role)
        assertNotEquals(first, unavailable)
        assertEquals(3, listOf(first, updated, unavailable).distinct().size)
        assertEquals(first, first.copy()); assertEquals(first.hashCode(), first.copy().hashCode())
        assertEquals(3, wire.requests.size)
    }
}
