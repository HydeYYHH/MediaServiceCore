package com.liskovsoft.youtubeapi.videoinfo.V2

import com.liskovsoft.youtubeapi.common.helpers.AppClient

enum class SignIn { SIGNED_OUT, SIGNED_IN }

enum class PlaybackForm { VOD, LIVE }

data class ClientQueueKey(val signIn: SignIn, val form: PlaybackForm)

enum class ClientOutcome { PLAYED, CONTENT, SOFT }

class PlayerClientQueues {
    private val orders = HashMap<ClientQueueKey, MutableList<AppClient>>()

    fun order(key: ClientQueueKey): List<AppClient> = synchronized(orders) {
        queue(key).toList()
    }

    fun commit(key: ClientQueueKey, attempts: List<Pair<AppClient, ClientOutcome>>) {
        val played = attempts.indexOfFirst { it.second == ClientOutcome.PLAYED }
        if (played < 0 || attempts.subList(0, played).none { it.second == ClientOutcome.SOFT }) return
        moveToFront(key, attempts[played].first)
    }

    fun demote(key: ClientQueueKey, client: AppClient) = synchronized(orders) {
        val queue = queue(key)
        if (queue.remove(client)) queue.add(client)
    }

    private fun moveToFront(key: ClientQueueKey, client: AppClient) = synchronized(orders) {
        val queue = queue(key)
        if (queue.remove(client)) queue.add(0, client)
    }

    private fun queue(key: ClientQueueKey): MutableList<AppClient> =
        orders.getOrPut(key) { defaultOrder(key).toMutableList() }

    companion object {
        fun defaultOrder(key: ClientQueueKey): List<AppClient> = when (key.form) {
            PlaybackForm.VOD -> listOf(
                AppClient.VISIONOS,
                AppClient.WEB,
                AppClient.WEB_EMBED,
                AppClient.WEB_SAFARI,
                AppClient.TV
            )
            PlaybackForm.LIVE -> when (key.signIn) {
                SignIn.SIGNED_OUT -> listOf(
                    AppClient.WEB,
                    AppClient.WEB_SAFARI,
                    AppClient.VISIONOS,
                    AppClient.WEB_EMBED,
                    AppClient.ANDROID_VR
                )
                SignIn.SIGNED_IN -> listOf(
                    AppClient.WEB,
                    AppClient.WEB_SAFARI,
                    AppClient.TV,
                    AppClient.WEB_EMBED,
                    AppClient.ANDROID_VR
                )
            }
        }
    }
}
