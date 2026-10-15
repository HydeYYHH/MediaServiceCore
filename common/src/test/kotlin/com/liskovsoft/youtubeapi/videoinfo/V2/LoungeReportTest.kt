package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoungeReportTest {
    @Test
    fun reportsNowPlaying() {
        val query = nowPlayingQuery(
            "Living Room",
            "device-1",
            "tok",
            "sid-1",
            "gs-1",
            "dQw4w9WgXcQ",
            1500L,
            10000L,
            1,
            null,
            null,
            null,
            0
        )
        assertTrue(query.url.contains("RID=1337"))
        assertTrue(query.url.contains("SID=sid-1"))
        assertTrue(query.url.contains("gsessionid=gs-1"))
        assertEquals("application/x-www-form-urlencoded", query.headers["Content-Type"])
        assertFalse(query.headers.containsKey("X-Youtube-Client-Name"))
        assertTrue(query.body.contains("count=1"))
        assertTrue(query.body.contains("ofs=0"))
        assertTrue(query.body.contains("req0__sc=nowPlaying"))
        assertTrue(query.body.contains("req0_currentTime=1.5"))
    }

    @Test
    fun reportsStateUnlessDurationMissing() {
        val query = stateQuery("Living Room", "device-1", "tok", "sid-1", "gs-1", 1500L, 10000L, 1, 0)
        assertTrue(query.body.contains("req0__sc=onStateChange"))
        assertTrue(query.body.contains("req0_cpn=foo"))
        var called = false
        val sent = postState("Living Room", "device-1", "tok", "sid-1", "gs-1", 0L, 0L, 1, 0) {
            called = true
            Pair(200, "")
        }
        assertFalse(called)
        assertFalse(sent)
    }

    @Test
    fun reportsVolumeUnlessUnknown() {
        val query = volumeQuery("Living Room", "device-1", "tok", "sid-1", "gs-1", 40, 0)
        assertTrue(query.body.contains("req0__sc=onVolumeChanged"))
        assertTrue(query.body.contains("req0_muted=false"))
        var called = false
        val sent = postVolume("Living Room", "device-1", "tok", "sid-1", "gs-1", -1, 0) {
            called = true
            Pair(200, "")
        }
        assertFalse(called)
        assertFalse(sent)
    }

    @Test
    fun blankSessionSkipsPost() {
        var called = false
        val sent = postNowPlaying(
            "Living Room",
            "device-1",
            "tok",
            " \n",
            "gs-1",
            "dQw4w9WgXcQ",
            0L,
            1000L,
            1,
            null,
            null,
            null,
            0
        ) {
            called = true
            Pair(200, "")
        }
        assertFalse(called)
        assertFalse(sent)
    }

    @Test
    fun reportsSubtitles() {
        val query = subtitleQuery("Living Room", "device-1", "tok", "sid-1", "gs-1", "a.en", "en", 0)
        assertTrue(query.body.contains("req0__sc=onSubtitlesTrackChanged"))
        assertTrue(query.body.contains("req0_vss_id=a.en"))
        assertTrue(query.body.contains("req0_languageCode=en"))
        assertTrue(query.body.contains("req0_sourceLanguageCode=en"))
        var called = false
        val sent = postSubtitle("Living Room", "device-1", "tok", "sid-1", "gs-1", " \n", "en", 0) { posted ->
            called = true
            assertTrue(posted.body.contains("onSubtitlesTrackChanged"))
            assertFalse(posted.body.contains("vss_id"))
            assertFalse(posted.body.contains("languageCode"))
            Pair(200, "")
        }
        assertTrue(called)
        assertTrue(sent)
    }

    @Test
    fun reportsPreviousNext() {
        val query = previousNextQuery("Living Room", "device-1", "tok", "sid-1", "gs-1", 0)
        assertTrue(query.body.contains("req0__sc=onHasPreviousNextChanged"))
        assertTrue(query.body.contains("req0_hasPrevious=false"))
        assertTrue(query.body.contains("req0_hasNext=false"))
        assertTrue(query.body.contains("req0_loadedTime=0"))
        assertFalse(query.body.contains("currentTime"))
    }

    @Test
    fun blankSessionSkipsSubtitle() {
        var called = false
        val sent = postSubtitle("Living Room", "device-1", "tok", " \n", "gs-1", "a.en", "en", 0) {
            called = true
            Pair(200, "")
        }
        assertFalse(called)
        assertFalse(sent)
    }

    @Test
    fun reportsQuality() {
        val query = qualityQuery(
            "Living Room",
            "device-1",
            "tok",
            "sid-1",
            "gs-1",
            "dQw4w9WgXcQ",
            "360",
            listOf("720p", "1080P"),
            0
        )
        assertTrue(query.body.contains("req0__sc=onVideoQualityChanged"))
        assertTrue(query.body.contains("req0_qualityLevel=360"))
        assertTrue(query.body.contains("req0_availableQualityLevels=720%2C1080"))
        assertFalse(query.body.contains("loadedTime"))
    }

    @Test
    fun reportsDiscovery() {
        val query = discoveryQuery(
            "Living Room",
            "device-1",
            "tok",
            "sid-1",
            "gs-1",
            "d6e51266-0538-44a8-8cc7-0687e27cb89c",
            0
        )
        assertTrue(query.body.contains("req0__sc=setDiscoveryDeviceId"))
        assertTrue(query.body.contains("req0_discoveryDeviceId=d6e51266-0538-44a8-8cc7-0687e27cb89c"))
        assertTrue(query.body.contains("req0_loungeDeviceId=d6e51266-0538-44a8-8cc7-0687e27cb89c"))
        var called = false
        val sent = postDiscovery("Living Room", "device-1", "tok", "sid-1", "gs-1", " \n", 0) {
            called = true
            Pair(200, "")
        }
        assertFalse(called)
        assertFalse(sent)
    }

    @Test
    fun blankSessionSkipsQuality() {
        var called = false
        val sent = postQuality(
            "Living Room",
            "device-1",
            "tok",
            " \n",
            "gs-1",
            "dQw4w9WgXcQ",
            "360",
            listOf("720p"),
            0
        ) {
            called = true
            Pair(200, "")
        }
        assertFalse(called)
        assertFalse(sent)
    }
}
