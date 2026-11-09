package com.liskovsoft.youtubeapi.videoinfo.V2

import java.net.URI
import java.security.MessageDigest

data class RequestContext(
    val accountId: String? = null, val accessToken: String? = null,
    val visitor: String? = null, val language: String = "en", val region: String = "US",
    val generation: Long = 0, val onBehalfOfUser: String? = null,
    val webSession: WebSession? = null, val visitorCookie: String? = null
) {
    val signedIn: Boolean get() = !accessToken.isNullOrBlank() || webSession?.signedIn == true
    fun anonymous() = copy(accountId = null, accessToken = null, onBehalfOfUser = null, webSession = null)
    internal fun scope() = listOf(accountId, accessToken?.let(::textDigest), visitor, language, region,
        generation.toString(), onBehalfOfUser, webSession?.scope(), visitorCookie?.let(::textDigest)).joinToString("\u0000") {
        if (it == null) "-" else it.length.toString() + ":" + it
    }
    override fun toString() = "RequestContext(language=$language, region=$region, generation=$generation)"
}

/** Ledger identity: the account (or salted anonymous visitor) plus the session generation.
 *  Unlike [scope], a rotating access token does not change it, so a token refresh
 *  mid-playback keeps the position ledger and the playback session alive. */
fun RequestContext.ledgerScope(): String =
    listOf(accountId ?: visitor?.let(::textDigest), generation.toString(), onBehalfOfUser)
        .joinToString(Char.MIN_VALUE.toString()) { if (it == null) "-" else it.length.toString() + ":" + it }
internal fun textDigest(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
fun PlayerQuery.request() = HttpRequest("POST", url, headers, body)
fun legacyPost(http: HttpClient): (PlayerQuery) -> Pair<Int, String> = { query ->
    http.execute(query.request()).let { it.status to it.body }
}
internal fun contextualQuery(query: PlayerQuery, context: RequestContext): PlayerQuery {
    val root = jsonObject(query.body) ?: return query
    val client = root.path("context", "client") ?: return query
    val clientFields = client.fields + mapOf("hl" to Json.Str(context.language), "gl" to Json.Str(context.region)) +
        context.visitor?.let { mapOf("visitorData" to Json.Str(it)) }.orEmpty()
    val contextFields = root.obj("context")!!.fields + ("client" to Json.Obj(clientFields))
    val headers = query.headers.toMutableMap()
    val origin = try { URI(query.url) } catch (_: Exception) { null }
    val api = origin?.scheme == "https" && origin.host == "www.youtube.com" && origin.port in listOf(-1, 443)
        && origin.rawUserInfo == null && origin.path.startsWith("/youtubei/")
    headers.keys.filter { it.equals("Cookie",true) || it.equals("Authorization",true) || it.equals("X-Goog-AuthUser",true) || it.equals("X-Origin",true) || it.equals("X-Goog-PageId",true) || it.equals("Origin",true) }.toList().forEach { headers.remove(it) }
    if (api && context.accessToken != null) headers["Authorization"] = "Bearer " + context.accessToken
    else if (api) context.webSession?.headers()?.let { headers.putAll(it) }
    if (api && context.accessToken == null && context.webSession?.signedIn == true && context.onBehalfOfUser != null)
        headers["X-Goog-PageId"] = context.onBehalfOfUser
    if (api && !context.signedIn && !context.visitorCookie.isNullOrBlank()) headers["Cookie"] = context.visitorCookie
    if (context.visitor != null) headers["X-Goog-Visitor-Id"] = context.visitor
    val user = context.onBehalfOfUser?.let {
        Json.Obj(root.obj("context")?.obj("user")?.fields.orEmpty() + ("onBehalfOfUser" to Json.Str(it)))
    }
    val finalContext = if (user == null) contextFields else contextFields + ("user" to user)
    return query.copy(headers = headers, body = Json.Obj(root.fields + ("context" to Json.Obj(finalContext))).encode())
}
internal fun youtubeQuery(endpoint: String, fields: String, context: RequestContext): PlayerQuery {
    val template = if (context.accessToken == null) webQuery(endpoint, fields, context.visitor) else tvQuery(endpoint, fields, context.visitor)
    return contextualQuery(template, context)
}
// Some endpoints (FEtopics_*, FEtrending) reject the WEB client with HTTP 400 but serve the TV client;
// verified against the live service 2026-10-23.
internal fun tvQuery(endpoint: String, fields: String, visitor: String?): PlayerQuery {
    val player = playerQuery(PlayerClient.TV, "context", visitor)
    val client = jsonObject(player.body)!!.obj("context")!!
    return PlayerQuery(endpoint, player.headers, "{\"context\":${client.encode()}${if (fields.isEmpty()) "" else "," + fields}}")
}
internal const val YOUTUBE_API = "https://www.youtube.com/youtubei/v1/"
