package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class ContentAuthorRoleTest {
    private val owner = "UC_x5XG1OV2P6uZZ5FSM9Ttw"
    private val video = "jNQXAC9IVRw"
    private val singleAvatar = """{"avatarViewModel":{"image":{"sources":[{"url":"https://example.test/author"}]}}}"""
    private val decoratedAvatar = """{"decoratedAvatarViewModel":{"avatar":$singleAvatar}}"""
    private fun card(label: String = "Unspecified label", metadataExtra: String = "", extra: String = "", linked: Boolean = false) =
        """{"lockupViewModel":{"contentId":"$video","contentType":"LOCKUP_CONTENT_TYPE_VIDEO",
          "rendererContext":{"commandContext":{"onTap":{"innertubeCommand":{"watchEndpoint":{"videoId":"$video","startTimeSeconds":12}}}}},
          "metadata":{"lockupMetadataViewModel":{"title":{"content":"Video title"},
            "metadata":{"contentMetadataViewModel":{"metadataRows":[
              {"metadataParts":[{"text":{"content":"$label"}${if (linked) ",\"navigationEndpoint\":{\"browseEndpoint\":{\"browseId\":\"$owner\"}}" else ""}}]},
              {"metadataParts":[{"text":{"content":"123 views"}},{"text":{"content":"2 days ago"}}]}]}}$metadataExtra}}$extra}}"""
    private fun parsed(card: String) = searchPage("""{"contents":[$card]}""").items.single()

    @Test fun actualWebGenericLabelCannotBecomeAnAuthorButKeepsStatsAndWatchCommand() {
        val item = parsed(card())
        assertNull(item.author); assertNull(item.channelId)
        assertEquals("Video title", item.title); assertEquals("123 views", item.viewCount)
        assertEquals("2 days ago", item.published); assertEquals(12L, item.startSeconds)
        assertEquals("watchEndpoint", item.endpoint!!.name)
        assertEquals(video, jsonObject(item.endpoint!!.payload)!!.text("videoId"))
    }

    @Test fun arbitraryUnlinkedTextDoesNotGainAuthorSemanticsFromItsSpellingOrOrdinal() {
        for (text in listOf("Creator", "A label", "123", "Members", "Electric Dreams", "Song"))
            assertNull(text, parsed(card(text)).author)
    }

    @Test fun explicitBylineAndLinkedAuthorContinueToCarryTheirOwnNames() {
        assertEquals("Direct creator", parsed(card(extra = ",\"ownerText\":\"Direct creator\"")).author)
        val linked = parsed(card("Linked creator", linked = true))
        assertEquals("Linked creator", linked.author); assertEquals(owner, linked.channelId)
        assertEquals("123 views", linked.viewCount); assertEquals("2 days ago", linked.published)
    }

    @Test fun onlyTheMetadataOwnSingleAvatarCanEstablishAnUnlinkedName() {
        for (avatar in listOf(singleAvatar, decoratedAvatar)) {
            val item = parsed(card("Avatar creator", ",\"image\":$avatar"))
            assertEquals("Avatar creator", item.author)
            assertEquals("https://example.test/author", item.authorThumbnail)
        }
        assertNull(parsed(card(metadataExtra = ",\"responseContext\":$singleAvatar")).author)
        assertNull(parsed(card(metadataExtra = ",\"futureRole\":{\"image\":$singleAvatar}")).author)
        assertNull(parsed(card(metadataExtra = ",\"image\":{\"futureRole\":$singleAvatar}")).author)
    }

    @Test fun parallelMultipleMalformedOrForeignAvatarRolesCannotEstablishAnAuthor() {
        val parallel = listOf(
            ",\"avatar\":{\"avatarStackViewModel\":{\"avatars\":[{},{}]}}",
            ",\"avatarStackViewModel\":{\"avatars\":[{},{}]}", ",\"avatar\":\"wrong\"")
        for (extra in parallel) assertNull(parsed(card(metadataExtra = ",\"image\":$singleAvatar", extra = extra)).author)
        for (image in listOf("null", "[]", "\"wrong\"", "{\"avatarStackViewModel\":{\"avatars\":[{},{}]}}",
            "{\"avatarViewModel\":{},\"decoratedAvatarViewModel\":{}}", "{\"avatarViewModel\":{\"image\":{}}}"))
            assertNull(parsed(card(metadataExtra = ",\"image\":$image")).author)
        for (declaration in listOf("\"wrong\"", "{\"webCommandMetadata\":\"wrong\"}",
            "{\"webCommandMetadata\":{\"apiUrl\":\"/youtubei/v1/like/like\"}}")) {
            val image = singleAvatar.replace("{\"avatarViewModel\":", "{\"commandMetadata\":$declaration,\"avatarViewModel\":")
            assertNull(parsed(card(metadataExtra = ",\"image\":$image")).author)
        }
    }

    @Test fun statsRemainStatsEvenWhenTheMetadataHasAnOwnedAvatar() {
        for (text in listOf("123 views", "2 days ago")) {
            val item = parsed(card(text, ",\"image\":$singleAvatar"))
            assertNull(item.author)
            assertNotNull(item.viewCount); assertNotNull(item.published)
        }
    }

    @Test fun actualCatalogVideoPageAndItsIssuedNextUseOwnerDisplayWithoutInventingCreators() {
        val requests = ArrayList<HttpRequest>()
        val wire = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                requests += request
                return HttpReply(200, when (requests.size) {
                    1 -> """{"metadata":{"channelMetadataRenderer":{"externalId":"$owner","title":"Owner","avatar":{"thumbnails":[{"url":"https://example.test/owner"}]}}},
                      "contents":{"twoColumnBrowseResultsRenderer":{"tabs":[
                        {"tabRenderer":{"tabIdentifier":"home","selected":true}},
                        {"tabRenderer":{"tabIdentifier":"videos","endpoint":{"browseEndpoint":{"browseId":"$owner","params":"issued-videos"}}}}]}}}"""
                    2 -> {
                        assertEquals("issued-videos", jsonObject(request.body)!!.text("params"))
                        """{"contents":{"richGridRenderer":{"contents":[${card()},
                          {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"issued-next"}}}}]}}}"""
                    }
                    else -> {
                        assertEquals("issued-next", jsonObject(request.body)!!.text("continuation"))
                        """{"onResponseReceivedActions":[{"appendContinuationItemsAction":{"continuationItems":[${card()}]}}]}"""
                    }
                })
            }
        }
        val catalog = CatalogSession(AccountSession(), wire, 0)
        val initial = catalog.channel(owner, ChannelTabKind.VIDEOS).value!!
        val next = catalog.next(initial.next!!).value!!
        for (page in listOf(initial, next)) {
            val prepared = page.items.single()
            assertNull(prepared.item.author); assertNull(prepared.item.channelId)
            assertNull(prepared.author.name); assertNull(prepared.author.channelId)
            assertEquals(DisplayBylineRole.COLLECTION_OWNER, prepared.displayByline.role)
            assertEquals(owner, prepared.displayByline.author.channelId)
            assertEquals("https://example.test/owner", prepared.displayByline.author.avatar!!.best!!.url)
            assertEquals("123 views", prepared.item.viewCount); assertEquals("2 days ago", prepared.item.published)
            assertEquals(0, page.preparationRequestCount)
        }
        assertEquals(3, requests.size)
    }
}
