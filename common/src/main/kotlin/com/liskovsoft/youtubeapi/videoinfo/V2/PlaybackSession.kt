package com.liskovsoft.youtubeapi.videoinfo.V2

data class PlayerSource(val videoId: String, val client: PlayerClient, val context: RequestContext, val urls: Set<String>, val sequence: Long)
class PlaybackSession(private val http: HttpClient = JvmHttpClient(), private val script: PlayerScript? = null,
    private val solver: PlayerSolver? = null, private val tokens: PlayerTokenProvider? = null) {
    private val lock = PlaybackLock()
    private val queues = PlayerClientQueues()
    private val sequence = java.util.concurrent.atomic.AtomicLong()
    fun load(videoId: String, context: RequestContext = RequestContext(), preference: PlaybackPreference = PlaybackPreference(),
        fresh: Boolean = false, excluded: Set<PlayerClient> = emptySet()): ReadResult<PlaybackLoad> {
        val key = listOf(videoId, context.scope(), preference.toString(), script?.url, script?.source,
            fresh.toString(), excluded.sortedBy { it.name }.toString()).joinToString("\u0001")
        val loaded = try { lock.load(key) {
            val result = loadPlayer(videoId, context, http, script, solver, tokens, queues, excluded)
            if (!result.ok) PlaybackLoad(PlaybackForm.VOD, null, Playback(null, null, null), error = result.error)
            else {
                val player = result.value!!
                val playback = selectPlayback(player.answer, preference)
                val urls = listOfNotNull(playback.hlsUrl, playback.dashUrl, playback.sabrUrl, playback.videoUrl, playback.audioUrl).toSet()
                if (urls.isEmpty()) PlaybackLoad(player.form, player.client, playback, error = RequestError(RequestFailure.UNSUPPORTED, "no_matching_format"))
                else PlaybackLoad(player.form, player.client, playback, player.answer,
                    PlayerSource(videoId, player.client!!, context, urls, sequence.incrementAndGet()))
            }
        }} catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return ReadResult(error = RequestError(RequestFailure.CANCELLED, "cancelled"))
        }
        return if (loaded.error != null) ReadResult(error = loaded.error) else ReadResult(loaded)
    }
}
