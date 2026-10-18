package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class ContentReadTest {
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
}
