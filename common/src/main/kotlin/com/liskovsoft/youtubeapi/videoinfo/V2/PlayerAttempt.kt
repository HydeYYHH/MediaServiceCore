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

data class PlayerAnswer(
    val transportFailed: Boolean,
    val status: String?,
    val reason: String?,
    val hasDirectUrl: Boolean,
    val hasHls: Boolean,
    val hasMuxed: Boolean,
    val live: Boolean
)

data class AttemptResult(val form: PlaybackForm, val client: PlayerClient?)

class PlayerAttempt(private val queues: PlayerClientQueues) {
    fun begin(signIn: SignIn): List<PlayerClient> =
        queues.order(ClientQueueKey(signIn, PlaybackForm.VOD))

    fun onLive(signIn: SignIn, tried: Set<PlayerClient>): List<PlayerClient> =
        queues.order(ClientQueueKey(signIn, PlaybackForm.LIVE)).filter { it !in tried }

    fun finish(key: ClientQueueKey, attempts: List<Pair<PlayerClient, ClientOutcome>>) {
        queues.commit(key, attempts)
    }

    fun run(signIn: SignIn, fetch: (PlayerClient) -> PlayerAnswer): AttemptResult {
        var form = PlaybackForm.VOD
        var pending = begin(signIn)
        val tried = LinkedHashSet<PlayerClient>()
        val attempts = ArrayList<Pair<PlayerClient, ClientOutcome>>()
        var played: PlayerClient? = null
        var index = 0
        while (index < pending.size) {
            val client = pending[index]
            index++
            if (!tried.add(client)) continue
            val answer = fetch(client)
            if (form == PlaybackForm.VOD && answer.live) {
                form = PlaybackForm.LIVE
                attempts.clear()
                record(attempts, client, answer, true)
                if (attempts.last().second == ClientOutcome.PLAYED) {
                    played = client
                    break
                }
                pending = onLive(signIn, tried)
                index = 0
                continue
            }
            record(attempts, client, answer, form == PlaybackForm.LIVE)
            if (attempts.last().second == ClientOutcome.PLAYED) {
                played = client
                break
            }
        }
        finish(ClientQueueKey(signIn, form), attempts)
        return AttemptResult(form, played)
    }

    private fun record(
        attempts: MutableList<Pair<PlayerClient, ClientOutcome>>,
        client: PlayerClient,
        answer: PlayerAnswer,
        live: Boolean
    ) {
        attempts.add(client to clientOutcome(
            answer.transportFailed,
            answer.status,
            answer.reason,
            answer.hasDirectUrl,
            answer.hasHls,
            answer.hasMuxed,
            live
        ))
    }
}
