package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

/** A group token is presentation data until a group-owned read route has been proven. */
class CatalogGroupContinuationBoundaryTest {
    private val card = """{"videoRenderer":{"videoId":"jNQXAC9IVRw","title":"Fixture video"}}"""
    private fun cursor(key: String) = """{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"$key"}}}}"""
    private fun shelf(key: String = "group") = """{"shelfRenderer":{"title":"Fixture group","content":{
        "horizontalListRenderer":{"items":[$card,${cursor(key)}]}}}}"""
    private fun opening(search: Boolean, slots: String): String = if (search)
        """{"contents":{"twoColumnSearchResultsRenderer":{"primaryContents":{"sectionListRenderer":{
            "contents":[{"itemSectionRenderer":{"contents":[$slots]}}]}}}}}"""
        else """{"contents":{"sectionListRenderer":{"contents":[$slots]}}}"""
    private class Wire(val reply: (HttpRequest, Int) -> HttpReply) : HttpClient {
        val requests = mutableListOf<HttpRequest>()
        override fun execute(request: HttpRequest): HttpReply { requests += request; return reply(request, requests.lastIndex) }
    }
    private fun CatalogSession.open(search: Boolean) = if (search) this.search("fixture query") else home()

    @Test fun aSingleShelfCannotIssueAnOuterHomeOrSearchCursor() {
        for (search in listOf(false, true)) {
            val wire = Wire { _, _ -> HttpReply(200, opening(search, shelf())) }
            val loaded = CatalogSession(AccountSession(), wire).open(search)
            assertTrue(loaded.error.toString(), loaded.ok)
            val page = loaded.value!!
            assertNull(page.next)
            assertNull(page.groups.single().next)
            assertEquals("continuation_route_unavailable", page.groups.single().continuationUnavailableReason)
            assertEquals(1, wire.requests.size)
        }
    }

    @Test fun multipleShelvesCannotLendAnOuterCursorEvenWhenTheirTokenBytesAgree() {
        for (search in listOf(false, true)) for (second in listOf("group", "other")) {
            val wire = Wire { _, _ -> HttpReply(200, opening(search, shelf() + "," + shelf(second))) }
            val page = CatalogSession(AccountSession(), wire).open(search).value!!
            assertNull(page.next); assertEquals(2, page.groups.size)
            assertTrue(page.groups.all { it.next == null && it.continuationUnavailableReason == "continuation_route_unavailable" })
            assertEquals(1, wire.requests.size)
        }
    }

    @Test fun aRealOuterContinuationRetainsItsOwnEndpointBesideGroups() {
        for (search in listOf(false, true)) for (outer in listOf("outer", "group")) {
            val wire = Wire { request, index ->
                assertTrue(request.url.startsWith(YOUTUBE_API + if (search) "search" else "browse"))
                if (index == 0) HttpReply(200, opening(search, shelf() + "," + cursor(outer))) else {
                    assertEquals(outer, jsonObject(request.body)!!.text("continuation"))
                    HttpReply(200, """{"onResponseReceivedActions":[{"appendContinuationItemsAction":{"continuationItems":[$card]}}]}""")
                }
            }
            val catalog = CatalogSession(AccountSession(), wire)
            val first = catalog.open(search).value!!
            assertNotNull(first.next); assertNull(first.groups.single().next)
            assertTrue(catalog.next(first.next!!).ok); assertEquals(2, wire.requests.size)
        }
    }

    @Test fun thePrimarySearchItemSectionStillIssuesItsActualPageContinuation() {
        val wire = Wire { request, index -> if (index == 0)
            HttpReply(200, opening(true, card + "," + cursor("primary"))) else {
            assertEquals("primary", jsonObject(request.body)!!.text("continuation"))
            HttpReply(200, """{"onResponseReceivedCommands":[{"appendContinuationItemsAction":{"continuationItems":[$card]}}]}""")
        } }
        val catalog = CatalogSession(AccountSession(), wire)
        val first = catalog.search("fixture query").value!!
        assertNotNull(first.next); assertTrue(catalog.next(first.next!!).ok); assertEquals(2, wire.requests.size)
    }

    @Test fun conflictingPrimarySectionCursorsDoNotIssueAnOuterCapability() {
        val section = """{"itemSectionRenderer":{"contents":[$card,${cursor("duplicate")}]}}"""
        val body = """{"contents":{"twoColumnSearchResultsRenderer":{"primaryContents":{
            "sectionListRenderer":{"contents":[$section,$section]}}}}}"""
        val wire = Wire { _, _ -> HttpReply(200, body) }
        val loaded = CatalogSession(AccountSession(), wire).search("fixture query")
        assertEquals(RequestFailure.PARSE, loaded.error?.kind)
        assertTrue(searchPage(body).items.isEmpty())
        assertEquals(1, wire.requests.size)
    }

    @Test fun anAmbiguousPrimarySectionCannotBeIgnoredToIssueItsSiblingsToken() {
        fun section(items: String) = """{"itemSectionRenderer":{"contents":[$items]}}"""
        for (second in listOf("first", "second")) for (outer in listOf(false, true)) {
            val slots = section(card + "," + cursor("first") + "," + cursor(second)) + "," + section(card + "," + cursor("sibling")) +
                if (outer) "," + cursor("outer") else ""
            val body = """{"contents":{"twoColumnSearchResultsRenderer":{"primaryContents":{
                "sectionListRenderer":{"contents":[$slots]}}}}}"""
            val wire = Wire { _, _ -> HttpReply(200, body) }
            val loaded = CatalogSession(AccountSession(), wire).search("fixture query")
            if (outer) {
                assertTrue(loaded.error.toString(), loaded.ok)
                assertNotNull(loaded.value!!.next)
            } else {
                assertEquals(RequestFailure.PARSE, loaded.error?.kind)
                assertTrue(searchPage(body).items.isEmpty())
            }
            assertEquals(1, wire.requests.size)
        }
    }

    @Test fun anOwnedOuterCursorPreservesIdentityAndStillRejectsAChangedGenerationBeforeIo() {
        val context = RequestContext(accountId = "fixture-owner", webSession = WebSession("SAPISID=fixture", 1),
            onBehalfOfUser = "fixture-page", visitor = "fixture-visitor")
        val account = AccountSession(context)
        val wire = Wire { request, _ ->
            assertEquals("1", request.headers["X-Goog-AuthUser"])
            assertEquals("fixture-page", request.headers["X-Goog-PageId"])
            assertEquals(context.webSession!!.cookies, request.headers["Cookie"])
            HttpReply(200, opening(false, shelf() + "," + cursor("outer")))
        }
        val catalog = CatalogSession(account, wire); val page = catalog.home().value!!
        assertNotNull(page.next); assertNull(page.groups.single().next)
        account.clear()
        assertEquals(RequestFailure.CANCELLED, catalog.next(page.next!!).error?.kind)
        assertEquals(1, wire.requests.size)
    }
}
