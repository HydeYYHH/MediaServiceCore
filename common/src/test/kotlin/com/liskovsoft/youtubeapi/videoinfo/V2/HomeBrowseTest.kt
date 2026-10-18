package com.liskovsoft.youtubeapi.videoinfo.V2
import org.junit.Assert.*
import org.junit.Test
class HomeBrowseTest {
    @Test fun usesTheUpstreamCategoryEndpointsAndContinuesInTheSameLane() {
        assertEquals("FEtopics_music", jsonObject(homeQuery(HomeSection.MUSIC).body)!!.text("browseId"))
        assertEquals("default", jsonObject(homeQuery(context = RequestContext(accessToken = "a")).body)!!.text("browseId"))
        assertEquals("next", jsonObject(homeQuery(continuation = "next").body)!!.text("continuation"))
        val shorts = homeQuery(HomeSection.SHORTS, continuation = "seq")
        assertTrue(shorts.url.endsWith("reel/reel_watch_sequence")); assertEquals("seq", jsonObject(shorts.body)!!.text("sequenceParams"))
    }
}
