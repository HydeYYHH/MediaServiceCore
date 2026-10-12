package com.liskovsoft.youtubeapi.videoinfo.V2

private val contentStatuses = setOf(
    "UNPLAYABLE",
    "ERROR",
    "LIVE_STREAM_OFFLINE",
    "LOGIN_REQUIRED",
    "AGE_CHECK_REQUIRED",
    "AGE_VERIFICATION_REQUIRED",
    "CONTENT_CHECK_REQUIRED"
)

fun clientOutcome(
    transportFailed: Boolean,
    status: String?,
    reason: String?,
    hasDirectUrl: Boolean,
    hasHls: Boolean,
    hasMuxed: Boolean,
    live: Boolean
): ClientOutcome {
    if (transportFailed || isBotCheck(reason)) return ClientOutcome.SOFT
    if (status != null && status in contentStatuses) return ClientOutcome.CONTENT
    val played = if (live) hasHls || hasMuxed else hasDirectUrl || hasHls
    return if (played) ClientOutcome.PLAYED else ClientOutcome.SOFT
}

private fun isBotCheck(reason: String?): Boolean {
    if (reason == null) return false
    val text = reason.toLowerCase()
    return text.contains("not a bot") || text.contains("unusual traffic")
}

class PlayerAttempt(private val queues: PlayerClientQueues) {
    fun begin(signIn: SignIn): List<PlayerClient> =
        queues.order(ClientQueueKey(signIn, PlaybackForm.VOD))

    fun onLive(signIn: SignIn, tried: Set<PlayerClient>): List<PlayerClient> =
        queues.order(ClientQueueKey(signIn, PlaybackForm.LIVE)).filter { it !in tried }

    fun finish(key: ClientQueueKey, attempts: List<Pair<PlayerClient, ClientOutcome>>) {
        queues.commit(key, attempts)
    }
}
