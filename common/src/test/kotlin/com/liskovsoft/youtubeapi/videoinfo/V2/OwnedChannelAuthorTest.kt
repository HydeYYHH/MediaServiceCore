package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class OwnedChannelAuthorTest {
    private val channel = "UC" + "a".repeat(22)
    private val other = "UC" + "b".repeat(22)
    private fun read(body: String) = ownedChannelAuthor(jsonObject(body)!!, channel)
    private fun avatar(url: String = "https://example.test/owner") =
        """{"thumbnails":[{"url":"$url","width":88,"height":88},{"url":"https://example.test/large","width":176,"height":176}]}"""

    @Test fun directMetadataAndHeaderIdentityOwnOnlyTheirFixedPresentationFields() {
        val owner = read("""{"metadata":{"channelMetadataRenderer":{"externalId":"$channel","title":"Owner","avatar":${avatar()}}},"header":{"c4TabbedHeaderRenderer":{"channelId":"$channel","title":"Header","avatar":{"sources":[{"url":"//example.test/header","width":48,"height":48}]}}}}""")
        assertEquals(channel, owner.channelId); assertEquals("Owner", owner.name); assertNull(owner.unavailableReason)
        assertEquals(listOf(88, 176, 48), owner.avatar!!.variants.map { it.width })
        assertEquals("https://example.test/header", owner.avatar!!.select(40, 40)!!.url)
        val header = read("""{"header":{"c4TabbedHeaderRenderer":{"channelId":"$channel","title":"Header","avatar":${avatar()}}}}""")
        assertEquals("Header", header.name); assertNotNull(header.avatar)
    }

    @Test fun callerIdentityAndNestedRendererNamesCannotSupplyOwnerIdentityOrArtwork() {
        val nestedOnly = read("""{"responseContext":{"channelMetadataRenderer":{"externalId":"$channel","title":"Shadow","avatar":${avatar()}}}}""")
        assertNull(nestedOnly.channelId); assertNull(nestedOnly.avatar)
        assertEquals("channel_identity_unavailable", nestedOnly.unavailableReason)
        val shadowArt = read("""{"metadata":{"channelMetadataRenderer":{"externalId":"$channel"}},"responseContext":{"c4TabbedHeaderRenderer":{"channelId":"$channel","avatar":${avatar()}}},"contents":{"channelMetadataRenderer":{"externalId":"$channel","avatar":${avatar()}}}}""")
        assertEquals(channel, shadowArt.channelId); assertNull(shadowArt.avatar)
        assertEquals("artwork_unavailable", shadowArt.unavailableReason)
    }

    @Test fun everyDeclaredDirectIdentityMustBeTypedCanonicalAndMatchTheRequestedOwner() {
        for (body in listOf(
            """{"metadata":{"channelMetadataRenderer":{"externalId":"$other","avatar":${avatar()}}}}""",
            """{"metadata":{"channelMetadataRenderer":{"externalId":"$channel","avatar":${avatar()}}},"header":{"c4TabbedHeaderRenderer":{"channelId":"$other"}}}""",
            """{"metadata":{"channelMetadataRenderer":{"externalId":7,"avatar":${avatar()}}}}""",
            """{"metadata":{"channelMetadataRenderer":{"externalId":"@owner","avatar":${avatar()}}}}""",
            """{"metadata":{"channelMetadataRenderer":{"avatar":${avatar()}}}}""",
            """{"metadata":{"channelMetadataRenderer":{"externalId":"$channel","avatar":${avatar()}}},"header":[]}""",
            """{"metadata":{"channelMetadataRenderer":{"externalId":"$channel","avatar":${avatar()}}},"error":{}}""")) {
            val owner = read(body)
            assertNull(owner.channelId); assertNull(owner.name); assertNull(owner.avatar)
            assertTrue(owner.unavailableReason in setOf("channel_identity_unavailable", "channel_identity_mismatch"))
        }
    }

    @Test fun malformedDeclaredAvatarCannotBeRescuedByAnotherRoleOrHiddenImage() {
        for (bad in listOf("[]", """{"thumbnails":"bad"}""", """{"thumbnails":[{"url":"javascript:bad"}]}""",
            """{"thumbnails":[{"url":"https://example.test/a","width":"88"}]}""",
            """{"thumbnails":[{"url":"https://example.test/a","futureImage":{}}]}""")) {
            val owner = read("""{"metadata":{"channelMetadataRenderer":{"externalId":"$channel","avatar":$bad}},"header":{"c4TabbedHeaderRenderer":{"channelId":"$channel","avatar":${avatar()}}}}""")
            assertEquals(channel, owner.channelId); assertNull(owner.avatar); assertEquals("artwork_unavailable", owner.unavailableReason)
        }
    }

    @Test fun presentationDoesNotBorrowNamesCommandsOrUnknownModernHeaderImages() {
        val owner = read("""{"metadata":{"channelMetadataRenderer":{"externalId":"$channel"}},"header":{"pageHeaderRenderer":{"content":{"pageHeaderViewModel":{"title":{"dynamicTextViewModel":{"text":{"content":"Shadow"}}},"image":{"avatarViewModel":{"image":${avatar()}}}}}}},"responseContext":{"title":"Shadow","createCommentEndpoint":{"createCommentParams":"opaque"}}}""")
        assertEquals(channel, owner.channelId); assertNull(owner.name); assertNull(owner.avatar)
        assertEquals("artwork_unavailable", owner.unavailableReason)
    }

    @Test fun ownerArtworkHasAFiniteVariantBudget() {
        val variants = (0..64).joinToString(",") { """{"url":"https://example.test/$it"}""" }
        val owner = read("""{"metadata":{"channelMetadataRenderer":{"externalId":"$channel","avatar":{"thumbnails":[$variants]}}}}""")
        assertEquals(channel, owner.channelId); assertNull(owner.avatar); assertEquals("artwork_unavailable", owner.unavailableReason)
    }
}
