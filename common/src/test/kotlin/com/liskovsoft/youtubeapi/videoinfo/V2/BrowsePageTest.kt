package com.liskovsoft.youtubeapi.videoinfo.V2
import org.junit.Assert.*
import org.junit.Test
class BrowsePageTest {
    @Test fun readsOnlyTheSelectedTabsItemsAndKeepsOtherEndpoints() {
        val p = browsePage("""{"tabs":[{"tabRenderer":{"title":"Videos","selected":true,"endpoint":{"browseEndpoint":{"browseId":"UC1","params":"videos"}},"content":{"shelfRenderer":{"title":{"simpleText":"Uploads"},"items":[{"gridVideoRenderer":{"videoId":"v"}}],"continuations":[{"nextContinuationData":{"continuation":"next"}}]}}}},{"tabRenderer":{"title":"Live","content":{"videoRenderer":{"videoId":"hidden"}}}}]}""")
        assertEquals(listOf("v"), p.items.map { it.id }); assertEquals("Uploads", p.groups.single().title)
        assertEquals("next", p.nextKey); assertEquals("videos", p.tabs.first().params)
    }
    @Test fun continuationDoesNotRepeatTheInitialBrowseId() {
        val q = browseQuery("home", continuation = "next")
        assertEquals("next", jsonObject(q.body)!!.text("continuation")); assertNull(jsonObject(q.body)!!.text("browseId"))
    }
}
