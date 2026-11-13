package com.liskovsoft.youtubeapi.videoinfo.V2

import java.net.URI

import org.junit.Assert.*
import org.junit.Test

class CatalogSessionTest {
    private val video = "jNQXAC9IVRw"
    private val card = """{"videoRenderer":{"videoId":"jNQXAC9IVRw","title":{"simpleText":"Lesson"},"longBylineText":{"runs":[{"text":"Teacher","navigationEndpoint":{"browseEndpoint":{"browseId":"UC_x5XG1OV2P6uZZ5FSM9Ttw"}}}]}}}"""
    private fun next(key: String) = """{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"$key"}}}}"""
    private fun browse(key: String? = null) = """{"contents":{"richGridRenderer":{"contents":[$card${key?.let { "," + next(it) }.orEmpty()}]}}}"""
    private fun continuation() = """{"onResponseReceivedActions":[{"appendContinuationItemsAction":{"continuationItems":[$card]}}]}"""
    private class Wire(private val response: (HttpRequest, Int) -> HttpReply) : HttpClient {
        val requests = mutableListOf<HttpRequest>()
        override fun execute(request: HttpRequest): HttpReply { requests += request; return response(request, requests.lastIndex) }
    }
    @Test fun homeUsesNativeWebAndContinuationKeepsTheOpeningRoute() {
        val http = Wire { request, _ ->
            val body = jsonObject(request.body)!!
            assertEquals("WEB", body.path("context", "client")?.text("clientName"))
            if (body.text("continuation") == null) { assertEquals("FEwhat_to_watch", body.text("browseId")); HttpReply(200, browse("native-next")) }
            else { assertEquals("native-next", body.text("continuation")); assertNull(body.text("browseId")); HttpReply(200, continuation()) }
        }
        val catalog = CatalogSession(AccountSession(), http, 0)
        val first = catalog.home().value!!
        assertFalse(first.empty); assertEquals(video, first.items.single().item.id)
        assertEquals(0, first.preparationRequestCount); assertTrue(first.filters.isEmpty()); assertTrue(first.messages.isEmpty())
        assertEquals("CatalogCursor(generation=0)", first.next.toString())
        assertEquals(video, catalog.next(first.next!!).value!!.items.single().item.id)
        assertNull(catalog.home(HomeSection.HOME).error)
        catalog.clearPreparedData()
    }
    @Test fun nativeEmptyHomePreservesServerMessageInsteadOfMixingOtherFeeds() {
        val http = Wire { _, _ -> HttpReply(200, """{"contents":{"richGridRenderer":{"contents":[{"messageRenderer":{"text":{"simpleText":"Try searching to get started"}}}]}}}""") }
        val page = CatalogSession(AccountSession(), http).home().value!!
        assertTrue(page.empty); assertEquals(listOf("Try searching to get started"), page.messages)
        assertNull(page.next); assertEquals(1, http.requests.size)
    }
    @Test fun topicFeedsKeepExplicitTvProfileAndLibraryPreservesAuthenticationRequirement() {
        val http = Wire { request, _ ->
            val body = jsonObject(request.body)!!
            assertEquals("TVHTML5", body.path("context", "client")?.text("clientName"))
            assertEquals("FEtopics_music", body.text("browseId")); HttpReply(200, browse())
        }
        val catalog = CatalogSession(AccountSession(), http)
        assertTrue(catalog.home(HomeSection.MUSIC).ok)
        assertEquals(RequestFailure.AUTH, catalog.library(LibrarySection.HISTORY).error?.kind)
        assertEquals(1, http.requests.size)
    }
    @Test fun filtersKeepCanonicalServerParametersAndCannotCrossCatalogsOrGenerations() {
        val root = searchOwnedFilterHeaderFixture("""{"contents":{"twoColumnSearchResultsRenderer":{"primaryContents":{"sectionListRenderer":{"contents":[$card]}}}},"searchFilterGroupRenderer":{"title":{"simpleText":"类型"},"filters":[{"searchFilterRenderer":{"label":{"simpleText":"全部"},"status":"FILTER_STATUS_SELECTED"}},{"searchFilterRenderer":{"label":{"simpleText":"课程"},"navigationEndpoint":{"searchEndpoint":{"params":"opaque-canonical-combined"}}}}]}}""")
        val http = Wire { request, index ->
            val body = jsonObject(request.body)!!
            assertEquals("lesson", body.text("query"))
            assertEquals(if (index == 0) null else "opaque-canonical-combined", body.text("params"))
            HttpReply(200, root)
        }
        val account = AccountSession(); val catalog = CatalogSession(account, http)
        val page = catalog.search("lesson").value!!
        val selected = page.filters[0]; val option = page.filters[1]
        assertTrue(selected.selected); assertFalse(selected.available)
        assertEquals("课程", option.title); assertEquals("类型", option.group)
        assertFalse(option.selected); assertTrue(option.available)
        assertFalse(option.toString().contains("opaque"))
        assertEquals(RequestFailure.INVALID, catalog.filter(CatalogFilter("fake", null, false, true, catalog, 0, "lesson", "handmade")).error?.kind)
        assertEquals(RequestFailure.UNSUPPORTED, catalog.filter(selected).error?.kind)
        assertTrue(catalog.filter(option).ok)
        assertEquals(RequestFailure.INVALID, CatalogSession(account, http).filter(option).error?.kind)
        account.clear()
        assertEquals(RequestFailure.CANCELLED, catalog.filter(option).error?.kind)
        assertEquals(2, http.requests.size)
    }
    @Test fun cursorRejectsForeignAndStaleAndFencesInFlightResponses() {
        val account = AccountSession()
        val http = Wire { _, index -> if (index == 0) HttpReply(200, browse("token")) else { account.clear(); HttpReply(200, continuation()) } }
        val catalog = CatalogSession(account, http)
        val cursor = catalog.home().value!!.next!!
        assertEquals(RequestFailure.INVALID, catalog.next(CatalogCursor(catalog, 0, CatalogRoute.Home, "made-up-token")).error?.kind)
        assertEquals(RequestFailure.INVALID, CatalogSession(account, http).next(cursor).error?.kind)
        assertEquals(RequestFailure.CANCELLED, catalog.next(cursor).error?.kind)
        assertEquals(RequestFailure.CANCELLED, catalog.next(cursor).error?.kind)
        assertEquals(2, http.requests.size)
    }
    @Test fun visitorInstallationUpdatesCredentialsButOnlyIdentityChangesInvalidateCapabilities() {
        val account = AccountSession(RequestContext(language = "zh", region = "TW"))
        val first = account.installVisitor(VisitorSession("VISITOR_INFO1_LIVE=one; YSC=first", "visitor-data-1"))
        assertFalse(first.signedIn); assertEquals(1L, first.generation)
        assertEquals("zh", first.language); assertEquals("TW", first.region)
        val same = account.installVisitor(VisitorSession("VISITOR_INFO1_LIVE=one; YSC=renewed", "visitor-data-2"))
        assertEquals(first.generation, same.generation)
        assertEquals("visitor-data-2", same.visitor)
        val bound = account.execute { ReadResult("opened") }.value!!.cursor("next")!!
        val changed = account.installVisitor(VisitorSession("VISITOR_INFO1_LIVE=other", "visitor-data-3"))
        assertEquals(2L, changed.generation); assertTrue(account.accounts().isEmpty())
        assertEquals(RequestFailure.CANCELLED, account.execute(bound) { _, _ -> ReadResult("wrong identity") }.error?.kind)
        val signed = AccountSession(RequestContext(webSession = WebSession("SAPISID=fixture"), accountId = "old", onBehalfOfUser = "old-brand"))
        val loggedOut = signed.installVisitor(VisitorSession("", "new-guest"))
        assertFalse(loggedOut.signedIn); assertNull(loggedOut.accountId); assertNull(loggedOut.onBehalfOfUser)
        assertEquals(1L, loggedOut.generation)
    }
    @Test fun relatedCardsAndGroupsArePreparedWithoutHiddenNetworkCalls() {
        val http = Wire { request, _ ->
            assertEquals("/youtubei/v1/next", URI(request.url).path)
            HttpReply(200, """{"contents":{"twoColumnWatchNextResults":{"secondaryResults":{"secondaryResults":{"results":[$card]}}}}}""")
        }
        val catalog = CatalogSession(AccountSession(), http)
        val page = catalog.related(video).value!!
        assertEquals(video, page.items.single().item.id)
        assertEquals("UC_x5XG1OV2P6uZZ5FSM9Ttw", page.items.single().author.channelId)
        assertEquals("Teacher", page.items.single().author.name)
        assertEquals(1, http.requests.size)
        assertEquals(RequestFailure.INVALID, catalog.related("invalid id").error?.kind)
        assertEquals(RequestFailure.INVALID, catalog.search(" ").error?.kind)
        val grouped = Wire { _, _ -> HttpReply(200, """{"contents":{"sectionListRenderer":{"contents":[{"shelfRenderer":{"title":{"simpleText":"Lessons"},"content":{"horizontalListRenderer":{"items":[$card]}}}}]}}}""") }
        val group = CatalogSession(AccountSession(), grouped).home().value!!.groups.single()
        assertEquals("Lessons", group.title); assertEquals(video, group.items.single().item.id)
    }
    @Test fun invalidConfigurationAndUnknownResponsesRemainErrors() {
        CatalogSession(AccountSession()).clearPreparedData()
        for (budget in listOf(-1, 33)) assertThrows(IllegalArgumentException::class.java) { CatalogSession(AccountSession(), Wire { _, _ -> HttpReply(200, "{}") }, budget) }
        assertEquals(RequestFailure.PARSE, CatalogSession(AccountSession(), Wire { _, _ -> HttpReply(200, "{\"futurePage\":{}}") }).home().error?.kind)
    }
    @Test fun artworkValueEqualityAndDiagnosticsKeepImageAddressesOutOfLogs() {
        val variant = ArtworkVariant("https://yt3.ggpht.com/private-image-reference", 88, 88)
        val artwork = Artwork(listOf(variant))
        assertEquals(Artwork(listOf(variant)), artwork)
        assertEquals(Artwork(listOf(variant)).hashCode(), artwork.hashCode())
        assertEquals("ArtworkVariant(width=88, height=88)", variant.toString())
        assertFalse(variant.toString().contains(variant.url))
    }
}
