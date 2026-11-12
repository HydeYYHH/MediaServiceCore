package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

/** A channel screen consumes capabilities rather than translated titles or protocol parameters. */
class ChannelSessionTest {
    private val id = "UC_x5XG1OV2P6uZZ5FSM9Ttw"
    private val other = "UCBR8-60-B28hp2BmDPdntcQ"
    private class Transport(private val reply: (HttpRequest, Int) -> HttpReply) : HttpClient {
        val requests = mutableListOf<HttpRequest>()
        override fun execute(request: HttpRequest): HttpReply {
            requests += request
            return reply(request, requests.lastIndex)
        }
    }
    private fun tab(kind: String?, selected: Boolean = false, url: String? = null, params: String? = "route-$kind",
        title: String = "误导的标题", endpoint: String = "endpoint", browseId: String = id) =
        """{"tabRenderer":{"title":"$title","selected":$selected,${kind?.let { "\"tabIdentifier\":\"$it\"," }.orEmpty()}"$endpoint":{"commandMetadata":{"webCommandMetadata":{"url":"${url ?: "/channel/$id/${if (kind == "home") "featured" else kind}"}"}},"browseEndpoint":{"browseId":"$browseId"${params?.let { ",\"params\":\"$it\"" }.orEmpty()}}}}}"""
    private fun landing(tabs: List<String>, channelId: String = id) =
        """{"metadata":{"channelMetadataRenderer":{"externalId":"$channelId","title":"Developer lessons"}},"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[${tabs.joinToString(",")}]}}}"""
    private fun page(video: String, next: String? = null, continued: Boolean = true): String {
        val cards = """{"videoRenderer":{"videoId":"$video","title":{"simpleText":"Lesson $video"}}}${next?.let { "," + continuation(it) }.orEmpty()}"""
        return if (continued) """{"onResponseReceivedActions":[{"appendContinuationItemsAction":{"continuationItems":[$cards]}}]}"""
            else """{"contents":{"richGridRenderer":{"contents":[$cards]}}}"""
    }
    private fun continuation(key: String) = """{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"$key"}}}}"""
    private fun postPage(post: String, next: String? = null) =
        """{"contents":[{"backstagePostRenderer":{"postId":"$post","contentText":{"simpleText":"Course announcement"}}}${next?.let { "," + continuation(it) }.orEmpty()}]}"""
    private fun content(tab: String, content: String) = tab.dropLast(2) + ",\"content\":" + content + "}}"

    @Test fun semanticTabsUseProtocolIdentifiersAndTrustedUrlsWithoutReadingTitles() {
        val tabs = browsePage(landing(listOf(tab("home", true), tab("videos"), tab("shorts"), tab("streams"), tab("playlists"),
            tab(null, url = "/@lessons/community", endpoint = "serviceEndpoint"), tab(null, url = "/@lessons/mystery", title = "Videos")))).tabs
        assertEquals(listOf(ChannelTabKind.HOME, ChannelTabKind.VIDEOS, ChannelTabKind.SHORTS, ChannelTabKind.LIVE,
            ChannelTabKind.PLAYLISTS, ChannelTabKind.POSTS, ChannelTabKind.UNKNOWN), tabs.map { it.kind })
        assertTrue(tabs.first().selected); assertEquals("route-videos", tabs[1].params)
        assertEquals("误导的标题", tabs[2].title); assertEquals(id, tabs[5].browseId)
        for (url in listOf("https://attacker.example/@x/videos", "//attacker.example/@x/videos", "/@x/videos/deeper", "https://youtube.com.evil/@x/videos", "/@x/videos#fragment"))
            assertEquals(url, ChannelTabKind.UNKNOWN, channelTabKind(null, url))
        assertEquals(ChannelTabKind.UNKNOWN, channelTabKind("videos", "/@x/shorts"))
        assertEquals(ChannelTabKind.VIDEOS, channelTabKind(null, "https://www.youtube.com/@x/videos"))
        assertEquals(ChannelTabKind.HOME, channelTabKind(null, "/channel/$id"))
        assertEquals(ChannelTabKind.POSTS, channelTabKind("posts", null))
        val tile = browsePage("""{"tileRenderer":{"style":"TILE_STYLE_YTLR_WORMHOLE_RECTANGULAR","tileMetadataRenderer":{"title":{"simpleText":"课程"}},"onSelectCommand":{"commandMetadata":{"webCommandMetadata":{"url":"/@x/playlists"}},"browseEndpoint":{"browseId":"$id","params":"server-list"}}}}""").tabs.single()
        assertEquals(ChannelTabKind.PLAYLISTS, tile.kind); assertEquals("server-list", tile.params)
    }

