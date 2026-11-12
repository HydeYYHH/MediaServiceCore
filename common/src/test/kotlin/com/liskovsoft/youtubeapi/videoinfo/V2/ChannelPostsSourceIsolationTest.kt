package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

/** Real issued channel capabilities must originate in the channel's direct tab collection. */
class ChannelPostsSourceIsolationTest {
    private val channel = "UC_x5XG1OV2P6uZZ5FSM9Ttw"
    private class Wire(private val reply: (HttpRequest, Int) -> String) : HttpClient {
        val requests = ArrayList<HttpRequest>()
        override fun execute(request: HttpRequest): HttpReply {
            requests += request
            return HttpReply(200, reply(request, requests.lastIndex))
        }
    }
    private fun cursor(key: String) = """{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"$key"}}}}"""
    private fun post(id: String) = """{"backstagePostRenderer":{"postId":"$id","contentText":{"simpleText":"Owned post"}}}"""
    private fun tab(kind: String, selected: Boolean, content: String? = null) = """{"tabRenderer":{
        "title":"Tab", "tabIdentifier":"$kind", "selected":$selected,
        "endpoint":{"browseEndpoint":{"browseId":"$channel","params":"route-$kind"},
            "commandMetadata":{"webCommandMetadata":{"url":"/channel/$channel/$kind"}}}
        ${content?.let { ",\"content\":$it" }.orEmpty()}
    }}"""
    private fun landing(tabs: String, metadata: String = "{}") = """{
        "metadata":{"channelMetadataRenderer":{"externalId":"$channel","title":"Channel"}},
        "responseContext":$metadata,
        "contents":{"twoColumnBrowseResultsRenderer":{"tabs":[$tabs]}}
    }"""

    @Test fun aMetadataPostsTabCannotIssueAChannelRoute() {
        val fakeTab = tab("posts", true, """{"contents":[${post("metadata-post")},${cursor("METADATA_NEXT")}]}""")
        val http = Wire { _, _ -> landing(tab("home", true), fakeTab) }
        val opened = ChannelSession(AccountSession(), http).open(channel)
        assertTrue(opened.ok)
        val handle = opened.value!!
        assertEquals(listOf(ChannelTabKind.HOME), handle.availableTabs)
        assertEquals(RequestFailure.UNSUPPORTED, handle.openPosts().error?.kind)
        assertEquals(1, http.requests.size)
    }

    @Test fun selectedPostsAndTheirIssuedCursorIgnoreUnselectedAndMetadataCollections() {
        val owned = """{"contents":[${post("owned-first")},${cursor("OWNED_NEXT")}]}"""
        val foreign = """{"contents":[${post("foreign-post")},${cursor("FOREIGN_NEXT")}]}"""
        val http = Wire { request, index ->
            if (index == 0) landing("${tab("posts", true, owned)},${tab("home", false, foreign)}",
                """{"backstagePostRenderer":{"postId":"metadata-post"},"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"METADATA_NEXT"}}}}""")
            else {
                assertEquals("OWNED_NEXT", jsonObject(request.body)!!.text("continuation"))
                """{"contents":[${post("owned-last")}]}"""
            }
        }
        val handle = ChannelSession(AccountSession(), http).open(channel).value!!
        val first = handle.openPosts().value!!
        assertEquals(listOf("owned-first"), first.page.posts.map { it.postId })
        assertNotNull(first.next)
        assertEquals(1, http.requests.size)
        val last = handle.nextPosts(first.next!!).value!!
        assertEquals(listOf("owned-last"), last.page.posts.map { it.postId })
        assertNull(last.next)
        assertEquals(2, http.requests.size)
    }

    @Test fun aNestedTabInsideSelectedContentCannotBecomeAnotherChannelCapability() {
        val nested = tab("posts", false)
        val content = """{"contents":[],"responseContext":$nested}"""
        val http = Wire { _, _ -> landing(tab("home", true, content)) }
        val handle = ChannelSession(AccountSession(), http).open(channel).value!!
        assertEquals(listOf(ChannelTabKind.HOME), handle.availableTabs)
        assertEquals(RequestFailure.UNSUPPORTED, handle.openPosts().error?.kind)
        assertEquals(1, http.requests.size)
    }
}
