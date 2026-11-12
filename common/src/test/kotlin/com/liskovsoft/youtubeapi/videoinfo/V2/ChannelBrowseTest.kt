package com.liskovsoft.youtubeapi.videoinfo.V2
import org.junit.Assert.*
import org.junit.Test
class ChannelBrowseTest {
    @Test fun preservesMetadataAndServerTabParameters() {
        val p = channelPage("""{"metadata":{"channelMetadataRenderer":{"externalId":"UC1","title":"Name","description":"About"}},"tabs":[{"tabRenderer":{"title":"Videos","endpoint":{"browseEndpoint":{"browseId":"UC1","params":"opaque"}}}}]}""")
        assertEquals("Name", p.title); assertEquals("opaque", p.page.tabs.single().params)
        val q = channelQuery("UC1", params = p.page.tabs.single().params, search = "a\"b")
        assertEquals("a\"b", jsonObject(q.body)!!.text("query")); assertEquals("opaque", jsonObject(q.body)!!.text("params"))
        assertEquals("", channelQuery("bad/id").url)
    }

    /** The header wraps its view model under pageHeaderRenderer.content.pageHeaderViewModel. */
    @Test fun readsTheWrappedPageHeaderRendererChannelHeader() {
        val page = channelPage("""{"header":{"pageHeaderRenderer":{"pageTitle":"Wrapped","content":{"pageHeaderViewModel":{"title":{"dynamicTextViewModel":{"text":{"content":"Wrapped"}}},"image":{"decoratedAvatarViewModel":{"avatar":{"avatarViewModel":{"image":{"sources":[{"url":"https://yt3.googleusercontent.com/avatar=s120"}]}}}}},"metadata":{"contentMetadataViewModel":{"metadataRows":[{"metadataParts":[{"text":{"content":"@handle"}}]},{"metadataParts":[{"text":{"content":"2.68M subscribers"}},{"text":{"content":"6.1K videos"}}]}]}},"banner":{"imageBannerViewModel":{"image":{"sources":[{"url":"https://yt3.googleusercontent.com/banner=s170"}]}}}}}}}}""".trimIndent())
        assertEquals("Wrapped", page.title)
        assertEquals("@handle", page.handle)
        assertEquals("2.68M subscribers", page.subscriberCount)
        assertEquals("6.1K videos", page.videoCount)
        assertEquals("https://yt3.googleusercontent.com/avatar=s120", page.thumbnail)
        assertEquals("https://yt3.googleusercontent.com/banner=s170", page.banner)
    }

    @Test fun pageTitleFallsBackWhenTheViewModelIsMissing() {
        val page = channelPage("""{"header":{"pageHeaderRenderer":{"pageTitle":"OnlyTitle"}}}""")
        assertEquals("OnlyTitle", page.title)
    }

    /** Channel playlists tabs answer with lockupViewModel items; metadata rows feed the author. */
    @Test fun readsLockupViewModelPlaylistItems() {
        val page = browsePage("""{"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"title":"Playlists","selected":true,"content":{"sectionListRenderer":{"contents":[{"itemSectionRenderer":{"contents":[{"gridRenderer":{"items":[{"lockupViewModel":{"contentId":"PL1","contentType":"LOCKUP_CONTENT_TYPE_PLAYLIST","onSelectCommand":{"innertubeCommand":{"browseEndpoint":{"browseId":"PL1","params":"pp"}}},"contentImage":{"collectionThumbnailViewModel":{"primaryThumbnail":{"thumbnailViewModel":{"image":{"sources":[{"url":"https://i.ytimg.com/vi/a/webp"}]}}}}},"thumbnailBadgeViewModel":{"text":"10 videos","badgeStyle":"DEFAULT"},"metadata":{"lockupMetadataViewModel":{"title":{"content":"Playlist One"},"metadata":{"contentMetadataViewModel":{"metadataRows":[{"metadataParts":[{"text":{"content":"Owner"},"navigationEndpoint":{"browseEndpoint":{"browseId":"UCowner"}}}]},{"metadataParts":[{"text":{"content":"100K views"}}]}]}}}}}}]}}]}}]}}}}]}}}""".trimIndent())
        val item = page.items.single()
        assertEquals("playlist", item.kind); assertEquals("PL1", item.id); assertEquals("Playlist One", item.title)
        assertNull(item.playlistId); assertEquals("Owner", item.author)
        assertEquals("10 videos", item.videoCountText); assertEquals("100K views", item.viewCount)
        assertEquals("https://i.ytimg.com/vi/a/webp", item.thumbnail)
    }
}
