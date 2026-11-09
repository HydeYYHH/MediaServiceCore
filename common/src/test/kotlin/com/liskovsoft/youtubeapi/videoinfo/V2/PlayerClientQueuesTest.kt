package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerClientQueuesTest {
    private val queues = PlayerClientQueues()
    private val vod = ClientQueueKey(SignIn.SIGNED_OUT, PlaybackForm.VOD)
    private val live = ClientQueueKey(SignIn.SIGNED_OUT, PlaybackForm.LIVE)
    private val signedInVod = ClientQueueKey(SignIn.SIGNED_IN, PlaybackForm.VOD)

    @Test
    fun defaultsMatchTheFourQueues() {
        assertEquals(
            listOf(PlayerClient.VISIONOS, PlayerClient.WEB, PlayerClient.WEB_EMBED, PlayerClient.WEB_SAFARI, PlayerClient.TV, PlayerClient.ANDROID_VR),
            queues.order(vod)
        )
        assertEquals(
            listOf(PlayerClient.WEB_SAFARI, PlayerClient.VISIONOS, PlayerClient.TV_TIZEN, PlayerClient.WEB, PlayerClient.WEB_EMBED, PlayerClient.TV, PlayerClient.ANDROID_VR),
            queues.order(signedInVod)
        )
        assertEquals(
            listOf(PlayerClient.WEB, PlayerClient.WEB_SAFARI, PlayerClient.VISIONOS, PlayerClient.WEB_EMBED, PlayerClient.ANDROID_VR),
            queues.order(live)
        )
        assertEquals(
            listOf(PlayerClient.WEB, PlayerClient.WEB_SAFARI, PlayerClient.TV, PlayerClient.WEB_EMBED, PlayerClient.ANDROID_VR),
            queues.order(ClientQueueKey(SignIn.SIGNED_IN, PlaybackForm.LIVE))
        )
    }

    @Test
    fun contentRefusalDoesNotReorder() {
        queues.commit(vod, listOf(
            PlayerClient.VISIONOS to ClientOutcome.CONTENT,
            PlayerClient.WEB_EMBED to ClientOutcome.PLAYED
        ))
        assertEquals(PlayerClientQueues.defaultOrder(vod), queues.order(vod))
    }

    @Test
    fun softFailurePromotesTheClientThatPlayed() {
        queues.commit(vod, listOf(
            PlayerClient.VISIONOS to ClientOutcome.SOFT,
            PlayerClient.WEB to ClientOutcome.CONTENT,
            PlayerClient.WEB_EMBED to ClientOutcome.PLAYED
        ))
        assertEquals(PlayerClient.WEB_EMBED, queues.order(vod).first())
        assertEquals(PlayerClientQueues.defaultOrder(signedInVod), queues.order(signedInVod))
    }

    @Test
    fun headPlayedOrNoPlayLeavesTheQueue() {
        queues.commit(vod, listOf(PlayerClient.VISIONOS to ClientOutcome.PLAYED))
        assertEquals(PlayerClientQueues.defaultOrder(vod), queues.order(vod))

        queues.commit(live, listOf(
            PlayerClient.WEB to ClientOutcome.CONTENT,
            PlayerClient.WEB_SAFARI to ClientOutcome.SOFT
        ))
        assertEquals(PlayerClientQueues.defaultOrder(live), queues.order(live))
    }

    @Test
    fun demoteMovesTheClientToTheEnd() {
        queues.demote(vod, PlayerClient.VISIONOS)
        assertEquals(
            listOf(PlayerClient.WEB, PlayerClient.WEB_EMBED, PlayerClient.WEB_SAFARI, PlayerClient.TV, PlayerClient.ANDROID_VR, PlayerClient.VISIONOS),
            queues.order(vod)
        )
    }

    @Test
    fun returnedOrderIsACopy() {
        val copy = queues.order(vod).toMutableList()
        copy.clear()
        assertEquals(PlayerClientQueues.defaultOrder(vod), queues.order(vod))
    }
}
