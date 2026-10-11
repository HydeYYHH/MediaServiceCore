package com.liskovsoft.youtubeapi.videoinfo.V2

import com.liskovsoft.youtubeapi.common.helpers.AppClient
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlayerClientQueuesTest {
    private val queues = PlayerClientQueues()
    private val vod = ClientQueueKey(SignIn.SIGNED_OUT, PlaybackForm.VOD)
    private val live = ClientQueueKey(SignIn.SIGNED_OUT, PlaybackForm.LIVE)
    private val signedInVod = ClientQueueKey(SignIn.SIGNED_IN, PlaybackForm.VOD)

    @Test
    fun defaultsMatchTheFourQueues() {
        assertEquals(
            listOf(AppClient.VISIONOS, AppClient.WEB, AppClient.WEB_EMBED, AppClient.WEB_SAFARI, AppClient.TV),
            queues.order(vod)
        )
        assertEquals(queues.order(vod), queues.order(signedInVod))
        assertEquals(
            listOf(AppClient.WEB, AppClient.WEB_SAFARI, AppClient.VISIONOS, AppClient.WEB_EMBED, AppClient.ANDROID_VR),
            queues.order(live)
        )
        assertEquals(
            listOf(AppClient.WEB, AppClient.WEB_SAFARI, AppClient.TV, AppClient.WEB_EMBED, AppClient.ANDROID_VR),
            queues.order(ClientQueueKey(SignIn.SIGNED_IN, PlaybackForm.LIVE))
        )
    }

    @Test
    fun contentRefusalDoesNotReorder() {
        queues.commit(vod, listOf(
            AppClient.VISIONOS to ClientOutcome.CONTENT,
            AppClient.WEB_EMBED to ClientOutcome.PLAYED
        ))
        assertEquals(PlayerClientQueues.defaultOrder(vod), queues.order(vod))
    }

    @Test
    fun softFailurePromotesTheClientThatPlayed() {
        queues.commit(vod, listOf(
            AppClient.VISIONOS to ClientOutcome.SOFT,
            AppClient.WEB to ClientOutcome.CONTENT,
            AppClient.WEB_EMBED to ClientOutcome.PLAYED
        ))
        assertEquals(AppClient.WEB_EMBED, queues.order(vod).first())
        assertEquals(PlayerClientQueues.defaultOrder(signedInVod), queues.order(signedInVod))
    }

    @Test
    fun headPlayedOrNoPlayLeavesTheQueue() {
        queues.commit(vod, listOf(AppClient.VISIONOS to ClientOutcome.PLAYED))
        assertEquals(PlayerClientQueues.defaultOrder(vod), queues.order(vod))

        queues.commit(live, listOf(
            AppClient.WEB to ClientOutcome.CONTENT,
            AppClient.WEB_SAFARI to ClientOutcome.SOFT
        ))
        assertEquals(PlayerClientQueues.defaultOrder(live), queues.order(live))
    }

    @Test
    fun demoteMovesTheClientToTheEnd() {
        queues.demote(vod, AppClient.VISIONOS)
        assertEquals(
            listOf(AppClient.WEB, AppClient.WEB_EMBED, AppClient.WEB_SAFARI, AppClient.TV, AppClient.VISIONOS),
            queues.order(vod)
        )
        queues.demote(vod, AppClient.IOS)
        assertEquals(AppClient.WEB, queues.order(vod).first())
    }

    @Test
    fun returnedOrderIsACopy() {
        val copy = queues.order(vod).toMutableList()
        copy.clear()
        assertEquals(PlayerClientQueues.defaultOrder(vod), queues.order(vod))
    }
}
