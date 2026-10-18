package com.liskovsoft.youtubeapi.videoinfo.V2
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in public reads. No credentials, account mutation, or media-playback claim. */
class OnlineReadTest {
    @Test fun youtubeSearch() {
        assumeTrue(java.lang.Boolean.getBoolean("msc.online"))
        val result=loadSearch("Kotlin programming",http=JvmHttpClient())
        assertTrue(result.error.toString(),result.ok)
        assertTrue("Search returned no readable items",result.value!!.items.isNotEmpty())
    }
    @Test fun youtubeHome() {
        assumeTrue(java.lang.Boolean.getBoolean("msc.online"))
        val result=loadHome(http=JvmHttpClient())
        assertTrue(result.error.toString(),result.ok)
        val page=result.value!!
        assertTrue("Home returned neither items nor an empty-state message",page.items.isNotEmpty() || page.messages.isNotEmpty())
    }
    @Test fun youtubeOembed() {
        assumeTrue(java.lang.Boolean.getBoolean("msc.online"))
        val url=urlWithParameter("https://www.youtube.com/oembed?format=json","url","https://www.youtube.com/watch?v=jNQXAC9IVRw")
        val response=requestBody(HttpRequest("GET",url),JvmHttpClient())
        assertTrue(response.error.toString(),response.ok)
        assertFalse(jsonObject(response.value!!)!!.text("title").isNullOrBlank())
    }
    @Test fun sponsorBlockRead() {
        assumeTrue(java.lang.Boolean.getBoolean("msc.online"))
        val result=loadSponsorSegments("jNQXAC9IVRw")
        assertTrue(result.error.toString(),result.ok)
    }
    @Test fun dislikeRead() {
        assumeTrue(java.lang.Boolean.getBoolean("msc.online"))
        val result=loadDislikes("jNQXAC9IVRw")
        assertTrue(result.error.toString(),result.ok)
        assertEquals("jNQXAC9IVRw",result.value!!.videoId)
    }
}