    @Test fun openingReusesSelectedPageAndTargetNavigationRetainsServerParametersAndKind() {
        val initial = landing(listOf(tab("home", true, params = null), tab("videos"), tab("shorts"), tab("streams"), tab("playlists")))
        val http = Transport { request, index ->
            if (index == 0) HttpReply(200, initial) else {
                val body = jsonObject(request.body)!!
                if (index == 1) { assertEquals(id, body.text("browseId")); assertEquals("route-videos", body.text("params")); HttpReply(200, page("first", "private-next", false)) }
                else { assertEquals("private-next", body.text("continuation")); assertNull(body.text("browseId")); HttpReply(200, page("second")) }
            }
        }
        val handle = ChannelSession(AccountSession(), http).open(id).value!!
        assertEquals("Developer lessons", handle.channel.title)
        assertEquals(listOf(ChannelTabKind.HOME, ChannelTabKind.VIDEOS, ChannelTabKind.SHORTS, ChannelTabKind.LIVE, ChannelTabKind.PLAYLISTS), handle.availableTabs)
        val home = handle.openTab(ChannelTabKind.HOME).value!!
        assertEquals(ChannelTabKind.HOME, home.kind); assertSame(handle.channel.page, home.page); assertNull(home.next)
        assertEquals(1, http.requests.size)
        val first = handle.openTab(ChannelTabKind.VIDEOS).value!!
        assertEquals("first", first.page.items.single().id); assertEquals(ChannelTabKind.VIDEOS, first.kind)
        assertSame(first.page, handle.openTab(ChannelTabKind.VIDEOS).value!!.page); assertEquals(2, http.requests.size)
        val cursor = first.next!!; assertEquals(ChannelTabKind.VIDEOS, cursor.kind)
        assertEquals("ChannelTabCursor(kind=VIDEOS)", cursor.toString()); assertFalse(handle.toString().contains("route-"))
        val last = handle.next(cursor).value!!
        assertEquals("second", last.page.items.single().id); assertEquals(ChannelTabKind.VIDEOS, last.kind); assertNull(last.next)
        assertEquals(3, http.requests.size)
        assertEquals(RequestFailure.UNSUPPORTED, handle.openTab(ChannelTabKind.UNKNOWN).error?.kind)
        assertEquals("channel_posts_requires_typed_reader", handle.openTab(ChannelTabKind.POSTS).error?.code)
        assertEquals("channel_posts_unavailable_or_ambiguous", handle.openPosts().error?.code)
    }

    @Test fun typedPostReaderKeepsOwnershipAndDoesNotLeakContinuationTokens() {
        val http = Transport { request, index -> when (index) {
            0, 3 -> HttpReply(200, landing(listOf(tab("home", true), tab("posts", endpoint = "serviceEndpoint"))))
            1 -> { assertEquals("route-posts", jsonObject(request.body)!!.text("params")); HttpReply(200, postPage("announcement-first", "private-post-next")) }
            else -> { assertEquals("private-post-next", jsonObject(request.body)!!.text("continuation")); HttpReply(200, postPage("announcement-last")) }
        } }
        val session = AccountSession()
        val service = ChannelSession(session, http)
        val handle = service.open(id).value!!
        val first = handle.openPosts().value!!
        assertSame(first.page, handle.openPosts().value!!.page); assertEquals(2, http.requests.size)
        assertEquals("announcement-first", first.page.posts.single().postId)
        assertEquals("Course announcement", first.page.posts.single().text)
        val cursor = first.next!!; assertEquals("ChannelPostsCursor", cursor.toString())
        val last = handle.nextPosts(cursor).value!!
        assertEquals("announcement-last", last.page.posts.single().postId); assertNull(last.next)
        val foreign = service.open(id).value!!
        assertEquals("foreign_channel_posts_cursor", foreign.nextPosts(cursor).error?.code)
        assertEquals(4, http.requests.size)
        session.clear()
        assertEquals("session_changed", handle.nextPosts(cursor).error?.code)
        assertEquals(4, http.requests.size)
    }

