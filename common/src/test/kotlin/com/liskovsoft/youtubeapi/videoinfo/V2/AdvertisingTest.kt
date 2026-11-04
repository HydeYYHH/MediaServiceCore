package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class AdvertisingTest {
    @Test fun browseSkipsAdvertisingSubtreesButKeepsOrganicVideos() {
        val page = searchPage("""{"contents":[
            {"adSlotRenderer":{"content":{"videoRenderer":{"videoId":"advertisement","title":{"simpleText":"Promotion"}}}}},
            {"richItemRenderer":{"content":{"videoRenderer":{"videoId":"organic","title":{"simpleText":"Sponsored science explained"}}}}},
            {"videoRenderer":{"videoId":"direct-ad","adMetadata":{}}}
        ]}""")
        assertEquals(listOf("organic"), page.items.map { it.id })
        assertTrue(page.groups.isEmpty())
    }

    @Test fun reelAdsAreRemovedWithoutLosingTheSequenceCursor() {
        val page = shortsPage("""{"entries":[
            {"isAd":true,"command":{"reelWatchEndpoint":{"videoId":"ad-one"}}},
            {"command":{"reelWatchEndpoint":{"videoId":"ad-two","adClientParams":"opaque"}}},
            {"command":{"reelWatchEndpoint":{"videoId":"organic"}}}
        ],"sequenceContinuation":"next"}""")
        assertEquals(listOf("organic"), page.items.map { it.id })
        assertEquals("next", page.nextKey)
    }

    @Test fun guideKeepsUnreadContentSignal() {
        val http = object : HttpClient {
            override fun execute(request: HttpRequest) = HttpReply(200, """{"items":[
                {"guideEntryRenderer":{"title":{"simpleText":"Channel"},"navigationEndpoint":{"browseEndpoint":{"browseId":"UCnew"}},"isNewContent":true}},
                {"guideEntryRenderer":{"navigationEndpoint":{"browseEndpoint":{"browseId":"UCread"}},"isNewContent":false}}
            ]}""")
        }
        val channels = loadSubscribedChannels(RequestContext(accessToken = "test"), http).value.orEmpty()
        assertEquals(listOf(true, false), channels.map { it.hasNewContent })
    }
}
