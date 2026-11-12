package com.liskovsoft.youtubeapi.videoinfo.V2

import java.io.StringReader
import org.junit.Assert.*
import org.junit.Test

class LoungeConsumerContractTest {
    @Test fun consumesQueueRemoteInputAndSubtitleCommandsWithoutLosingTypedFields() {
        val commands = loungeCommands("""[[1,["setPlaylist",{"videoId":"v","listId":"PL","currentIndex":"2","currentTime":"12.5","ctt":"ct"}]],
            [2,["setVolume",{"volume":44,"delta":-2}]],
            [3,["remoteConnected",{"id":"remote","name":"Living room"}]],
            [4,["dpadCommand",{"key":"ENTER"}]],
            [5,["voiceCommand",{"status":"INITIATED"}]],
            [6,["setSubtitlesTrack",{"videoId":"v","languageCode":"zh"}]]]""")
        val queue = commands.first()
        assertEquals(listOf("v", "PL", "2", "12.5", "ct"),
            listOf(queue.videoId, queue.playlistId, queue.index.toString(), queue.seconds, queue.ctt))
        assertEquals(44, commands[1].volume); assertEquals(-2, commands[1].delta)
        assertEquals("remote", commands[2].deviceId); assertEquals("Living room", commands[2].deviceName)
        assertEquals("ENTER", commands[3].key); assertEquals(true, commands[4].voiceStarted)
        assertEquals("zh", commands[5].languageCode)
        assertNull(loungeCommands("""[[0,["setVolume",{"delta":"1.5"}]]]""").single().delta)
    }

    @Test fun reportsRemoteCapabilitiesAndFailuresWithOrderedSequenceNumbers() {
        val requests = mutableListOf<HttpRequest>()
        val http = object : HttpClient { override fun execute(request: HttpRequest): HttpReply {
            requests += request
            return if (requests.size == 5) HttpReply(503, "") else HttpReply(200, "")
        } }
        val identity = LoungeIdentity("screen", "device", LoungeScreen("s", "secret"),
            LoungeSession("session", "g-session", "url"), 12, 100)
        assertEquals(100, identity.requestId)
        assertFalse(identity.toString().contains("secret"))
        val reporter = LoungeReporter({ identity }, http, nonce = { "cpn" })
        assertTrue(reporter.volume(30).ok)
        assertTrue(reporter.subtitle(".zh", "zh").ok)
        assertTrue(reporter.quality("v", "hd720", listOf("auto", "hd720")).ok)
        assertTrue(reporter.discovery("discovery").ok)
        assertEquals(503, reporter.volume(50).error?.status)
        assertEquals(listOf("onVolumeChanged", "onSubtitlesTrackChanged", "onVideoQualityChanged", "setDiscoveryDeviceId", "onVolumeChanged"),
            requests.map { urlParameter("x?" + it.body, "req0__sc") })
        assertEquals((0..4).map(Int::toString), requests.map { urlParameter("x?" + it.body, "ofs") })
        assertEquals("zh", urlParameter("x?" + requests[1].body, "req0_languageCode"))
        assertEquals("hd720", urlParameter("x?" + requests[2].body, "req0_qualityLevel"))
        assertTrue(postPreviousNext("s", "d", "t", "sid", "gsid", 2) { query ->
            assertEquals("false", urlParameter("x?" + query.body, "req0_hasNext")); 200 to ""
        })
        val connection = LoungeConnection("s", "d", { LoungeScreen("s", "t") }, http,
            object : LoungeTransport { override fun open(request: HttpRequest) = StringReader("") })
        assertEquals("lounge_not_connected", LoungeReporter(connection, http).discovery("d").error?.code)
        connection.close()
    }
}