    @Test fun selectedVideosAndPostsAreAlreadyOpenAndNeedNoSecondBrowse() {
        val videos = Transport { _, _ -> HttpReply(200, landing(listOf(tab("videos", true, params = null)))) }
        val videoHandle = ChannelSession(AccountSession(), videos).open(id).value!!
        assertSame(videoHandle.channel.page, videoHandle.openTab(ChannelTabKind.VIDEOS).value!!.page)
        assertEquals(1, videos.requests.size)
        val postTab = tab("community", true, params = null).dropLast(2) + ",\"content\":" + postPage("selected-announcement", "selected-next") + "}}"
        val posts = Transport { _, _ -> HttpReply(200, landing(listOf(postTab))) }
        val postHandle = ChannelSession(AccountSession(), posts).open(id).value!!
        val opened = postHandle.openPosts().value!!
        assertEquals("selected-announcement", opened.page.posts.single().postId); assertNotNull(opened.next)
        assertEquals(1, posts.requests.size)
    }

    @Test fun aBareCanonicalHomeEndpointRemainsNavigableWhenAnotherTabWasSelected() {
        val http = Transport { request, index -> if (index == 0)
            HttpReply(200, landing(listOf(tab("shorts", true), tab("home", params = null), tab("videos", params = null))))
        else {
            val body = jsonObject(request.body)!!
            assertEquals(id, body.text("browseId")); assertNull(body.text("params")); assertNull(body.text("continuation"))
            HttpReply(200, page("home-video", continued = false))
        } }
        val handle = ChannelSession(AccountSession(), http).open(id).value!!
        assertEquals("home-video", handle.openTab(ChannelTabKind.HOME).value!!.page.items.single().id)
        assertEquals("channel_tab_has_no_navigation_capability", handle.openTab(ChannelTabKind.VIDEOS).error?.code)
        assertEquals(2, http.requests.size)
        assertFalse(channelTabHasNavigation(BrowseTab("Home", null, null, false, ChannelTabKind.HOME), id))
    }

    @Test fun handleResolutionRejectsOtherResourcesAndDoesNotGuessAnUnavailableRoute() {
        val http = Transport { request, index -> if (index == 0) {
            assertTrue(request.url.endsWith("navigation/resolve_url")); assertEquals("https://www.youtube.com/@lessons", jsonObject(request.body)!!.text("url"))
            HttpReply(200, """{"endpoint":{"browseEndpoint":{"browseId":"$id"}}}""")
        } else HttpReply(200, landing(listOf(tab("home", true), tab("videos", params = null)))) }
        val handle = ChannelSession(AccountSession(), http).open("@lessons").value!!
        assertEquals(id, handle.channel.id)
        assertEquals("channel_tab_has_no_navigation_capability", handle.openTab(ChannelTabKind.VIDEOS).error?.code)
        assertEquals("channel_tab_unavailable_or_ambiguous", handle.openTab(ChannelTabKind.SHORTS).error?.code)
        assertEquals(2, http.requests.size)
        val invalid = Transport { _, _ -> fail("invalid identity cannot send a request"); HttpReply(500, "") }
        for (input in listOf("", "UC", "https://youtube.com/@x", "@x/path", "@x?query", "not-a-channel"))
            assertEquals("invalid_channel_identity", ChannelSession(AccountSession(), invalid).open(input).error?.code)
        val resolvedPlaylist = Transport { _, _ -> HttpReply(200, """{"browseEndpoint":{"browseId":"VLplaylist"}}""") }
        assertEquals("resolved_identity_is_not_a_channel", ChannelSession(AccountSession(), resolvedPlaylist).open("@x").error?.code)
        val mismatch = Transport { _, _ -> HttpReply(200, landing(listOf(tab("home", true)), other)) }
        assertEquals("channel_identity_mismatch", ChannelSession(AccountSession(), mismatch).open(id).error?.code)
        val failed = Transport { _, _ -> HttpReply(503, "") }
        assertEquals(RequestFailure.HTTP, ChannelSession(AccountSession(), failed).open(id).error?.kind)
    }

