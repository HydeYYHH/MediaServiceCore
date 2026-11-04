package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerAttemptTest {
    private val queues = PlayerClientQueues()
    private val walk = PlayerAttempt(queues)

    @Test fun deferredClientsRunLastWithoutRetryingTheFailedPrefix() {
        val asked = mutableListOf<PlayerClient>()
        val result = walk.run(SignIn.SIGNED_OUT, deferred = setOf(PlayerClient.VISIONOS)) { client ->
            asked += client
            answer(hasDirectUrl = client == PlayerClient.VISIONOS)
        }
        assertEquals(PlayerClient.VISIONOS, result.client)
        assertEquals(walk.begin(SignIn.SIGNED_OUT).filter { it != PlayerClient.VISIONOS } + PlayerClient.VISIONOS, asked)
        assertEquals(asked.size, asked.distinct().size)
    }

    @Test fun exclusionsNeitherRunNorPromoteTheNextClient() {
        val asked = mutableListOf<PlayerClient>()
        walk.run(SignIn.SIGNED_OUT, excluded = setOf(PlayerClient.VISIONOS)) { client ->
            asked += client
            answer(hasDirectUrl = true)
        }
        assertEquals(listOf(PlayerClient.WEB), asked)
        assertEquals(PlayerClient.VISIONOS, walk.begin(SignIn.SIGNED_OUT).first())
    }

    @Test fun liveSwitchKeepsDeferredClientsLastAndNeverRepeatsAClient() {
        val asked = mutableListOf<PlayerClient>()
        val result = walk.run(SignIn.SIGNED_OUT, deferred = setOf(PlayerClient.WEB)) { client ->
            asked += client
            answer(live = true, hasHls = client == PlayerClient.WEB)
        }
        assertEquals(PlaybackForm.LIVE, result.form)
        assertEquals(PlayerClient.WEB, asked.last())
        assertEquals(asked.size, asked.distinct().size)
    }

    @Test fun promotedWinnerStartsTheNextVideoWithoutReplayingFailures() {
        walk.run(SignIn.SIGNED_OUT) { client -> answer(hasDirectUrl = client == PlayerClient.WEB) }
        val asked = mutableListOf<PlayerClient>()
        walk.run(SignIn.SIGNED_OUT) { client -> asked += client; answer(hasDirectUrl = true) }
        assertEquals(listOf(PlayerClient.WEB), asked)
    }

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
    fun contentRefusalKeepsWalkingAndLeavesTheVodQueue() {
        val vod = ClientQueueKey(SignIn.SIGNED_OUT, PlaybackForm.VOD)
        val asked = ArrayList<PlayerClient>()
        val result = walk.run(SignIn.SIGNED_OUT) { client ->
            asked.add(client)
            if (client == PlayerClient.VISIONOS) answer(status = "AGE_CHECK_REQUIRED")
            else answer(hasDirectUrl = true)
        }
        assertEquals(AttemptResult(PlaybackForm.VOD, PlayerClient.WEB), result)
        assertEquals(listOf(PlayerClient.VISIONOS, PlayerClient.WEB), asked)
        assertEquals(PlayerClientQueues.defaultOrder(vod), queues.order(vod))
    }

    @Test
    fun softFailurePromotesTheClientThatPlayed() {
        val vod = ClientQueueKey(SignIn.SIGNED_OUT, PlaybackForm.VOD)
        val result = walk.run(SignIn.SIGNED_OUT) { client ->
            if (client == PlayerClient.VISIONOS) answer() else answer(hasDirectUrl = true)
        }
        assertEquals(AttemptResult(PlaybackForm.VOD, PlayerClient.WEB), result)
        assertEquals(PlayerClient.WEB, queues.order(vod).first())
    }

    @Test
    fun liveSwitchLeavesTheVodQueue() {
        val vod = ClientQueueKey(SignIn.SIGNED_OUT, PlaybackForm.VOD)
        val live = ClientQueueKey(SignIn.SIGNED_OUT, PlaybackForm.LIVE)
        val asked = ArrayList<PlayerClient>()
        val result = walk.run(SignIn.SIGNED_OUT) { client ->
            asked.add(client)
            if (client == PlayerClient.VISIONOS) answer(live = true)
            else answer(hasHls = true, live = true)
        }
        assertEquals(AttemptResult(PlaybackForm.LIVE, PlayerClient.WEB), result)
        assertEquals(listOf(PlayerClient.VISIONOS, PlayerClient.WEB), asked)
        assertEquals(PlayerClientQueues.defaultOrder(vod), queues.order(vod))
        assertEquals(PlayerClient.WEB, queues.order(live).first())
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

private fun answer(
    transportFailed: Boolean = false,
    status: String? = "OK",
    reason: String? = null,
    hasDirectUrl: Boolean = false,
    hasHls: Boolean = false,
    hasMuxed: Boolean = false,
    live: Boolean = false
) = PlayerAnswer(transportFailed, status, reason, hasDirectUrl, hasHls, hasMuxed, live)
