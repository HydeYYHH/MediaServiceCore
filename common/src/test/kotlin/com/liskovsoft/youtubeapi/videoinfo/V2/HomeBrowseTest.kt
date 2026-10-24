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
    @Test fun servesTopicSectionsWithTheTvClientBecauseWebRejectsThem() {
        for (section in listOf(HomeSection.MUSIC, HomeSection.GAMING, HomeSection.NEWS, HomeSection.SPORTS, HomeSection.LIVE, HomeSection.MOVIES, HomeSection.TRENDING)) {
            val query = homeQuery(section)
            val client = jsonObject(query.body)!!.path("context", "client")!!
            assertEquals("$section", "TVHTML5", client.text("clientName"))
            assertEquals("$section", "7", query.headers["X-Youtube-Client-Name"])
        }
        // Continuations must stay in the same client lane that issued the token.
        val next = homeQuery(HomeSection.MUSIC, continuation = "next")
        assertEquals("TVHTML5", jsonObject(next.body)!!.path("context", "client")!!.text("clientName"))
        assertEquals("next", jsonObject(next.body)!!.text("continuation"))
        val home = homeQuery(HomeSection.HOME, RequestContext(visitor = "visitor"))
        assertEquals("TVHTML5", jsonObject(home.body)!!.path("context", "client")!!.text("clientName"))
        assertEquals("default", jsonObject(home.body)!!.text("browseId"))
        assertEquals("visitor", home.headers["X-Goog-Visitor-Id"])
    }
}