    @Test fun foreignStaleAndInFlightResponsesCannotCrossAccountBoundaries() {
        val session = AccountSession()
        val http = Transport { _, index -> if (index < 2) HttpReply(200, landing(listOf(content(tab("videos", true), page("initial", "owned-secret", false))))) else {
            session.clear(); HttpReply(200, page("old-account-result"))
        } }
        val service = ChannelSession(session, http)
        val one = service.open(id).value!!; val two = service.open(id).value!!
        val cursor = one.openTab(ChannelTabKind.VIDEOS).value!!.next!!
        assertEquals("foreign_channel_cursor", two.next(cursor).error?.code); assertEquals(2, http.requests.size)
        val inFlight = one.next(cursor)
        assertNull(inFlight.value); assertEquals("session_changed", inFlight.error?.code)
        assertEquals("session_changed", one.openTab(ChannelTabKind.VIDEOS).error?.code)
        assertEquals("session_changed", one.openPosts().error?.code)
        assertEquals("foreign_channel_posts_cursor", one.nextPosts(ChannelPostsCursor(one, "post-secret")).error?.code)
        assertEquals(3, http.requests.size)
    }

    @Test fun ambiguousUnknownAndMissingPostRoutesAreExplicitlyUnsupported() {
        val http = Transport { _, _ -> HttpReply(200, landing(listOf(tab("videos"), tab("videos"), tab("posts", params = null), tab(null, url = "/@x/unrecognized", title = "Live")))) }
        val handle = ChannelSession(AccountSession(), http).open(id).value!!
        assertTrue(handle.availableTabs.contains(ChannelTabKind.UNKNOWN))
        assertEquals("channel_tab_unavailable_or_ambiguous", handle.openTab(ChannelTabKind.VIDEOS).error?.code)
        assertEquals("channel_tab_has_no_navigation_capability", handle.openPosts().error?.code)
        assertEquals(1, http.requests.size)
    }

    @Test fun allNonPostSurfacesKeepTheirOwnServerCapabilityAndPublicMetadataCannotReplaceRoutes() {
        val targetKinds = listOf("videos" to ChannelTabKind.VIDEOS, "shorts" to ChannelTabKind.SHORTS,
            "streams" to ChannelTabKind.LIVE, "playlists" to ChannelTabKind.PLAYLISTS)
        val http = Transport { request, index -> if (index == 0)
            HttpReply(200, landing(listOf(tab("home", true)) + targetKinds.map { tab(it.first) }))
        else {
            val wanted = targetKinds[index - 1]
            assertEquals("route-${wanted.first}", jsonObject(request.body)!!.text("params"))
            HttpReply(200, page(wanted.first, continued = false))
        } }
        val handle = ChannelSession(AccountSession(), http).open(id).value!!
        (handle.channel.page.tabs as MutableList<BrowseTab>).clear()
        for ((name, kind) in targetKinds) {
            val result = handle.openTab(kind)
            assertTrue(result.error.toString(), result.ok)
            assertEquals(kind, result.value!!.kind); assertEquals(name, result.value!!.page.items.single().id)
        }
        assertEquals(5, http.requests.size)
        try { (handle.availableTabs as MutableList<ChannelTabKind>).clear(); fail("capability inventory is read-only") }
        catch (_: UnsupportedOperationException) { }
    }

    @Test fun accountChangeDuringHandleResolutionPreventsASecondRequest() {
        val session = AccountSession()
        val http = Transport { _, _ ->
            session.clear()
            HttpReply(200, """{"browseEndpoint":{"browseId":"$id"}}""")
        }
        val result = ChannelSession(session, http).open("@lessons")
        assertEquals("session_changed", result.error?.code); assertNull(result.value)
        assertEquals(1, http.requests.size)
    }
}
