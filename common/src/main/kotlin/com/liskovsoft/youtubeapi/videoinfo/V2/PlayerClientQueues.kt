package com.liskovsoft.youtubeapi.videoinfo.V2

enum class PlayerClient {
    VISIONOS,
    WEB,
    WEB_EMBED,
    WEB_SAFARI,
    TV,
    ANDROID_VR
}

enum class SignIn { SIGNED_OUT, SIGNED_IN }

enum class PlaybackForm { VOD, LIVE }

data class ClientQueueKey(val signIn: SignIn, val form: PlaybackForm)

enum class ClientOutcome { PLAYED, CONTENT, SOFT }

class PlayerClientQueues {
    private val orders = HashMap<ClientQueueKey, MutableList<PlayerClient>>()

    fun order(key: ClientQueueKey): List<PlayerClient> = synchronized(orders) {
        queue(key).toList()
    }

    fun commit(key: ClientQueueKey, attempts: List<Pair<PlayerClient, ClientOutcome>>) {
        val played = attempts.indexOfFirst { it.second == ClientOutcome.PLAYED }
        if (played < 0 || attempts.subList(0, played).none { it.second == ClientOutcome.SOFT }) return
        moveToFront(key, attempts[played].first)
    }

    fun demote(key: ClientQueueKey, client: PlayerClient) = synchronized(orders) {
        val queue = queue(key)
        if (queue.remove(client)) queue.add(client)
    }

    private fun moveToFront(key: ClientQueueKey, client: PlayerClient) = synchronized(orders) {
        val queue = queue(key)
        if (queue.remove(client)) queue.add(0, client)
    }

    private fun queue(key: ClientQueueKey): MutableList<PlayerClient> =
        orders.getOrPut(key) { defaultOrder(key).toMutableList() }

    companion object {
        fun defaultOrder(key: ClientQueueKey): List<PlayerClient> = when (key.form) {
            PlaybackForm.VOD -> listOf(
                PlayerClient.VISIONOS,
                PlayerClient.WEB,
                PlayerClient.WEB_EMBED,
                PlayerClient.WEB_SAFARI,
                PlayerClient.TV
            )
            PlaybackForm.LIVE -> when (key.signIn) {
                SignIn.SIGNED_OUT -> listOf(
                    PlayerClient.WEB,
                    PlayerClient.WEB_SAFARI,
                    PlayerClient.VISIONOS,
                    PlayerClient.WEB_EMBED,
                    PlayerClient.ANDROID_VR
                )
                SignIn.SIGNED_IN -> listOf(
                    PlayerClient.WEB,
                    PlayerClient.WEB_SAFARI,
                    PlayerClient.TV,
                    PlayerClient.WEB_EMBED,
                    PlayerClient.ANDROID_VR
                )
            }
        }
    }
}
