package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class ParsedBrowseRootTest {
    @Test fun channelLoadPreservesTheEntireParsedPageAndUsesOneResponse() {
        val root = jsonObject(landing)!!
        val encoded = root.encode()
        val expected = channelPage(landing, "UCfallback")
        var requests = 0
        val loaded = loadChannel("UCfixture", http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                requests++
                assertTrue(request.url.contains("prettyPrint=false"))
                return HttpReply(200, landing)
            }
        })

        assertEquals(1, requests)
        assertTrue(loaded.ok)
        assertEquals(expected, loaded.value)
        assertEquals(expected, channelPage(root, "UCfallback"))
        assertEquals(expected.page, browsePage(root))
        assertEquals(encoded, root.encode())
        assertEquals("UCfixture", expected.id)
        assertEquals("Name \"with\" unicode 世界", expected.title)
        assertEquals("Line one\nLine two", expected.description)
        assertEquals("@fixture", expected.handle)
        assertEquals("12 subscribers", expected.subscriberCount)
        assertEquals("34 videos", expected.videoCount)
        assertEquals(true, expected.subscribed)
        assertEquals("https://image.invalid/avatar", expected.thumbnail)
        assertEquals("https://image.invalid/banner", expected.banner)
        assertEquals(listOf("v1"), expected.page.items.map { it.id })
        assertEquals(listOf("Uploads"), expected.page.groups.map { it.title })
        assertEquals(listOf("Videos", "Playlists"), expected.page.tabs.map { it.title })
        assertEquals("opaque-video-params", expected.page.tabs.first().params)
        assertNull(expected.page.nextKey)
        assertEquals("next-page", expected.page.groups.single().nextKey)
        assertEquals(listOf("Server message"), expected.page.messages)
        assertEquals("fixture-visitor", expected.page.visitor)
        assertTrue(expected.page.actions.isNotEmpty())
        assertEquals(expected.page, readBrowsePage(root).value)
    }

    @Test fun malformedPublicStringInputsKeepTheirEmptyPageAndChannelFallback() {
        val empty = BrowsePage(emptyList(), emptyList(), emptyList(), null)
        for (body in listOf("{", "null", "[]", "42")) {
            assertEquals(empty, browsePage(body))
            assertEquals(ChannelPage("UCfallback", null, null, null, null, empty), channelPage(body, "UCfallback"))
        }
    }

    @Test fun loadedBrowseKeepsUnknownEmptyContinuationAndTransportErrorSemantics() {
        var body = """{"contents":[{"futureCardViewModel":{"title":"Unknown"}}]}"""
        var status = 200
        val http = object : HttpClient {
            override fun execute(request: HttpRequest) = HttpReply(status, body)
        }
        assertEquals("unknown_browse_page", loadBrowse("UCfixture", http = http).error!!.code)
        assertEquals("unknown_channel_page", loadChannel("UCfixture", http = http).error!!.code)
        body = "{}"
        assertEquals("unknown_browse_page", loadBrowse(null, continuation = "cursor", http = http).error!!.code)
        for (empty in listOf("""{"responseContext":{}}""", """{"responseContext":{},"trackingParams":"opaque"}""")) {
            body = empty
            assertEquals("unknown_browse_page", loadBrowse("UCfixture", http = http).error!!.code)
            assertTrue(loadBrowse(null, continuation = "cursor", http = http).ok)
        }
        for (empty in listOf("""{"contents":[]}""", """{"sectionListRenderer":{"contents":[]}}""")) {
            body = empty
            val loaded = loadBrowse("UCfixture", http = http)
            assertTrue(loaded.ok)
            assertEquals(browsePage(body), loaded.value)
        }
        body = """{"continuationContents":{"gridContinuation":{"items":[]}}}"""
        assertTrue(loadBrowse(null, continuation = "cursor", http = http).ok)
        body = "{"
        assertEquals("invalid_json", loadChannel("UCfixture", http = http).error!!.code)
        assertEquals("invalid_json", loadBrowse("UCfixture", http = http).error!!.code)
        status = 403
        assertEquals(RequestFailure.AUTH, loadChannel("UCfixture", http = http).error!!.kind)
        assertEquals(403, loadBrowse("UCfixture", http = http).error!!.status)
    }

    private val landing = """
        {
          "responseContext":{"visitorData":"fixture-visitor"},
          "metadata":{"channelMetadataRenderer":{"externalId":"UCfixture","title":"Name \"with\" unicode 世界","description":"Line one\nLine two","avatar":{"thumbnails":[{"url":"https://image.invalid/avatar"}]}}},
          "header":{"c4TabbedHeaderRenderer":{"title":"Fallback title","channelHandleText":{"simpleText":"@fixture"},"subscriberCountText":{"simpleText":"12 subscribers"},"videosCountText":{"simpleText":"34 videos"},"banner":{"thumbnails":[{"url":"https://image.invalid/banner"}]},"subscribeButtonRenderer":{"subscribed":true}}},
          "tabs":[
            {"tabRenderer":{"title":"Videos","selected":true,"endpoint":{"browseEndpoint":{"browseId":"UCfixture","params":"opaque-video-params"}},"content":{
              "shelfRenderer":{"title":{"simpleText":"Uploads"},"items":[{"gridVideoRenderer":{"videoId":"v1","title":{"simpleText":"A video"}}}],"continuations":[{"nextContinuationData":{"continuation":"next-page"}}]},
              "messageRenderer":{"text":{"simpleText":"Server message"}},
              "buttonRenderer":{"text":{"simpleText":"Browse more"},"navigationEndpoint":{"browseEndpoint":{"browseId":"UCmore","params":"opaque-action"}}}
            }}},
            {"tabRenderer":{"title":"Playlists","endpoint":{"browseEndpoint":{"browseId":"UCfixture","params":"opaque-playlist-params"}},"content":{"videoRenderer":{"videoId":"hidden"}}}}
          ]
        }
    """.trimIndent()
}
