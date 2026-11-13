package com.liskovsoft.youtubeapi.videoinfo.V2

import java.net.URI
import java.util.LinkedHashMap
import java.util.Locale
import java.util.UUID

data class PlayerTracking(val playbackUrl: String?, val watchTimeUrl: String?)
fun playerTracking(body: String): PlayerTracking = playerTracking(jsonObject(body))
internal fun playerTracking(root: Json.Obj?): PlayerTracking {
    val tracking = root?.obj("playbackTracking")
    return PlayerTracking(tracking?.obj("videostatsPlaybackUrl")?.text("baseUrl"), tracking?.obj("videostatsWatchtimeUrl")?.text("baseUrl"))
}

/** Account history needs tracking issued to that account, even when media plays anonymously. */
fun loadAccountWatchTracking(videoId: String, context: RequestContext, http: HttpClient = JvmHttpClient()): ReadResult<PlayerTracking> {
    if (!videoIdAccepted(videoId)) return ReadResult(error = RequestError(RequestFailure.INVALID, "invalid_video_id"))
    if (!context.signedIn) return ReadResult(error = RequestError(RequestFailure.AUTH, "sign_in_required"))
    return loadNativeWatchTracking(videoId, context, http, "account")
}

/** Tracking issued by native WEB for this signed-in or anonymous visitor identity; not proof of personalization. */
@JvmOverloads fun loadWatchTracking(videoId: String, context: RequestContext,
    http: HttpClient = JvmHttpClient()): ReadResult<PlayerTracking> {
    if (!videoIdAccepted(videoId)) return ReadResult(error = RequestError(RequestFailure.INVALID, "invalid_video_id"))
    if (!context.signedIn) {
        if (context.visitor.isNullOrBlank() || context.onBehalfOfUser != null || context.accountId != null)
            return ReadResult(error = RequestError(RequestFailure.AUTH, "missing_watch_identity"))
        try { VisitorSession(context.visitorCookie.orEmpty(), context.visitor!!) } catch (_: IllegalArgumentException) {
            return ReadResult(error = RequestError(RequestFailure.INVALID, "invalid_visitor_session"))
        }
    }
    return loadNativeWatchTracking(videoId, context, http, "watch")
}

private fun loadNativeWatchTracking(videoId: String, context: RequestContext, http: HttpClient,
    scope: String): ReadResult<PlayerTracking> {
    val response = requestObject(contextualQuery(playerQuery(PlayerClient.WEB, videoId, context.visitor), context), http)
    if (!response.ok) return ReadResult(error = response.error)
    val root = response.value!!
    val answer = playerAnswer(root)
    if (isBotCheck(answer.reason))
        return ReadResult(error = RequestError(RequestFailure.VERIFICATION, "playability_bot_check"))
    val status = answer.status
    if (status != "OK") {
        val failure = if (status == "LOGIN_REQUIRED") RequestFailure.AUTH else RequestFailure.UNSUPPORTED
        val code = status?.takeIf { it.matches(Regex("[A-Z_]{1,48}")) }?.let { "playability_${it.lowercase(Locale.ROOT)}" }
            ?: return ReadResult(error = RequestError(RequestFailure.PARSE, "missing_playability_status"))
        return ReadResult(error = RequestError(failure, code))
    }
    if (root.obj("videoDetails")?.text("videoId") != videoId)
        return ReadResult(error = RequestError(RequestFailure.PARSE, "tracking_video_mismatch"))
    val tracking = playerTracking(root)
    if (tracking.playbackUrl.isNullOrBlank() || tracking.watchTimeUrl.isNullOrBlank())
        return ReadResult(error = RequestError(RequestFailure.PARSE, "missing_${scope}_tracking"))
    if (!validTrackingUrl(tracking.playbackUrl, true) || !validTrackingUrl(tracking.watchTimeUrl, false))
        return ReadResult(error = RequestError(RequestFailure.PARSE, "invalid_${scope}_tracking_url"))
    return ReadResult(tracking)
}

private fun validTrackingUrl(url: String?, playback: Boolean): Boolean {
    val uri = try { URI(url ?: "") } catch (_: Exception) { return false }
    return uri.scheme == "https" && uri.host in setOf("www.youtube.com", "s.youtube.com") && uri.rawUserInfo == null &&
        (uri.port == -1 || uri.port == 443) && uri.rawFragment == null &&
        uri.path == if (playback) "/api/stats/playback" else "/api/stats/watchtime"
}

fun setWatchHistory(enabled: Boolean, context: RequestContext, http: HttpClient = JvmHttpClient()) =
    performWatchHistoryManagement(if (enabled) WatchHistoryCommand.RESUME else WatchHistoryCommand.PAUSE, context, http)
fun clearWatchHistory(context: RequestContext, http: HttpClient = JvmHttpClient()) =
    performWatchHistoryManagement(WatchHistoryCommand.CLEAR, context, http)
fun removeWatchHistory(feedbackToken: String, context: RequestContext, http: HttpClient = JvmHttpClient()): ReadResult<ActionReply> {
    if (feedbackToken.isBlank()) return ReadResult(error = RequestError(RequestFailure.INVALID, "missing_feedback_token"))
    return submitHistoryFeedback(Json.Obj(mapOf("feedbackToken" to Json.Str(feedbackToken))), context, http)
}
// TrackingApi.createWatchRecordShort/updateWatchTime; keep the answer's ei/vm/of parameters.
class WatchHistory(private val http: HttpClient = JvmHttpClient()) {
    /** One playback session: its cpn and the last position watch time was reported at. */
    private class Session(val cpn: String, var lastMs: Long)

    private val sessions = LinkedHashMap<String, Session>()
    private var activeKey: String? = null

    @Synchronized fun update(videoId: String, positionMs: Long, durationMs: Long, tracking: PlayerTracking,
        context: RequestContext, http: HttpClient = this.http, discontinuity: Boolean = false): ReadResult<Boolean> {
        if (!context.signedIn && context.visitor.isNullOrBlank())
            return ReadResult(error = RequestError(RequestFailure.AUTH, "missing_watch_identity"))
        if (!videoIdAccepted(videoId) || durationMs <= 0 || positionMs !in 0..durationMs)
            return ReadResult(error = RequestError(RequestFailure.INVALID, "invalid_watch_position"))
        // The ledger key follows the stable identity: cookie renewal mid-playback must
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
        if (!validTrackingUrl(base, playback))
            return ReadResult(error = RequestError(RequestFailure.INVALID, "invalid_tracking_url"))
        var url = base!!
        // Without ver=2, YouTube can return 204 without persisting account history.
        val fields = listOf("ver" to "2", "docid" to videoId, "cpn" to cpn, "cmt" to (positionMs / 1000.0).toString(), "len" to (durationMs / 1000.0).toString(),
            "st" to (startMs / 1000.0).toString(), "et" to (endMs / 1000.0).toString())
        for ((name, value) in fields) url = urlWithParameter(url, name, value)
        val headers = context.sessionHeaders()
        return requestBody(HttpRequest("GET", url, headers), http)
    }

    private fun newCpn(): String = UUID.randomUUID().toString().replace("-", "").take(16)

    @Synchronized fun reset() {
        sessions.clear()
        activeKey = null
    }
}
