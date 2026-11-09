package com.liskovsoft.youtubeapi.videoinfo.V2

enum class PlayerClient {
    VISIONOS,
    WEB,
    WEB_EMBED,
    WEB_SAFARI,
    TV,
    ANDROID_VR,
    TV_TIZEN
}

enum class SignIn { SIGNED_OUT, SIGNED_IN }

enum class PlaybackForm { VOD, LIVE }

data class ClientQueueKey(val signIn: SignIn, val form: PlaybackForm)

enum class ClientOutcome { PLAYED, CONTENT, SOFT }

class PlayerClientQueues(private val vodFirst: PlayerClient? = null) {
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
        orders.getOrPut(key) {
            val defaults = defaultOrder(key)
            if (key.form == PlaybackForm.VOD && vodFirst != null && vodFirst in defaults)
                (listOf(vodFirst) + defaults.filter { it != vodFirst }).toMutableList()
            else defaults.toMutableList()
        }

    companion object {
        fun defaultOrder(key: ClientQueueKey): List<PlayerClient> = when (key.form) {
            // Verified playback routes: the cookieless direct-URL client leads anonymous
            // VOD; a signed-in session starts from the cookie-bearing WEB_SAFARI before
            // falling back. ANDROID_VR stays the terminal emergency pool.
            PlaybackForm.VOD -> when (key.signIn) {
                SignIn.SIGNED_IN -> listOf(
                    PlayerClient.WEB_SAFARI,
                    PlayerClient.VISIONOS,
                    PlayerClient.TV_TIZEN,
                    PlayerClient.WEB,
                    PlayerClient.WEB_EMBED,
                    PlayerClient.TV,
                    PlayerClient.ANDROID_VR
                )
                else -> listOf(
                    PlayerClient.VISIONOS,
                    PlayerClient.WEB,
                    PlayerClient.WEB_EMBED,
                    PlayerClient.WEB_SAFARI,
                    PlayerClient.TV,
                    PlayerClient.ANDROID_VR
                )
            }
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
