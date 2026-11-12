package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class EmbeddedAuthorArtworkTest {
    private val channel = "UC" + "a".repeat(22)
    private val avatar = """{"avatarViewModel":{"image":{"sources":[{"url":"https://example.test/author","width":48,"height":48}]}}}"""

    @Test fun aSingleAuthorStackRetainsArtworkButMultipleAuthorsDoNotBorrowTheFirstNestedChannel() {
        fun page(avatars: String) = searchPage("""{"videoRenderer":{"videoId":"video","avatar":{"avatarStackViewModel":{
            "avatars":[$avatars],"rendererContext":{"commandContext":{"onTap":{"innertubeCommand":{"showDialogCommand":{
              "dialog":{"browseEndpoint":{"browseId":"$channel"}}}}}}}}}}}""").items.single()
        val single = page(avatar)
        assertEquals(48, single.authorArtwork!!.variants.single().width)
        assertEquals("https://example.test/author", single.authorThumbnail)
        val multiple = page("$avatar,$avatar")
        assertNull(multiple.authorArtwork)
        assertNull(multiple.authorThumbnail)
        assertNull(multiple.channelId)
    }

    @Test fun shortsPrimaryHeaderRetainsItsOwnAuthorImageAndDoesNotCopyItToUnseenEntries() {
        // Protocol source: YouTube.js ReelPlayerHeader.channel_thumbnail <- channelThumbnail.
        val page = shortsPage("""{"replacementEndpoint":{"reelWatchEndpoint":{"videoId":"first"}},
            "videoDescriptionHeaderRenderer":{"title":{"simpleText":"Description without channel navigation"}},
            "reelPlayerHeaderRenderer":{"channelTitleText":{"simpleText":"Author"},
                "channelNavigationEndpoint":{"browseEndpoint":{"browseId":"$channel"}},
                "channelThumbnail":{"thumbnails":[{"url":"//example.test/short-author","width":88,"height":88}]}},
            "entries":[{"command":{"reelWatchEndpoint":{"videoId":"second"}}}]}""")
        assertEquals(channel, page.items[0].channelId)
        assertEquals("https://example.test/short-author", page.items[0].authorThumbnail)
        assertEquals(88, page.items[0].authorArtwork!!.best!!.height)
        assertNull(page.items[1].authorThumbnail)
        assertNull(page.items[1].authorArtwork)
        assertNull(page.items[1].channelId)
    }
}
