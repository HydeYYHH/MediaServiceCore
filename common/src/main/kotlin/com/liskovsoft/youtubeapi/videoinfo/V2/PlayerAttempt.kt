package com.liskovsoft.youtubeapi.videoinfo.V2

import java.util.Locale

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

internal fun isBotCheck(reason: String?): Boolean {
    if (reason == null) return false
    val text = reason.lowercase(Locale.ROOT)
    return text.contains("not a bot") || text.contains("unusual traffic")
}

data class PlayerAnswer @JvmOverloads constructor(
    val transportFailed: Boolean,
    val status: String?,
    val reason: String?,
    val hasDirectUrl: Boolean,
    val hasHls: Boolean,
    val hasMuxed: Boolean,
    val live: Boolean,
    val hlsUrl: String? = null,
    val muxedUrls: List<String> = emptyList(),
    val adaptiveUrls: List<String> = emptyList(),
    val muxedFormats: List<PlayerFormat> = emptyList(),
    val adaptiveFormats: List<PlayerFormat> = emptyList(),
    val dashUrl: String? = null,
    val sabrUrl: String? = null,
    val ustreamerConfig: String? = null,
    val expiresInSeconds: Long? = null,
    val details: PlayerDetails? = null,
    val captions: List<PlayerCaption> = emptyList(),
    val expiresAtMs: Long? = null,
    val mediaWaitMs: Long = 0,
    val mediaAvailableAtMs: Long = 0
)

data class PlayerFormat @JvmOverloads constructor(
    val itag: Int?,
    val mimeType: String?,
    val url: String?,
    val cipher: String?,
    val height: Int? = null,
    val bitrate: Int? = null,
    val fps: Int? = null,
    val audioTrack: PlayerAudio? = null,
    val width: Int? = null,
    val contentLength: Long? = null,
    val initRange: PlayerRange? = null,
    val indexRange: PlayerRange? = null,
    val codecs: List<String> = emptyList(),
    val dynamicRange: VideoRange = VideoRange.UNKNOWN,
    val approxDurationMs: Long? = null,
    val sampleRate: Int? = null,
    val audioChannels: Int? = null,
    val qualityLabel: String? = null
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

    fun run(signIn: SignIn, deferred: Set<PlayerClient> = emptySet(), excluded: Set<PlayerClient> = emptySet(),
        fetch: (PlayerClient) -> PlayerAnswer): AttemptResult =
        runCancellable(signIn, deferred, excluded, { false }, fetch)

    internal fun runCancellable(signIn: SignIn, deferred: Set<PlayerClient>, excluded: Set<PlayerClient>,
        cancelled: () -> Boolean, fetch: (PlayerClient) -> PlayerAnswer): AttemptResult {
        fun ordered(clients: List<PlayerClient>): List<PlayerClient> =
            clients.filter { it !in excluded && it !in deferred } + clients.filter { it !in excluded && it in deferred }
        var form = PlaybackForm.VOD
        var pending = ordered(begin(signIn))
        val tried = LinkedHashSet<PlayerClient>()
        val attempts = ArrayList<Pair<PlayerClient, ClientOutcome>>()
        var played: PlayerClient? = null
        var index = 0
        while (index < pending.size) {
            val client = pending[index]
            index++
            if (!tried.add(client)) continue
            val answer = fetch(client)
            // A cancelled owner says nothing about client validity. Do not probe or
            // demote the remaining queue, including its remembered successful client.
            if (cancelled()) return AttemptResult(form, null)
            if (form == PlaybackForm.VOD && answer.live) {
                form = PlaybackForm.LIVE
                attempts.clear()
                record(attempts, client, answer, true)
                if (attempts.last().second == ClientOutcome.PLAYED) {
                    played = client
                    break
                }
                pending = ordered(onLive(signIn, tried))
                index = 0
                continue
            }
            record(attempts, client, answer, form == PlaybackForm.LIVE)
            // An explicit guest challenge makes the account route the next useful
            // attempt. Do not spend a cold Web token mint before asking it, and
            // respect media-rejection deferrals and exclusions from this walk.
            if (form == PlaybackForm.VOD && signIn == SignIn.SIGNED_IN && isBotCheck(answer.reason) &&
                PlayerClient.TV_TIZEN !in tried && PlayerClient.TV_TIZEN !in deferred &&
                PlayerClient.TV_TIZEN in pending.drop(index)) {
                pending = pending.take(index) + PlayerClient.TV_TIZEN + pending.drop(index).filter { it != PlayerClient.TV_TIZEN }
            }
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
            answer.hasHls || answer.dashUrl != null || answer.sabrUrl != null,
            answer.hasMuxed,
            live
        ))
    }
}
