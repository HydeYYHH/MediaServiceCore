package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class KidsBrowseTest {
    // Minimal synthetic counterpart of the observed kidsHomeScreen/anchoredSection schema.
    // 31 compact videos and five compact playlists mirror counts, with no captured user material.
    private fun feed(continuation: Boolean = false): String {
        val videos = (0 until 31).map { index -> """{"compactVideoRenderer":{"videoId":"kid${index.toString().padStart(8, '0')}",
          "title":{"simpleText":"Fixture video $index"},"thumbnail":{"thumbnails":[{"url":"https://example.test/video-$index"}]}}}""" }
        val playlists = (0 until 5).map { index -> """{"compactPlaylistRenderer":{"playlistId":"PLfixture$index","title":{"simpleText":"Fixture collection $index"}}}""" }
        if (continuation) return """{"continuationContents":{"gridContinuation":{"items":[${(videos + playlists).joinToString(",")}],
          "continuations":[{"nextContinuationData":{"continuation":"kids-after-next"}}]}}}"""
        return """{"contents":{"kidsHomeScreenRenderer":{"contents":[
          {"anchoredSectionRenderer":{"contents":[${videos.joinToString(",")}]}},
          {"anchoredSectionRenderer":{"contents":[${playlists.joinToString(",")}]}}
        ]}},"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"kids-next"}}}}"""
    }

    @Test fun publicKidsFeedUsesItsOwnProfileAndParsesTheObservedContentKinds() {
        val requests = ArrayList<HttpRequest>()
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                requests += request
                return HttpReply(200, feed(jsonObject(request.body)?.text("continuation") != null))
            }
        }
        val context = RequestContext(visitor = "visitor", language = "zh", region = "TW",
            visitorCookie = "VISITOR_INFO1_LIVE=not-forwarded", webSession = WebSession("VISITOR_INFO1_LIVE=not-authentication"))
        val first = loadHome(HomeSection.KIDS, context, http = http).value!!
        assertEquals(31, first.items.count { it.kind == "video" })
        assertEquals(5, first.items.count { it.kind == "playlist" })
        assertTrue(first.items.filter { it.kind == "video" }.all { !it.title.isNullOrBlank() && !it.thumbnail.isNullOrBlank() })
        assertEquals("kids-next", first.nextKey)
        val next = loadHome(HomeSection.KIDS, context, first.nextKey, http)
        assertTrue(next.error?.code, next.ok)
        assertEquals(31, next.value!!.items.count { it.kind == "video" })
        assertEquals(5, next.value!!.items.count { it.kind == "playlist" })
        assertEquals("kids-after-next", next.value!!.nextKey)
        assertEquals(2, requests.size)
        for ((index, request) in requests.withIndex()) {
            assertEquals("POST", request.method)
            assertEquals("https://www.youtube.com/youtubei/v1/browse?prettyPrint=false&alt=json", request.url)
            assertEquals("76", request.headers["X-Youtube-Client-Name"])
            assertEquals("2.20260205.00.00", request.headers["X-Youtube-Client-Version"])
            assertEquals("visitor", request.headers["X-Goog-Visitor-Id"])
            assertTrue(request.headers.keys.none { it.equals("Cookie", true) || it.equals("Authorization", true) ||
                it.equals("X-Goog-AuthUser", true) || it.equals("X-Goog-PageId", true) || it.equals("X-Origin", true) })
            val root = jsonObject(request.body)!!
            assertEquals(if (index == 0) "FEkids_home" else null, root.text("browseId"))
            assertEquals(if (index == 1) "kids-next" else null, root.text("continuation"))
            val client = root.path("context", "client")!!
            assertEquals("WEB_KIDS", client.text("clientName"))
            assertEquals("zh", client.text("hl"))
            assertEquals("TW", client.text("gl"))
            val app = client.obj("kidsAppInfo")!!
            assertEquals(20, app.obj("categorySettings")!!.arr("enabledCategories").size)
            assertEquals("KIDS_CORPUS_PREFERENCE_YOUNGER", app.obj("contentSettings")!!.text("corpusPreference"))
            assertEquals("YT_KIDS_NO_SEARCH_MODE_OFF", app.obj("contentSettings")!!.text("kidsNoSearchMode"))
            assertFalse(root.path("context", "user")!!.fields.containsKey("onBehalfOfUser"))
            assertFalse(request.body.contains("not-forwarded"))
            assertFalse(request.body.contains("not-authentication"))
        }
    }

    @Test fun loginAndDelegationRequireAnExplicitAnonymousRequestWithoutAnyFallback() {
        var calls = 0
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply { calls++; return HttpReply(200, feed()) }
        }
        val login = RequestContext(accountId = "owner", webSession = WebSession("SAPISID=secret", 2), visitorCookie = "VISITOR_INFO1_LIVE=not-forwarded")
        for (context in listOf(login, login.copy(onBehalfOfUser = "brand"), RequestContext(onBehalfOfUser = "brand"))) {
            for (continuation in listOf(null, "kids-next")) {
                val result = loadHome(HomeSection.KIDS, context, continuation, http)
                assertNull(result.value)
                assertEquals(RequestFailure.UNSUPPORTED, result.error!!.kind)
                assertEquals("kids_requires_explicit_anonymous_context", result.error!!.code)
                assertTrue(homeQuery(HomeSection.KIDS, context, continuation).url.isEmpty())
            }
        }
        assertEquals(0, calls)
        assertTrue(loadHome(HomeSection.KIDS, login.anonymous(), http = http).ok)
        assertEquals(1, calls)
    }
}
