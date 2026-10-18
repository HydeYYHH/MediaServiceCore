package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class PlaybackSourceTest {
    private val http = object : HttpClient {
        override fun execute(request: HttpRequest) = HttpReply(200,
            "{\"streamingData\":{\"formats\":[{\"url\":\"https://media/low\",\"height\":360},{\"url\":\"https://media/high\",\"height\":1080}]}}")
    }

    @Test fun attributesChildRequestsAndKeepsRecoveryPreferences() {
        val session = PlaybackSession(http)
        val first = session.load("v", preference = PlaybackPreference(maxHeight = 480)).value!!
        val registered = session.registerRequest(first, first.playback.videoUrl!!, "https://cdn/segment").value!!
        assertTrue("https://cdn/segment" in registered.source!!.urls)
        val recovered = PlaybackRecovery(session).recover(first, "https://cdn/segment", 403).value!!
        assertEquals("https://media/low", recovered.playback.videoUrl)
        assertEquals(480, recovered.source!!.preference.maxHeight)
    }

    @Test fun rejectsUnknownParentsAndForgedSources() {
        val session = PlaybackSession(http)
        val first = session.load("v").value!!
        assertEquals("unknown_parent_request", session.registerRequest(first, "https://other", "https://cdn/x").error!!.code)
        val forged = first.copy(source = first.source!!.copy(sequence = 999))
        assertEquals("unknown_source", PlaybackRecovery(session).recover(forged, first.playback.videoUrl!!, 403).error!!.code)
        assertEquals("invalid_media_url", session.registerRequest(first, first.playback.videoUrl!!, "file:///x").error!!.code)
        val other = PlaybackSession(http)
        other.load("v")
        assertEquals("unknown_source", other.registerRequest(first, first.playback.videoUrl!!, "https://cdn/x").error!!.code)
    }
}
