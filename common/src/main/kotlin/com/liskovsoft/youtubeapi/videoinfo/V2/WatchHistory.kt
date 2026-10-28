package com.liskovsoft.youtubeapi.videoinfo.V2

data class PlayerTracking(val playbackUrl: String?, val watchTimeUrl: String?)
fun playerTracking(body: String): PlayerTracking = playerTracking(jsonObject(body))
internal fun playerTracking(root: Json.Obj?): PlayerTracking {
    val tracking = root?.obj("playbackTracking")
    return PlayerTracking(tracking?.obj("videostatsPlaybackUrl")?.text("baseUrl"), tracking?.obj("videostatsWatchtimeUrl")?.text("baseUrl"))
}
fun setWatchHistory(enabled: Boolean, context: RequestContext, http: HttpClient = JvmHttpClient()) =
    signedAction("history/" + if (enabled) "resume_watch_history" else "pause_watch_history", "\"nop\":\"false\"", context, http)
fun clearWatchHistory(context: RequestContext, http: HttpClient = JvmHttpClient()) =
    signedAction("history/clear_watch_history", "\"nop\":\"false\"", context, http)
fun removeWatchHistory(feedbackToken: String, context: RequestContext, http: HttpClient = JvmHttpClient()): ReadResult<ActionReply> {
    if (feedbackToken.isBlank()) return ReadResult(error = RequestError(RequestFailure.INVALID, "missing_feedback_token"))
    return signedAction("feedback", "\"feedbackTokens\":[${jsonQuote(feedbackToken)}]", context, http)
}
// TrackingApi.createWatchRecordShort/updateWatchTime; keep the answer's ei/vm/of parameters.
class WatchHistory(private val http: HttpClient = JvmHttpClient()) {
    /** One playback session: its cpn and the last position watch time was reported at. */
    private class Session(val cpn: String, var lastMs: Long)

    private val sessions = java.util.LinkedHashMap<String, Session>()
    private var activeKey: String? = null

    @Synchronized fun update(videoId: String, positionMs: Long, durationMs: Long, tracking: PlayerTracking,
        context: RequestContext, http: HttpClient = this.http, discontinuity: Boolean = false): ReadResult<Boolean> {
        if (!context.signedIn && context.visitor.isNullOrBlank())
            return ReadResult(error = RequestError(RequestFailure.AUTH, "missing_watch_identity"))
        if (!videoIdAccepted(videoId) || durationMs <= 0 || positionMs !in 0..durationMs)
            return ReadResult(error = RequestError(RequestFailure.INVALID, "invalid_watch_position"))
        // The ledger key follows the stable identity: a token refresh mid-playback must
        // not orphan the session, while a sign-in/out (a new generation) still resets it.
        val key = context.ledgerScope() + Char.MIN_VALUE + videoId
        var session = sessions[key]
        if (session == null || activeKey != key) {
            // A new or resumed session gets its own playback nonce.
            session = Session(newCpn(), positionMs)
            sessions[key] = session
            activeKey = key
            while (sessions.size > 64) sessions.remove(sessions.keys.first())
            // Session creation only marks the start; the first watch-time report must
            // span a real interval, so it goes out with the next update.
            val created = send(tracking.playbackUrl, true, videoId, session.cpn, positionMs, durationMs, positionMs, positionMs, context, http)
            if (!created.ok) {
                sessions.remove(key)
                if (activeKey == key) activeKey = null
                return ReadResult(error = created.error)
            }
            return ReadResult(true)
        }
        if (positionMs == session.lastMs) return ReadResult(true) // paused ticks carry no interval
        if (positionMs < session.lastMs || discontinuity) {
            // A seek or transport discontinuity breaks the range; the next forward
            // report chains from the new position. The skipped span is not watch
            // time, so nothing is sent.
            session.lastMs = positionMs
            return ReadResult(true)
        }
        // Forward progress: chain the interval from the last reported position.
        val reported = send(tracking.watchTimeUrl, false, videoId, session.cpn, positionMs, durationMs, session.lastMs, positionMs, context, http)
        if (!reported.ok) return ReadResult(error = reported.error)
        session.lastMs = positionMs
        return ReadResult(true)
    }

    private fun send(base: String?, playback: Boolean, videoId: String, cpn: String, positionMs: Long,
        durationMs: Long, startMs: Long, endMs: Long, context: RequestContext, http: HttpClient): ReadResult<String> {
        val uri = try { java.net.URI(base ?: "") } catch (_: Exception) { null }
        if (uri?.scheme != "https" || uri.host !in setOf("www.youtube.com", "s.youtube.com") || uri.rawUserInfo != null || (uri.port != -1 && uri.port != 443) ||
            uri.path != if (playback) "/api/stats/playback" else "/api/stats/watchtime")
            return ReadResult(error = RequestError(RequestFailure.INVALID, "invalid_tracking_url"))
        var url = base!!
        val fields = listOf("docid" to videoId, "cpn" to cpn, "cmt" to (positionMs / 1000.0).toString(), "len" to (durationMs / 1000.0).toString(),
            "st" to (startMs / 1000.0).toString(), "et" to (endMs / 1000.0).toString())
        for ((name, value) in fields) url = urlWithParameter(url, name, value)
        val headers = (if (!context.accessToken.isNullOrBlank()) mapOf("Authorization" to "Bearer " + context.accessToken)
            else context.webSession?.headers().orEmpty()
            ) + (context.visitor?.let { mapOf("X-Goog-Visitor-Id" to it) } ?: emptyMap())
        return requestBody(HttpRequest("GET", url, headers), http)
    }

    private fun newCpn(): String = java.util.UUID.randomUUID().toString().replace("-", "").take(16)

    @Synchronized fun reset() {
        sessions.clear()
        activeKey = null
    }
}
