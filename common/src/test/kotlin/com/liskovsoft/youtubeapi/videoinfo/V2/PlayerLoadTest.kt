package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerLoadTest {
    private val queues = PlayerClientQueues()
    private val walk = PlayerAttempt(queues)
    private val direct = """
        {"playabilityStatus":{"status":"OK"},
         "streamingData":{"adaptiveFormats":[{"url":"https://rr.googlevideo.com/videoplayback?id=v"}]}}
    """

    @Test
    fun ageCheckKeepsWalkingAndLeavesTheVodQueue() {
        val vod = ClientQueueKey(SignIn.SIGNED_OUT, PlaybackForm.VOD)
        val asked = ArrayList<String>()
        val load = loadPlayer(walk, SignIn.SIGNED_OUT, "dQw4w9WgXcQ", "visitor-1") { query ->
            asked.add(query.body)
            assertEquals("visitor-1", query.headers["X-Goog-Visitor-Id"])
            if (query.body.contains("\"clientName\":\"VISIONOS\"")) {
                Pair(200, """{"playabilityStatus":{"status":"AGE_CHECK_REQUIRED"}}""")
            } else {
                Pair(200, direct)
            }
        }
        assertEquals(PlaybackForm.VOD, load.form)
        assertEquals(PlayerClient.WEB, load.client)
        assertTrue(load.answer!!.hasDirectUrl)
        assertFalse(load.answer!!.transportFailed)
        assertEquals(2, asked.size)
        assertTrue(asked[0].contains("\"clientName\":\"VISIONOS\""))
        assertTrue(asked[1].contains("\"clientName\":\"WEB\""))
        assertEquals(PlayerClientQueues.defaultOrder(vod), queues.order(vod))
    }

    @Test
    fun softFailurePromotesTheClientAndReturnsItsAnswer() {
        val vod = ClientQueueKey(SignIn.SIGNED_OUT, PlaybackForm.VOD)
        val load = loadPlayer(walk, SignIn.SIGNED_OUT, "dQw4w9WgXcQ", null) { query ->
            if (query.body.contains("\"clientName\":\"VISIONOS\"")) Pair(500, "") else Pair(200, direct)
        }
        assertEquals(PlayerClient.WEB, load.client)
        assertTrue(load.answer!!.hasDirectUrl)
        assertEquals(PlayerClient.WEB, queues.order(vod).first())
    }

    @Test
    fun solvedCipherPlaysTheFirstClient() {
        val vod = ClientQueueKey(SignIn.SIGNED_OUT, PlaybackForm.VOD)
        val body = """
            {"playabilityStatus":{"status":"OK"},
             "streamingData":{"formats":[
               {"itag":18,"signatureCipher":"s=abc&sp=sig&url=https%3A%2F%2Frr.example%2Fvideoplayback%3Fid%3Dv"}
             ]}}
        """
        val load = loadPlayer(
            walk,
            SignIn.SIGNED_OUT,
            "dQw4w9WgXcQ",
            null,
            { _, _ -> PlayerChallenge(mapOf("abc" to "solved"), emptyMap()) }
        ) { Pair(200, body) }
        assertEquals(PlayerClient.VISIONOS, load.client)
        assertTrue(load.answer!!.hasMuxed)
        assertTrue(load.answer!!.muxedUrls[0].contains("sig=solved"))
        assertEquals(PlayerClientQueues.defaultOrder(vod), queues.order(vod))
    }

    @Test
    fun badVideoIdSkipsPost() {
        var called = false
        val load = loadPlayer(walk, SignIn.SIGNED_OUT, "bad id", null) {
            called = true
            Pair(200, direct)
        }
        assertFalse(called)
        assertEquals(PlaybackForm.VOD, load.form)
        assertNull(load.client)
        assertNull(load.answer)
    }
}
