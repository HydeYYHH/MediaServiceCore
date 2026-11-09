package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class CompactPageReadTest {
    @Test fun compactPageUrlsPreserveOtherParametersFragmentsHeadersAndBodies() {
        val headers = mapOf("Authorization" to "Bearer test-token")
        val original = PlayerQuery(YOUTUBE_API + "browse?key=a%2Bb&fields=a%23b#section?part", headers, "{\"preserved\":true}")
        val compact = original.withCompactResponse()
        assertEquals(YOUTUBE_API + "browse?key=a%2Bb&fields=a%23b&prettyPrint=false#section?part", compact.url)
        assertSame(headers, compact.headers)
        assertEquals(original.body, compact.body)
        assertSame(compact, compact.withCompactResponse())
        assertEquals(YOUTUBE_API + "search?prettyPrint=false", PlayerQuery(YOUTUBE_API + "search", headers, "{}").withCompactResponse().url)
        assertEquals(YOUTUBE_API + "search?prettyPrint=false#f", PlayerQuery(YOUTUBE_API + "search?#f", headers, "{}").withCompactResponse().url)
    }

    @Test fun explicitFormattingAndNonApiUrlsAreNeverRewritten() {
        for (url in listOf(YOUTUBE_API + "browse?prettyPrint=true#fragment", YOUTUBE_API + "search?x=1&prettyPrint=false",
            YOUTUBE_API + "search?prettyPrint", YOUTUBE_API + "search?%70rettyPrint=true",
            "https://example.com/youtubei/v1/search", "https://www.youtube.com.evil.test/youtubei/v1/browse",
            "http://www.youtube.com/youtubei/v1/browse", "https://www.youtube.com/watch?v=id")) {
            val query = PlayerQuery(url, emptyMap(), "unchanged")
            assertSame(url, query, query.withCompactResponse())
        }
    }

    @Test fun browseAndBothSearchAuthenticationAttemptsRequestCompactPagesOnly() {
        val requests = mutableListOf<HttpRequest>()
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                requests += request
                return if (requests.size == 2) HttpReply(401, "{}") else HttpReply(200, "{}")
            }
        }
        assertTrue(loadBrowse("channel", http = http).ok)
        assertTrue(loadSearch("q", RequestContext("account", "token", "visitor"), http = http).ok)
        assertEquals(3, requests.size)
        assertTrue(requests.all { urlParameter(it.url, "prettyPrint") == "false" })
        assertNotNull(requests[1].headers["Authorization"])
        assertNull(requests[2].headers["Authorization"])
        // The generic transport keeps its existing URL, including non-page consumers.
        requestObject(searchQuery("q"), http)
        assertFalse(requests.last().url.contains("prettyPrint"))
    }

    @Test fun homeBrowsePagesUseCompactJsonWhileShortsDocumentAndSequenceKeepTheirUrls() {
        val requests = mutableListOf<HttpRequest>()
        val seed = """{"replacementEndpoint":{"reelWatchEndpoint":{"videoId":"entry000001"}},"entries":[],"sequenceContinuation":"next"}"""
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                requests += request
                return HttpReply(200, when {
                    request.method == "GET" -> "<script>var ytInitialData = $seed;</script>"
                    request.url.contains("reel_watch_sequence") -> seed
                    else -> "{}"
                })
            }
        }
        assertTrue(loadHome(HomeSection.HOME, http = http).ok)
        assertTrue(loadHome(HomeSection.GAMING, http = http).ok)
        assertTrue(loadHome(HomeSection.SHORTS, http = http).ok)
        assertTrue(loadHome(HomeSection.SHORTS, continuation = "next", http = http).ok)
        assertEquals(listOf("false", "false", null, null), requests.map { urlParameter(it.url, "prettyPrint") })
        assertEquals(listOf("POST", "POST", "GET", "POST"), requests.map { it.method })
        assertEquals("https://m.youtube.com/shorts?hl=en", requests[2].url)
        assertEquals(homeQuery(HomeSection.SHORTS, continuation = "next").request(), requests.last())
    }

    @Test fun sharedSubscriptionsBrowseRequestsCompactJsonAndRetainsBothConsumers() {
        val context = RequestContext("account", "token", "visitor", "zh", "TW", 7, "brand")
        val expected = browseQuery(LibrarySection.SUBSCRIPTIONS.browseId, context).request()
        val body = """{"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{
            "title":"Ada","endpoint":{"browseEndpoint":{"browseId":"UCada","params":"channel-filter"}},
            "presentationStyle":{"style":"NEW_CONTENT"}}}]}},"videoRenderer":{"videoId":"abcdefghijk"}}"""
        var calls = 0
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                calls++
                assertEquals(expected.copy(url = expected.url + "?prettyPrint=false"), request)
                return HttpReply(200, body)
            }
        }
        val opened = loadSubscriptionsBrowse(context, http).value!!
        assertEquals(browsePage(body), opened.feed.value)
        val directory = loadSubscribedChannelsPage(context, opened, http).value!!
        assertEquals("UCada", directory.channels.single().id)
        assertEquals("channel-filter", directory.channels.single().params)
        assertTrue(directory.channels.single().hasNewContent)
        assertEquals(1, calls)
    }

    @Test fun channelPostsRequestCompactJsonWithoutChangingIdentityPayloadOrContinuation() {
        val context = RequestContext("account", "token", "visitor", "ja", "JP", 8, "brand")
        val body = """{"backstagePostRenderer":{"postId":"post-id","authorText":{"simpleText":"Ada"},
            "contentText":{"runs":[{"text":"Post body"}]},"voteCount":{"simpleText":"12"}},
            "continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"next-posts"}}}}"""
        for (cursor in listOf(null, "posts-cursor")) {
            val expected = channelQuery("UCada", context, "posts-params", cursor).request()
            var calls = 0
            val http = object : HttpClient {
                override fun execute(request: HttpRequest): HttpReply {
                    calls++
                    assertEquals(expected.copy(url = expected.url + "?prettyPrint=false"), request)
                    return HttpReply(200, body)
                }
            }
            val page = loadChannelPosts("UCada", context, "posts-params", cursor, http).value!!
            assertEquals(listOf(PostItem("UCada", "post-id", "Ada", null, null, "Post body", emptyList(), "12", null)), page.posts)
            assertEquals("next-posts", page.nextKey)
            assertEquals(1, calls)
        }
    }
}
