package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class MenuReadTest {
    private val endpoint = MenuEndpoint("video-menu", "opaque%3D+/=")
    private fun panel(items: String) = """{"content":{"engagementPanelSectionListRenderer":{"content":{"listViewModel":{"listItems":[$items]}}}}}"""
    private fun row(token: String, ui: String) = """{"listItemViewModel":{"rendererContext":{"commandContext":{"onTap":{"innertubeCommand":{"feedbackEndpoint":{"feedbackToken":"$token","uiActions":{$ui}}}}}}}}"""

    @Test fun loadsTheCardsOwnPanelWithTheSameIdentity() {
        val requests = mutableListOf<HttpRequest>()
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                requests += request
                return HttpReply(200, panel(listOf(row("video", "\"hideEnclosingContainer\":true"),
                    row("channel", "\"hideEnclosingContainerUis\":{}")).joinToString(",")))
            }
        }
        val actions = loadMenu(endpoint, RequestContext(accountId = "account", accessToken = "access",
            visitor = "visitor", language = "fr", onBehalfOfUser = "channel"), http).value!!
        assertEquals("video", actions.single { it.kind == MenuActionKind.NotInterested }.kindToken)
        assertEquals("channel", actions.single { it.kind == MenuActionKind.DontRecommendChannel }.kindToken)
        val request = requests.single()
        assertTrue(request.url.startsWith(YOUTUBE_API + "get_panel"))
        assertEquals("Bearer access", request.headers["Authorization"])
        assertEquals("visitor", request.headers["X-Goog-Visitor-Id"])
        val body = jsonObject(request.body)!!
        assertEquals(endpoint.panelId, body.text("panelId"))
        assertEquals(endpoint.params, body.text("params"))
        assertEquals("TVHTML5", body.path("context", "client")!!.text("clientName"))
        assertEquals("fr", body.path("context", "client")!!.text("hl"))
        assertEquals("channel", body.path("context", "user")!!.text("onBehalfOfUser"))
        assertEquals("MenuEndpoint", endpoint.toString())
    }

    @Test fun distinguishesAnEmptyMenuFromAnUnknownResponse() {
        fun load(body: String, status: Int = 200) = loadMenu(endpoint, http = object : HttpClient {
            override fun execute(request: HttpRequest) = HttpReply(status, body)
        })
        assertEquals(emptyList<PageAction>(), load(panel("")).value)
        for (body in listOf("{}", "not-json", panel("").replace("\"listItems\":[]", "\"listItems\":{}")))
            assertFalse(body, load(body).ok)
        assertFalse(load("{}", 403).ok)
        assertFalse(load("""{"error":{"code":400,"status":"INVALID_ARGUMENT"}}""").ok)
    }

    @Test fun invalidEndpointsNeverMakeARequest() {
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply = error("unexpected request")
        }
        for (invalid in listOf(endpoint.copy(panelId = ""), endpoint.copy(params = " "),
            endpoint.copy(panelId = "x".repeat(257)), endpoint.copy(params = "x".repeat(32769))))
            assertEquals(RequestFailure.INVALID, loadMenu(invalid, http = http).error?.kind)
    }

    @Test fun contentParsingDoesNotBorrowAnotherCardsMenu() {
        fun card(id: String, endpoint: String = "") = """{"videoRenderer":{"videoId":"$id"$endpoint}}"""
        val command = """, "onLongPressCommand":{"showEngagementPanelEndpoint":{"identifier":{"tag":"own-menu"},"globalConfiguration":{"params":"own-params"}}}"""
        val items = contentItems(jsonObject("""{"contents":[${card("first", command)},${card("second")}]}""")!!)
        assertEquals(MenuEndpoint("own-menu", "own-params"), items[0].menuEndpoint)
        assertNull(items[1].menuEndpoint)
    }
}
