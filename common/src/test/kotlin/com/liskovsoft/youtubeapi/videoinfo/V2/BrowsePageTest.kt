package com.liskovsoft.youtubeapi.videoinfo.V2
import org.junit.Assert.*
import org.junit.Test
class BrowsePageTest {
    @Test fun keepsTheServersEmptyFeedMessage() {
        val page=browsePage("""{"contents":{"richGridRenderer":{"header":{"feedNudgeRenderer":{"title":{"runs":[{"text":"Try searching to get started"}]}}}}}}""")
        assertTrue(page.items.isEmpty())
        assertEquals(listOf("Try searching to get started"),page.messages)
    }
    @Test fun readsOnlyTheSelectedTabsItemsAndKeepsOtherEndpoints() {
        val p = browsePage("""{"tabs":[{"tabRenderer":{"title":"Videos","selected":true,"endpoint":{"browseEndpoint":{"browseId":"UC1","params":"videos"}},"content":{"shelfRenderer":{"title":{"simpleText":"Uploads"},"items":[{"gridVideoRenderer":{"videoId":"v"}}],"continuations":[{"nextContinuationData":{"continuation":"next"}}]}}}},{"tabRenderer":{"title":"Live","content":{"videoRenderer":{"videoId":"hidden"}}}}]}""")
        assertEquals(listOf("v"), p.items.map { it.id }); assertEquals("Uploads", p.groups.single().title)
        assertEquals("next", p.nextKey); assertEquals("videos", p.tabs.first().params)
    }
    @Test fun continuationDoesNotRepeatTheInitialBrowseId() {
        val q = browseQuery("home", continuation = "next")
        assertEquals("next", jsonObject(q.body)!!.text("continuation")); assertNull(jsonObject(q.body)!!.text("browseId"))
    }
    @Test fun tvShelfTitlesComeFromTheShelfHeaderRenderer() {
        val p = browsePage("""{"contents":{"sectionListRenderer":{"contents":[{"shelfRenderer":{"headerRenderer":{"shelfHeaderRenderer":{"avatarLockup":{"avatarLockupRenderer":{"title":{"runs":[{"text":"Gameplay for you to unwind"}]}}}}},"content":{"horizontalListRenderer":{"items":[{"tileRenderer":{"contentId":"v1","onTap":{"innertubeCommand":{"watchEndpoint":{"videoId":"v1"}}},"metadata":{"tileMetadataRenderer":{"title":{"simpleText":"Tile One"},"lines":[{"lineRenderer":{"items":[{"lineItemRenderer":{"text":{"runs":[{"text":"Author"}]}}}]}}]}}}}]}}}},{"shelfRenderer":{"title":{"runs":[{"text":"Plain Shelf"}]},"content":{"horizontalListRenderer":{"items":[{"tileRenderer":{"contentId":"v2","onTap":{"innertubeCommand":{"watchEndpoint":{"videoId":"v2"}}},"metadata":{"tileMetadataRenderer":{"title":{"simpleText":"Tile Two"},"lines":[{"lineRenderer":{"items":[{"lineItemRenderer":{"text":{"runs":[{"text":"Author2"}]}}}]}}]}}}}]}}}}]}}}""")
        val groups = p.groups
        assertEquals(listOf("Gameplay for you to unwind", "Plain Shelf"), groups.map { it.title })
        assertEquals(listOf("v1", "v2"), groups.flatMap { it.items.map { i -> i.id } })
    }
    @Test fun entityMetadataRendererRowsFeedItemMetadata() {
        val p = browsePage("""{"contents":{"sectionListRenderer":{"contents":[{"shelfRenderer":{"title":{"runs":[{"text":"Shelf"}]},"content":{"horizontalListRenderer":{"items":[{"lockupViewModel":{"contentId":"v3","onSelectCommand":{"innertubeCommand":{"watchEndpoint":{"videoId":"v3"}}},"entityMetadataRenderer":{"metadataRows":[{"metadataParts":[{"text":{"content":"Entity Author"},"navigationEndpoint":{"browseEndpoint":{"browseId":"UCowner"}}}]},{"metadataParts":[{"text":{"content":"1.2M views"}},{"text":{"content":"2 days ago"}}]}]}}}]}}}}]}}}""")
        val item = p.items.single()
        assertEquals("video", item.kind); assertEquals("v3", item.id)
        assertEquals("Entity Author", item.author); assertEquals("1.2M views", item.viewCount); assertEquals("2 days ago", item.published)
    }
}
