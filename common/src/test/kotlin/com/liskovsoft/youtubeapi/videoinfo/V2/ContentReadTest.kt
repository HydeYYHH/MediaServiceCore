package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class ContentReadTest {
    @Test fun readsShortsContentImagesAndFallsBackOnlyWhenMissing() {
        val body = """{"contents":[{"shortsLockupViewModel":{"onTap":{"innertubeCommand":{"reelWatchEndpoint":{"videoId":"abcdefghijk"}}},"contentImage":{"thumbnailViewModel":{"image":{"sources":[{"url":"https://i.ytimg.com/vi/abcdefghijk/oar2.jpg"}]}}}}},{"shortsLockupViewModel":{"onTap":{"innertubeCommand":{"reelWatchEndpoint":{"videoId":"12345678901"}}}}}]}"""
        val items = searchPage(body).items
        assertEquals("https://i.ytimg.com/vi/abcdefghijk/oar2.jpg", items[0].thumbnail)
        assertEquals("https://i.ytimg.com/vi/12345678901/hqdefault.jpg", items[1].thumbnail)
    }
    @Test fun readsTileMetadataLinesAndDuration() {
        val body = """{"contents":[{"tileRenderer":{"onSelectCommand":{"watchEndpoint":{"videoId":"video"}},"header":{"tileHeaderRenderer":{"thumbnail":{"thumbnails":[{"url":"https://image"}]},"thumbnailOverlays":[{"thumbnailOverlayTimeStatusRenderer":{"text":{"simpleText":"3:42"}}}]}},"metadata":{"tileMetadataRenderer":{"title":{"simpleText":"A song"},"lines":[{"lineRenderer":{"items":[{"lineItemRenderer":{"text":{"runs":[{"text":"Artist","navigationEndpoint":{"browseEndpoint":{"browseId":"UCartist"}}}]}}}]}},{"lineRenderer":{"items":[{"lineItemRenderer":{"text":{"simpleText":"12M views"}}},{"lineItemRenderer":{"text":{"simpleText":"•"}}},{"lineItemRenderer":{"text":{"simpleText":"2 days ago"}}}]}}]}}}}]}"""
        val item = searchPage(body).items.single()
        assertEquals("A song", item.title)
        assertEquals("Artist", item.author)
        assertEquals("UCartist", item.channelId)
        assertEquals("12M views", item.viewCount)
        assertEquals("2 days ago", item.published)
        assertEquals("3:42", item.duration)
        assertEquals("https://image", item.thumbnail)
    }

    @Test fun readsModernLockupsAndKeepsTheirActions() {
        val body = """{"contents":[{"lockupViewModel":{"contentId":"v","metadata":{"lockupMetadataViewModel":{"title":{"content":"Modern"},"menuButton":{"buttonViewModel":{"title":"Hide","onTap":{"innertubeCommand":{"feedbackEndpoint":{"feedbackToken":"opaque"}}}}}}},"contentImage":{"thumbnailViewModel":{"image":{"sources":[{"url":"https://image"}]}}},"rendererContext":{"commandContext":{"onTap":{"innertubeCommand":{"watchEndpoint":{"videoId":"v","startTimeSeconds":12,"playerParams":"player"}}}}}}},{"shortsLockupViewModel":{"overlayMetadata":{"primaryText":{"content":"Short"}},"onTap":{"innertubeCommand":{"reelWatchEndpoint":{"videoId":"short"}}}}},{"tileRenderer":{"onSelectCommand":{"browseEndpoint":{"browseId":"UCchannel","params":"tab"}}}}]}"""
        val items = searchPage(body).items
        assertEquals(listOf("video", "short", "channel"), items.map { it.kind })
        assertEquals("Modern", items[0].title)
        assertEquals("https://image", items[0].thumbnail)
        assertEquals(12L, items[0].startSeconds)
        assertEquals("player", items[0].playerParams)
        val action = items[0].actions.single()
        assertEquals("feedbackEndpoint", action.endpoint.name)
        assertEquals("opaque", jsonObject(action.endpoint.payload)!!.text("feedbackToken"))
        assertEquals("tab", items[2].params)
    }

    @Test fun keepsMixedGroupOrderWithoutRepeatingNestedContainers() {
        val body = """{"contents":[{"richShelfRenderer":{"title":"First","contents":[{"videoRenderer":{"videoId":"a"}}]}},{"shelfRenderer":{"title":"Second","contents":[{"videoRenderer":{"videoId":"b"}}]}},{"itemSectionRenderer":{"contents":[{"shelfRenderer":{"title":"Third","contents":[{"videoRenderer":{"videoId":"c"}}]}}]}}]}"""
        assertEquals(listOf("First", "Second", "Third"), searchPage(body).groups.map { it.title })
    }

    @Test fun keepsPlaylistIdentityWhenALockupAlsoHasAFirstVideo() {
        val body = """{"lockupViewModel":{"contentId":"PLlist","contentType":"LOCKUP_CONTENT_TYPE_PLAYLIST","rendererContext":{"commandContext":{"onTap":{"innertubeCommand":{"watchEndpoint":{"videoId":"first","playlistId":"PLlist"}}}}}}}"""
        val item = searchPage(body).items.single()
        assertEquals("playlist", item.kind)
        assertEquals("PLlist", item.id)
        assertEquals("PLlist", item.playlistId)
    }
}
