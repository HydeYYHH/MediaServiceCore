package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class ContentReadTest {
    @Test fun webSearchMetadataBadgesIdentifyLiveVideosWithoutDisplayLabels() {
        // Badge metadata may include accessibility and style fields.
        val body = """{"contents":[
            {"videoRenderer":{"videoId":"paired","badges":[
                {"metadataBadgeRenderer":{"style":"BADGE_STYLE_TYPE_SIMPLE","icon":{"iconType":"OUTLINE_BAG"}}},
                {"metadataBadgeRenderer":{"style":"BADGE_STYLE_TYPE_LIVE_NOW","icon":{"iconType":"LIVE"}}}],
                "ownerBadges":[{"metadataBadgeRenderer":{"style":"BADGE_STYLE_TYPE_VERIFIED_ARTIST","icon":{"iconType":"AUDIO_BADGE"}}}]}},
            {"videoRenderer":{"videoId":"style","badges":[{"metadataBadgeRenderer":{"style":"BADGE_STYLE_TYPE_LIVE_NOW"}}]}},
            {"videoRenderer":{"videoId":"icon","badges":[{"metadataBadgeRenderer":{"icon":{"iconType":"LIVE"}}}]}}
        ]}"""
        val http = object : HttpClient { override fun execute(request: HttpRequest) = HttpReply(200, searchPrimaryFixture(body)) }
        val page = loadSearch("fixture", http = http).value!!
        assertEquals(listOf("paired", "style", "icon"), page.items.map { it.id })
        assertTrue(page.items.all { it.live })
        assertTrue(page.items.all { it.duration == null })
    }

    @Test fun ordinaryVideosAndOwnerVerificationDoNotBecomeLiveFromLabelsOrSearchFilters() {
        val page = searchPage("""{"contents":[
            {"videoRenderer":{"videoId":"ordinary","title":{"simpleText":"LIVE"},"lengthText":{"simpleText":"3:42"}}},
            {"videoRenderer":{"videoId":"verified","ownerBadges":[{"metadataBadgeRenderer":{
                "style":"BADGE_STYLE_TYPE_VERIFIED","icon":{"iconType":"CHECK_CIRCLE_THICK"},"tooltip":"LIVE"}}]}},
            {"videoRenderer":{"videoId":"artist","ownerBadges":[{"metadataBadgeRenderer":{
                "style":"BADGE_STYLE_TYPE_VERIFIED_ARTIST","icon":{"iconType":"AUDIO_BADGE"}}}]}},
            {"videoRenderer":{"videoId":"label","badges":[{"metadataBadgeRenderer":{
                "style":"BADGE_STYLE_TYPE_SIMPLE","label":"LIVE","icon":{"iconType":"OUTLINE_BAG"}}}]}},
            {"searchFilterGroupRenderer":{"title":{"simpleText":"Features"},"filters":[{"searchFilterRenderer":{
                "label":{"simpleText":"Live"},"status":"FILTER_STATUS_SELECTED"}}]}}
        ]}""")
        assertEquals(4, page.items.size)
        assertEquals("Live", page.filters.single().title)
        assertTrue(page.filters.single().selected)
        assertTrue(page.items.none { it.live })
        assertEquals("3:42", page.items.first().duration)
    }

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
