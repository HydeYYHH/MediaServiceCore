package com.liskovsoft.youtubeapi.videoinfo.V2
import org.junit.Assert.*
import org.junit.Test
class PlayerDetailsTest {
    @Test fun retainsManifestsRangesDetailsAndExpiry() {
        val a = playerAnswer("""{"playabilityStatus":{"status":"OK"},"streamingData":{"expiresInSeconds":"60","dashManifestUrl":"https://dash","serverAbrStreamingUrl":"https://sabr","adaptiveFormats":[{"itag":137,"url":"https://v","width":1920,"contentLength":"9999999999","initRange":{"start":"0","end":"100"},"indexRange":{"start":"101","end":"200"}}]},"videoDetails":{"videoId":"v","title":"Title","lengthSeconds":"123","viewCount":"9999999999"},"playerConfig":{"mediaCommonConfig":{"mediaUstreamerRequestConfig":{"videoPlaybackUstreamerConfig":"config"}}}}""")
        assertEquals("https://dash", a.dashUrl); assertEquals("config", a.ustreamerConfig)
        assertEquals(60L, a.expiresInSeconds); assertEquals(123L, a.details!!.durationSeconds)
        assertEquals(9999999999L, a.adaptiveFormats.single().contentLength)
        assertEquals(PlayerRange(0, 100), a.adaptiveFormats.single().initRange)
        val result = PlayerAttempt(PlayerClientQueues()).run(SignIn.SIGNED_OUT) { a }
        assertNotNull(result.client)
        assertNull(playerAnswer("{}").details)
    }
    @Test fun detailsCarryThePlaybackTrackingUrls() {
        val body = """{"videoDetails":{"videoId":"v","title":"T"},"playbackTracking":{"videostatsPlaybackUrl":{"baseUrl":"https://www.youtube.com/api/stats/playback?ei=e"},"videostatsWatchtimeUrl":{"baseUrl":"https://www.youtube.com/api/stats/watchtime?ei=e"}}}"""
        val details = readPlayerDetails(jsonObject(body)!!)!!
        assertEquals("https://www.youtube.com/api/stats/playback?ei=e", details.tracking?.playbackUrl)
        assertEquals("https://www.youtube.com/api/stats/watchtime?ei=e", details.tracking?.watchTimeUrl)
        assertEquals(playerTracking(body), details.tracking)
        assertNull(readPlayerDetails(jsonObject("""{"videoDetails":{"videoId":"v"}}""")!!)!!.tracking?.playbackUrl)
    }
    @Test fun liveNowIsDistinctFromEverLiveContent() {
        val active = readPlayerDetails(jsonObject("""{"videoDetails":{"videoId":"v","title":"T","isLive":true,"isLiveContent":true}}""")!!)!!
        assertTrue(active.live); assertTrue(active.liveContent)
        val ended = readPlayerDetails(jsonObject("""{"videoDetails":{"videoId":"v","title":"T","isLiveContent":true}}""")!!)!!
        assertFalse(ended.live); assertTrue(ended.liveContent)
        val viaPlayability = readPlayerDetails(jsonObject("""{"playabilityStatus":{"liveStreamability":{}},"videoDetails":{"videoId":"v","title":"T"}}""")!!)!!
        assertTrue(viaPlayability.live)
        val ordinary = readPlayerDetails(jsonObject("""{"videoDetails":{"videoId":"v","title":"T"}}""")!!)!!
        assertFalse(ordinary.live); assertFalse(ordinary.liveContent)
    }
}
