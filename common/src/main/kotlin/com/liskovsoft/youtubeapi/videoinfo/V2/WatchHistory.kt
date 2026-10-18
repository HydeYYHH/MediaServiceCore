package com.liskovsoft.youtubeapi.videoinfo.V2

data class PlayerTracking(val playbackUrl: String?, val watchTimeUrl: String?)
fun playerTracking(body: String): PlayerTracking {
    val root = jsonObject(body)?.obj("playbackTracking")
    return PlayerTracking(root?.obj("videostatsPlaybackUrl")?.text("baseUrl"), root?.obj("videostatsWatchtimeUrl")?.text("baseUrl"))
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
class WatchHistory(private val http: HttpClient = JvmHttpClient(), private val cpn: String = java.util.UUID.randomUUID().toString().replace("-", "").take(16)) {
    private val positions = java.util.LinkedHashMap<String, Long>()
    @Synchronized fun update(videoId: String, positionMs: Long, durationMs: Long, tracking: PlayerTracking,
        context: RequestContext): ReadResult<Boolean> {
        if (context.accessToken == null) return ReadResult(error = RequestError(RequestFailure.AUTH, "sign_in_required"))
        if (!videoIdAccepted(videoId) || durationMs <= 0 || positionMs !in 0..durationMs)
            return ReadResult(error = RequestError(RequestFailure.INVALID, "invalid_watch_position"))
        val key = context.scope() + "\u0000" + videoId
        fun send(base: String?, playback: Boolean): ReadResult<String> {
            val uri = try { java.net.URI(base ?: "") } catch (_: Exception) { null }
            if (uri?.scheme != "https" || uri.host != "www.youtube.com" || (uri.port != -1 && uri.port != 443) ||
                uri.path != if (playback) "/api/stats/playback" else "/api/stats/watchtime")
                return ReadResult(error = RequestError(RequestFailure.INVALID, "invalid_tracking_url"))
            var url = base!!
            val fields = listOf("docid" to videoId, "cpn" to cpn, "cmt" to (positionMs / 1000.0).toString(), "len" to (durationMs / 1000.0).toString(),
                "st" to ((positions[key] ?: positionMs) / 1000.0).toString(), "et" to (positionMs / 1000.0).toString())
            for ((name, value) in fields) url = urlWithParameter(url, name, value)
            return requestBody(HttpRequest("GET", url, mapOf("Authorization" to "Bearer " + context.accessToken)), http)
        }
        if (key !in positions) {
            val created = send(tracking.playbackUrl, true)
            if (!created.ok) return ReadResult(error = created.error)
        }
        val updated = send(tracking.watchTimeUrl, false)
        if (!updated.ok) return ReadResult(error = updated.error)
        positions[key] = positionMs
        while (positions.size > 64) positions.remove(positions.keys.first())
        return ReadResult(true)
    }
    @Synchronized fun reset() = positions.clear()
}
