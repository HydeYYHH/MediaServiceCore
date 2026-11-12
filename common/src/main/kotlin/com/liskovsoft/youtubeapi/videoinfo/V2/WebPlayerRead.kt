package com.liskovsoft.youtubeapi.videoinfo.V2

import java.net.URI
import java.net.URLEncoder

/** Native desktop WEB watch-document bootstrap. Getters perform no I/O. */
class WebPlayerPage(val answer: PlayerAnswer, val scriptUrl: String?) {
    val tracking: PlayerTracking? get() = answer.details?.tracking
    override fun toString() = "WebPlayerPage(status=${answer.status}, hasTracking=${tracking != null})"
}

/**
 * Loads the browser's initial player response using the explicit cookie jar and account index.
 * This is a WEB document source, separate from an Innertube /player attempt. It neither downloads
 * media nor supplies a JavaScript solver/PoToken. Delegated document identity is not established.
 */
@JvmOverloads fun loadWebPlayer(videoId: String, context: RequestContext,
    http: HttpClient = JvmHttpClient()): ReadResult<WebPlayerPage> {
    if (!videoIdAccepted(videoId)) return ReadResult(error = RequestError(RequestFailure.INVALID, "invalid_video_id"))
    if (context.onBehalfOfUser != null) return ReadResult(error = RequestError(RequestFailure.UNSUPPORTED, "web_player_document_brand_identity_unsupported"))
    if (!context.signedIn && context.accountId != null)
        return ReadResult(error = RequestError(RequestFailure.AUTH, "missing_web_document_identity"))
    val signed = context.webSession?.takeIf { it.signedIn }
    val cookies = if (signed != null) signed.cookies else try {
        val raw = context.visitorCookie.orEmpty()
        parseVisitorCookies(raw)
        raw
    } catch (_: IllegalArgumentException) {
        return ReadResult(error = RequestError(RequestFailure.INVALID, "invalid_visitor_cookies"))
    }
    val headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36") +
        cookies.takeIf(String::isNotBlank)?.let { mapOf("Cookie" to it) }.orEmpty()
    val url = "https://www.youtube.com/watch?v=${URLEncoder.encode(videoId, "UTF-8")}&hl=${URLEncoder.encode(context.language, "UTF-8")}" +
        "&gl=${URLEncoder.encode(context.region, "UTF-8")}" + signed?.let { "&authuser=${it.accountIndex}" }.orEmpty()
    val reply = requestBody(HttpRequest("GET", url, headers), http)
    if (!reply.ok) return ReadResult(error = reply.error)
    val html = reply.value!!
    val configs = documentConfigurations(html)
    if (configs.isEmpty()) return ReadResult(error = RequestError(RequestFailure.PARSE, "missing_web_player_config"))
    val config = Json.Obj(configs.fold(emptyMap<String, Json>()) { fields, value -> fields + value.fields })
    if (config.bool("LOGGED_IN") != context.signedIn)
        return ReadResult(error = RequestError(RequestFailure.AUTH, "web_document_login_state_mismatch"))
    if (!config.text("DELEGATED_SESSION_ID").isNullOrBlank())
        return ReadResult(error = RequestError(RequestFailure.AUTH, "unexpected_web_document_delegation"))
    if (signed != null && config.long("SESSION_INDEX") != signed.accountIndex.toLong())
        return ReadResult(error = RequestError(RequestFailure.AUTH, "web_document_account_index_mismatch"))
    val root = documentPlayerResponse(html)
        ?: return ReadResult(error = RequestError(RequestFailure.PARSE, "missing_web_player_response"))
    val status = root.obj("playabilityStatus")?.text("status")?.takeIf { it.matches(Regex("[A-Z_]{1,48}")) }
        ?: return ReadResult(error = RequestError(RequestFailure.PARSE, "missing_playability_status"))
    if (root.obj("videoDetails")?.text("videoId")?.let { it != videoId } == true ||
        status == "OK" && root.obj("videoDetails")?.text("videoId") != videoId)
        return ReadResult(error = RequestError(RequestFailure.PARSE, "web_player_video_mismatch"))
    val answer = playerAnswer(root)
    if (isBotCheck(answer.reason)) return ReadResult(error = RequestError(RequestFailure.VERIFICATION, "playability_bot_check"))
    val script = playerScriptUrl(html)?.takeIf { target ->
        val uri = runCatching { URI(target) }.getOrNull()
        uri?.scheme == "https" && uri.host == "www.youtube.com" && uri.port in listOf(-1, 443) &&
            uri.rawUserInfo == null && uri.rawFragment == null && uri.path.startsWith("/s/player/") && uri.path.endsWith(".js")
    }
    return ReadResult(WebPlayerPage(answer, script))
}
