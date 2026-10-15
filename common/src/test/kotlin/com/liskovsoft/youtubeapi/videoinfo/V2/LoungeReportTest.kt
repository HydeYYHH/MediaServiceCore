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
}
