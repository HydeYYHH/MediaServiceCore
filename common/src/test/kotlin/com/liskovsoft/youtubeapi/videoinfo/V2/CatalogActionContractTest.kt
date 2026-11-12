package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class CatalogActionContractTest {
    private val button = """{"buttonRenderer":{"text":{"simpleText":"Server terminal control"},"navigationEndpoint":{
        "commandMetadata":{"webCommandMetadata":{"apiUrl":"/youtubei/v1/browse"}},
        "browseEndpoint":{"browseId":"FEserver-issued","params":"opaque-server-route"}}}}"""
    private val card = """{"videoRenderer":{"videoId":"fixture-video"}}"""

    @Test fun browseTerminalControlsSurvivePreparationWithoutBeingExecuted() {
        val body = """{"contents":{"sectionListRenderer":{"contents":[$button]}}}"""
        var calls = 0
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply { calls++; return HttpReply(200, body) }
        }
        val page = CatalogSession(AccountSession(), http).home().value!!
        assertTrue(page.empty); assertEquals(1, calls)
        assertEquals(browsePage(body).actions, page.actions)
        val action = page.actions.single()
        assertEquals("Server terminal control", action.title)
        assertEquals("browseEndpoint", action.endpoint.name)
        assertEquals("/youtubei/v1/browse", action.endpoint.apiUrl)
        assertEquals("opaque-server-route", jsonObject(action.endpoint.payload)!!.text("params"))
        assertEquals(0, page.preparationRequestCount)
    }

    @Test fun searchKeepsOnlyOrganicPageActionsAndTheirOriginalPayload() {
        val ad = """{"searchPyvRenderer":{"content":$button}}"""
        val body = """{"contents":{"twoColumnSearchResultsRenderer":{"primaryContents":{"sectionListRenderer":{
            "contents":[{"itemSectionRenderer":{"contents":[$card,$ad]}}]}},"header":$button}}}"""
        var calls = 0
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply { calls++; return HttpReply(200, body) }
        }
        val page = CatalogSession(AccountSession(), http).search("fixture").value!!
        assertEquals(1, calls); assertEquals("fixture-video", page.items.single().item.id)
        assertEquals(searchPage(body).actions, page.actions)
        assertEquals(1, page.actions.size)
        assertEquals("FEserver-issued", jsonObject(page.actions.single().endpoint.payload)!!.text("browseId"))
        assertEquals("opaque-server-route", jsonObject(page.actions.single().endpoint.payload)!!.text("params"))
    }
}
