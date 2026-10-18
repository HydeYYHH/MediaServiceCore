package com.liskovsoft.youtubeapi.videoinfo.V2
import org.junit.Assert.*
import org.junit.Test
class PlaybackRecoveryTest {
    @Test fun refreshesOnceThenUsesADifferentClientAndStops() {
        val clients = ArrayList<String>()
        val http = object : HttpClient { override fun execute(request: HttpRequest): HttpReply {
            val client = jsonObject(request.body)!!.path("context", "client")!!.text("clientName")!!
            clients.add(client); return HttpReply(200, "{\"streamingData\":{\"formats\":[{\"url\":\"https://media/$client\"}]}}")
        }}
        val session = PlaybackSession(http); val recovery = PlaybackRecovery(session)
        val first = session.load("v").value!!
        assertEquals("unknown_request", recovery.recover(first, "https://unrelated", 403).error!!.code)
        val refreshed = recovery.recover(first, first.playback.videoUrl!!, 403).value!!
        assertEquals(first.client, refreshed.client)
        val fallback = recovery.recover(refreshed, refreshed.playback.videoUrl!!, transportFailed = true).value!!
        assertNotEquals(first.client, fallback.client)
        assertEquals("recovery_exhausted", recovery.recover(fallback, fallback.playback.videoUrl!!, 403).error!!.code)
        assertEquals(3, clients.size)
    }
    @Test fun leavesOrdinaryHttpErrorsToTheCaller() {
        val session = PlaybackSession(object : HttpClient { override fun execute(request: HttpRequest) = HttpReply(500, "{}") })
        assertEquals("unknown_source", PlaybackRecovery(session).recover(PlaybackLoad(PlaybackForm.VOD, null, Playback(null,null,null)), "x", 500).error!!.code)
    }
}
