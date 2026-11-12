package com.liskovsoft.youtubeapi.videoinfo.V2
import org.junit.Assert.*
import org.junit.Test
class SearchPageTest {
    @Test fun keepsMixedResultsAndTheContinuation() {
        val page = searchPage("""{"contents":[{"videoRenderer":{"videoId":"v","title":{"runs":[{"text":"Video"}]}}},{"channelRenderer":{"channelId":"UC1","title":{"simpleText":"Channel"}}},{"playlistRenderer":{"playlistId":"PL1","title":{"simpleText":"List"}}},{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"next"}}}}]}""")
        assertEquals(listOf("video","channel","playlist"), page.items.map { it.kind }); assertEquals("next", page.nextKey)
        val q = searchQuery(null, continuation = "a\"b")
        assertEquals("a\"b", jsonObject(q.body)!!.text("continuation")); assertFalse(q.body.contains("\"query\""))
    }
    @Test fun distinguishesAnEmptyPageFromFailure() {
        val empty = object : HttpClient { override fun execute(request: HttpRequest) = HttpReply(200, """{"contents":{"twoColumnSearchResultsRenderer":{"primaryContents":{"sectionListRenderer":{"contents":[]}}}}}""") }
        assertTrue(loadSearch("q", http = empty).value!!.items.isEmpty())
        assertEquals(RequestFailure.INVALID, loadSearch("", http = empty).error!!.kind)
    }
    @Test fun loadedSearchKeepsTheStringParserPageIncludingGroupsActionsAndFilters() {
        val body = """{"contents":[{"itemSectionRenderer":{"title":{"simpleText":"Mixed"},"contents":[
            {"videoRenderer":{"videoId":"video","title":{"simpleText":"Unicode \u4e2d\u6587\nSecond line"},
                "navigationEndpoint":{"watchEndpoint":{"videoId":"video","startTimeSeconds":12}},
                "menu":{"menuRenderer":{"items":[{"menuServiceItemRenderer":{"text":{"simpleText":"Action"},
                    "serviceEndpoint":{"feedbackEndpoint":{"feedbackToken":"opaque","uiActions":{"hideUnhideUis":{}}}}}}]}}}},
            {"playlistRenderer":{"playlistId":"PL1","title":{"simpleText":"Playlist"}}},
            {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"next"}}}}]}},
            {"searchFilterGroupRenderer":{"title":{"simpleText":"Upload date"},"contents":[{"searchFilterRenderer":{
                "label":{"simpleText":"This year"},"status":"FILTER_STATUS_SELECTED",
                "navigationEndpoint":{"searchEndpoint":{"params":"cumulative"}}}}]}},
            {"messageRenderer":{"text":{"simpleText":"Message"}}}]}"""
        val extracted = jsonObject(body)!!
        val slots = extracted.arr("contents")
        val filters = slots.mapNotNull { (it as? Json.Obj)?.obj("searchFilterGroupRenderer") }.single()
        val wireBody = searchPrimaryWithFilterFixture(Json.Obj(extracted.fields + mapOf(
            "contents" to Json.Arr(slots.filter { (it as? Json.Obj)?.obj("searchFilterGroupRenderer") == null }),
            "searchFilterGroupRenderer" to filters)).encode())
        var requests = 0
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                requests++
                return HttpReply(200, wireBody)
            }
        }
        val parsed = searchPage(wireBody)
        val loaded = loadSearch("q", http = http).value!!
        assertEquals(parsed, loaded)
        assertEquals(1, requests)
        assertEquals("Unicode 中文\nSecond line", loaded.items.first().title)
        assertEquals(listOf("Mixed"), loaded.groups.map { it.title })
        assertTrue(loaded.items.first().actions.isNotEmpty())
        assertEquals("next", loaded.nextKey)
        assertEquals(listOf("Message"), loaded.messages)
        assertTrue(loaded.filters.single().selected)
        assertEquals("cumulative", loaded.filters.single().params)
    }
}
