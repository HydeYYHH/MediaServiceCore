package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerAttemptTest {
    private val queues = PlayerClientQueues()
    private val walk = PlayerAttempt(queues)

    @Test
    fun classifiesAnswers() {
        assertEquals(ClientOutcome.SOFT, clientOutcome(true, "OK", null, true, true, true, false))
        assertEquals(ClientOutcome.SOFT, clientOutcome(false, "LOGIN_REQUIRED", "Sign in to confirm you're not a bot", false, false, false, false))
        assertEquals(ClientOutcome.SOFT, clientOutcome(false, "OK", "Unusual traffic from your network", true, false, false, false))
        assertEquals(ClientOutcome.CONTENT, clientOutcome(false, "AGE_CHECK_REQUIRED", null, false, false, false, false))
        assertEquals(ClientOutcome.CONTENT, clientOutcome(false, "LIVE_STREAM_OFFLINE", null, false, false, false, true))
        assertEquals(ClientOutcome.PLAYED, clientOutcome(false, "OK", null, true, false, false, false))
        assertEquals(ClientOutcome.PLAYED, clientOutcome(false, "OK", null, false, true, false, false))
        assertEquals(ClientOutcome.SOFT, clientOutcome(false, "OK", null, true, false, false, true))
        assertEquals(ClientOutcome.PLAYED, clientOutcome(false, "OK", null, false, false, true, true))
        assertEquals(ClientOutcome.SOFT, clientOutcome(false, "OK", null, false, false, false, false))
    }

    @Test
    fun beginUsesTheVodQueue() {
        assertEquals(
            PlayerClientQueues.defaultOrder(ClientQueueKey(SignIn.SIGNED_OUT, PlaybackForm.VOD)),
            walk.begin(SignIn.SIGNED_OUT)
        )
    }

    @Test
    fun liveSwitchSkipsClientsAlreadyTried() {
        val remaining = walk.onLive(
            SignIn.SIGNED_OUT,
            setOf(PlayerClient.WEB, PlayerClient.VISIONOS)
        )
        assertEquals(
            listOf(PlayerClient.WEB_SAFARI, PlayerClient.WEB_EMBED, PlayerClient.ANDROID_VR),
            remaining
        )
    }

    @Test
    fun liveFinishDoesNotTouchTheVodQueue() {
        val vod = ClientQueueKey(SignIn.SIGNED_OUT, PlaybackForm.VOD)
        val live = ClientQueueKey(SignIn.SIGNED_OUT, PlaybackForm.LIVE)
        walk.finish(live, listOf(
            PlayerClient.WEB to ClientOutcome.SOFT,
            PlayerClient.WEB_SAFARI to ClientOutcome.PLAYED
        ))
        assertEquals(PlayerClient.WEB_SAFARI, queues.order(live).first())
        assertEquals(PlayerClientQueues.defaultOrder(vod), queues.order(vod))
    }

    @Test
    fun contentRefusalFinishLeavesTheQueue() {
        val vod = ClientQueueKey(SignIn.SIGNED_IN, PlaybackForm.VOD)
        walk.finish(vod, listOf(
            PlayerClient.VISIONOS to ClientOutcome.CONTENT,
            PlayerClient.WEB_EMBED to ClientOutcome.PLAYED
        ))
        assertEquals(PlayerClientQueues.defaultOrder(vod), queues.order(vod))
    }
}
