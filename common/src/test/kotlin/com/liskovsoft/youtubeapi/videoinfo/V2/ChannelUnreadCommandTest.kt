package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class ChannelUnreadCommandTest {
    private val id = "UC_x5XG1OV2P6uZZ5FSM9Ttw"
    private val other = "UCBR8-60-B28hp2BmDPdntcQ"
    private val post = """{"backstagePostRenderer":{"postId":"Ugkx_owned_post","contentText":{"simpleText":"Announcement"}}}"""
    private val video = """{"videoRenderer":{"videoId":"aaaaaaaaaaa","title":{"simpleText":"Lesson"}}}"""
    private fun tab(kind: String, selected: Boolean = false, content: String = "") = """{"tabRenderer":{
        "title":"Tab","selected":$selected,"endpoint":{"browseEndpoint":{"browseId":"$id","params":"issued-$kind"},
        "commandMetadata":{"webCommandMetadata":{"url":"/channel/$id/$kind","apiUrl":"/youtubei/v1/browse"}}}
        ${if (content.isEmpty()) "" else ",\"content\":$content"}}}"""
    private fun landing(tabs: String, actions: String) = """{
        "metadata":{"channelMetadataRenderer":{"externalId":"$id","title":"Channel"}},
        "contents":{"twoColumnBrowseResultsRenderer":{"tabs":[$tabs]}},"onResponseReceivedActions":$actions}"""
    private fun reset(channel: String = id) = """{"resetChannelUnreadCountCommand":{"channelId":"$channel"}}"""
    private class Wire(private val replies: List<String>) : HttpClient {
        val requests = mutableListOf<HttpRequest>()
        override fun execute(request: HttpRequest): HttpReply {
            requests += request
            return HttpReply(200, replies[requests.lastIndex])
        }
    }

    @Test fun matchingUnreadAcknowledgementLeavesTheCanonicalOpeningAndItsDeferredCapabilitiesIntact() {
        val opening = landing(tab("featured", true, """{"richGridRenderer":{"contents":[$video]}}""") + "," + tab("videos"), "[${reset()}]")
        for (context in listOf(RequestContext(), RequestContext(accountId = "test-account", webSession = WebSession("SAPISID=fixture", 1)),
            RequestContext(accountId = "test-brand", webSession = WebSession("SAPISID=fixture"), onBehalfOfUser = "test-page"))) {
            val wire = Wire(listOf(opening, """{"contents":{"richGridRenderer":{"contents":[$video]}}}"""))
            val result = ChannelSession(AccountSession(context), wire).open(id)
            assertTrue(result.error.toString(), result.ok)
            val handle = result.value!!
            assertEquals(listOf(ChannelTabKind.HOME, ChannelTabKind.VIDEOS), handle.availableTabs)
            assertEquals("aaaaaaaaaaa", handle.openTab(ChannelTabKind.HOME).value!!.page.items.single().id)
            assertEquals(1, wire.requests.size)
            assertEquals("aaaaaaaaaaa", handle.openTab(ChannelTabKind.VIDEOS).value!!.page.items.single().id)
            assertEquals("issued-videos", jsonObject(wire.requests.last().body)!!.text("params"))
            assertEquals(2, wire.requests.size)
        }
    }

    @Test fun matchingUnreadAcknowledgementAlsoKeepsSelectedAndDiscoveredPostsOwnedByTheirChannel() {
        val selected = landing(tab("posts", true, """{"richGridRenderer":{"contents":[$post]}}"""), "[${reset()}]")
        val selectedWire = Wire(listOf(selected))
        val handle = ChannelSession(AccountSession(), selectedWire).open(id).value!!
        assertEquals("Ugkx_owned_post", handle.openPosts().value!!.page.posts.single().postId)
        assertEquals(1, selectedWire.requests.size)
        val direct = Wire(listOf(selected))
        assertEquals("Ugkx_owned_post", loadChannelPosts(id, http = direct).value!!.posts.single().postId)
        assertEquals(1, direct.requests.size)

        val opening = landing(tab("featured", true, """{"richGridRenderer":{"contents":[]}}""") + "," + tab("posts"), "[${reset()}]")
        val discovered = Wire(listOf(opening, """{"contents":[$post]}"""))
        assertEquals("Ugkx_owned_post", loadChannelPosts(id, http = discovered).value!!.posts.single().postId)
        assertEquals("issued-posts", jsonObject(discovered.requests.last().body)!!.text("params"))
        assertEquals(2, discovered.requests.size)
    }

    @Test fun auxiliaryCommandsCannotChangeOwnersOrIntroduceAnotherContentCollection() {
        val append = """{"appendContinuationItemsAction":{"continuationItems":[$video]}}"""
        val reload = """{"reloadContinuationItemsCommand":{"slot":"RELOAD_CONTINUATION_SLOT_BODY","continuationItems":[$video]}}"""
        val invalid = listOf("[]", "{}", "[${reset(other)}]",
            """[{"resetChannelUnreadCountCommand":{"channelId":7}}]""",
            """[{"resetChannelUnreadCountCommand":{}}]""",
            """[{"resetChannelUnreadCountCommand":{"channelId":"$id","continuationItems":[$video]}}]""",
            """[{"trackingParams":7,"resetChannelUnreadCountCommand":{"channelId":"$id"}}]""",
            "[${reset()},$append]", "[${reset()},$reload]", "[$append]", "[$reload]",
            """[{"futureChannelCommand":{"resetChannelUnreadCountCommand":{"channelId":"$id"}}}]""",
            """[{"resetChannelUnreadCountCommand":{"channelId":"$id"},"appendContinuationItemsAction":{"continuationItems":[]}}]""")
        for (actions in invalid) {
            val root = landing(tab("featured", true), actions)
            val wire = Wire(listOf(root))
            val result = ChannelSession(AccountSession(), wire).open(id)
            assertNull(result.value)
            assertEquals("unknown_channel_tabs", result.error?.code)
            assertEquals(1, wire.requests.size)
            assertNull(ownedChannelTabs(jsonObject(root)!!)) // No caller-owned channel means no exception to the root roles.
        }
    }

    @Test fun theUnreadCommandIsNotAContinuationOrAnAlternateResponseEnvelope() {
        val opening = landing(tab("featured", true), "[${reset()}]")
        for (family in listOf("onResponseReceivedEndpoints", "onResponseReceivedCommands")) {
            val wire = Wire(listOf(opening.replace("onResponseReceivedActions", family)))
            assertEquals("unknown_channel_tabs", ChannelSession(AccountSession(), wire).open(id).error?.code)
        }
        val continued = """{"onResponseReceivedActions":[${reset()}]}"""
        assertNull(ownedChannelPosts(jsonObject(continued)!!, id, true))
        val mixed = opening.dropLast(1) + ",\"onResponseReceivedCommands\":[]}"
        assertEquals("unknown_channel_tabs", ChannelSession(AccountSession(), Wire(listOf(mixed))).open(id).error?.code)
    }
}
