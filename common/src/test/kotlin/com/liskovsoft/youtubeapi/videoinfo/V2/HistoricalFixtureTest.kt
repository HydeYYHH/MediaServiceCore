package com.liskovsoft.youtubeapi.videoinfo.V2
import org.junit.Assert.*
import org.junit.Test
class HistoricalFixtureTest {
    private fun fixture(name:String)=javaClass.getResourceAsStream("/fixtures/$name")!!.bufferedReader(Charsets.UTF_8).use{it.readText()}
    @Test fun readsTheHistoricalTvHomeTiles() {
        val page=browsePage(fixture("tv-home.json"))
        assertEquals(2,page.items.size);assertTrue(page.items.all{!it.title.isNullOrBlank() && !it.thumbnail.isNullOrBlank()})
    }
    @Test fun readsTheHistoricalWebSearch() {
        val page=searchPage(fixture("web-search.json"))
        assertEquals(2,page.items.size);assertTrue(page.items.all{!it.title.isNullOrBlank()})
    }
    @Test fun readsTheHistoricalComments() {
        val page=commentPage(fixture("comments.json"))
        assertEquals(2,page.comments.size);assertTrue(page.comments.all{!it.id.isNullOrBlank() && !it.text.isNullOrBlank()})
    }
}
