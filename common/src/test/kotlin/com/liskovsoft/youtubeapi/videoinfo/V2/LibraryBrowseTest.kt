package com.liskovsoft.youtubeapi.videoinfo.V2
import org.junit.Assert.*
import org.junit.Test
class LibraryBrowseTest {
    @Test fun libraryUsesTheWebLibraryEntryAndRetainsAccountIdentityForContinuations() {
        var calls = 0
        val context = RequestContext(accountId = "account", webSession = WebSession("SAPISID=fixture", 1), onBehalfOfUser = "brand")
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                assertEquals("SAPISID=fixture", request.headers["Cookie"])
                assertEquals("1", request.headers["X-Goog-AuthUser"])
                assertEquals("brand", request.headers["X-Goog-PageId"])
                val root = jsonObject(request.body)!!
                assertEquals("WEB", root.path("context", "client")!!.text("clientName"))
                assertEquals("brand", root.path("context", "user")!!.text("onBehalfOfUser"))
                if (calls++ == 0) assertEquals("FElibrary", root.text("browseId")) else {
                    assertEquals("library-next", root.text("continuation"))
                    assertNull(root.text("browseId"))
                }
                val item = """{"playlistRenderer":{"playlistId":"PLsaved","title":{"simpleText":"Saved list"}}}"""
                return HttpReply(200, if (calls == 1) item else
                    """{"continuationContents":{"gridContinuation":{"items":[$item]}}}""")
            }
        }
        assertEquals("PLsaved", loadLibrary(LibrarySection.LIBRARY, context, http = http).value!!.items.single().id)
        assertEquals("Saved list", loadLibrary(LibrarySection.LIBRARY, context, "library-next", http).value!!.items.single().title)
        assertEquals(2, calls)
    }

    @Test fun myVideosUsesTheCookieAuthorizedTvProfileForBothInitialAndContinuationReads() {
        var calls = 0
        val context = RequestContext(accountId = "account", webSession = WebSession("SAPISID=fixture", 1), onBehalfOfUser = "brand")
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                assertEquals("SAPISID=fixture", request.headers["Cookie"])
                assertTrue(request.headers["Authorization"]!!.startsWith("SAPISIDHASH "))
                assertEquals("1", request.headers["X-Goog-AuthUser"])
                assertEquals("brand", request.headers["X-Goog-PageId"])
                val root = jsonObject(request.body)!!
                assertEquals("TVHTML5", root.path("context", "client")!!.text("clientName"))
                assertEquals("brand", root.path("context", "user")!!.text("onBehalfOfUser"))
                if (calls++ == 0) assertEquals("FEmy_videos", root.text("browseId")) else {
                    assertEquals("uploads-next", root.text("continuation"))
                    assertNull(root.text("browseId"))
                }
                val item = """{"videoRenderer":{"videoId":"upload","title":{"simpleText":"My upload"}}}"""
                return HttpReply(200, if (calls == 1)
                    """{"contents":{"tvBrowseRenderer":{"content":{"tvSurfaceContentRenderer":{"content":$item}}}}}"""
                    else """{"continuationContents":{"gridContinuation":{"items":[$item]}}}""")
            }
        }
        assertEquals(RequestFailure.AUTH, loadLibrary(LibrarySection.MY_VIDEOS, RequestContext(), http = http).error?.kind)
        assertEquals("upload", loadLibrary(LibrarySection.MY_VIDEOS, context, http = http).value!!.items.single().id)
        assertEquals("My upload", loadLibrary(LibrarySection.MY_VIDEOS, context, "uploads-next", http).value!!.items.single().title)
        assertEquals(2, calls)
    }

    @Test fun anOpenedInitialBrowseSuppliesTheDirectoryWithoutRepeatingTheRequest() {
        var calls = 0
        val http = object : HttpClient { override fun execute(request: HttpRequest): HttpReply {
            calls++
            if (request.url.contains("guide")) return HttpReply(200,
                """{"guideEntryRenderer":{"title":{"simpleText":"Ada"},"navigationEndpoint":{"browseEndpoint":{"browseId":"UCada"}}}}""")
            return HttpReply(200, """{"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{
                "title":"Ada","endpoint":{"browseEndpoint":{"browseId":"UCada","params":"channel-filter"}},
                "presentationStyle":{"style":"NEW_CONTENT"}}}]}},"videoRenderer":{"videoId":"abcdefghijk"}}""")
        } }
        val context = RequestContext(accountId = "a", webSession = WebSession("SAPISID=token"), generation = 7)
        val opened = loadSubscriptionsBrowse(context, http).value!!
        assertEquals("abcdefghijk", opened.feed.value!!.items.single().id)
        val directory = loadSubscribedChannelsPage(context, opened, http).value!!
        assertEquals("channel-filter", directory.channels.single().params)
        assertTrue(directory.channels.single().hasNewContent)
        assertEquals(1, calls)
        loadSubscribedChannelsPage(context.copy(generation = 8), opened, http)
        assertEquals(2, calls)
        loadSubscribedChannelsPage(context.copy(webSession = WebSession("SAPISID=rotated")), opened, http)
        assertEquals(3, calls)
    }

    @Test fun requiresAnAccountAndKeepsPrivateEmptyPagesDistinctFromErrors() {
        var calls = 0
        val http = object : HttpClient { override fun execute(request: HttpRequest): HttpReply {
            calls++; assertEquals("SAPISID=token", request.headers["Cookie"])
            assertEquals("FEsubscriptions", jsonObject(request.body)!!.text("browseId"))
            return HttpReply(200, """{"sectionListRenderer":{"contents":[]}}""")
        }}
        assertFalse(loadLibrary(LibrarySection.SUBSCRIPTIONS, RequestContext(), http = http).ok); assertEquals(0, calls)
        assertTrue(loadLibrary(LibrarySection.SUBSCRIPTIONS, RequestContext(accountId = "a", webSession = WebSession("SAPISID=token")), http = http).ok)
    }
    @Test fun extractsSubscribedChannelsFromTheGuide() {
        val http = object : HttpClient { override fun execute(request: HttpRequest) = HttpReply(200, """{"guideEntryRenderer":{"title":{"simpleText":"Ada"},"navigationEndpoint":{"browseEndpoint":{"browseId":"UCada"}}}}""") }
        assertEquals("UCada", loadSubscribedChannels(RequestContext(accountId = "a", webSession = WebSession("SAPISID=t")), http).value!!.single().id)
    }

    @Test fun readsFormattedGuideTitlesWithoutAFallbackRequest() {
        var calls = 0
        val http = object : HttpClient { override fun execute(request: HttpRequest): HttpReply {
            calls++
            return HttpReply(200, """{"guideEntryRenderer":{"formattedTitle":{"simpleText":"Ada"},"navigationEndpoint":{"browseEndpoint":{"browseId":"UCada"}}}}""")
        }}
        assertEquals("Ada", loadSubscribedChannels(RequestContext(accountId = "a", webSession = WebSession("SAPISID=t")), http).value!!.single().title)
        assertEquals(1, calls)
    }

    @Test fun anAccountOnlyGuideFallsBackToTheSubscribedChannelsBrowse() {
        var calls = 0
        val http = object : HttpClient { override fun execute(request: HttpRequest): HttpReply {
            calls++
            assertEquals("SAPISID=token", request.headers["Cookie"])
            if (jsonObject(request.body)?.text("browseId") == "FEsubscriptions") return HttpReply(200, "{}")
            return if (request.url.contains("guide")) HttpReply(200, """{"guideAccountEntryRenderer":{}}""")
            else {
                assertEquals("FEchannels", jsonObject(request.body)!!.text("browseId"))
                HttpReply(200, """{"channelRenderer":{"channelId":"UCada","title":{"simpleText":"Ada"},"thumbnail":{"thumbnails":[{"url":"https://example.com/avatar"}]}}}""")
            }
        }}
        val item = loadSubscribedChannels(RequestContext(accountId = "a", webSession = WebSession("SAPISID=token")), http).value!!.single()
        assertEquals("UCada", item.id)
        assertEquals("Ada", item.title)
        assertEquals("https://example.com/avatar", item.thumbnail)
        assertEquals(2, calls)
    }

    @Test fun aRejectedFallbackRemainsAnError() {
        val http = object : HttpClient { override fun execute(request: HttpRequest) =
            if (request.url.contains("guide")) HttpReply(200, "{}") else HttpReply(403, "{}") }
        assertFalse(loadSubscribedChannels(RequestContext(accountId = "a", webSession = WebSession("SAPISID=token")), http).ok)
    }
}
