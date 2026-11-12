package com.liskovsoft.youtubeapi.videoinfo.V2

import java.net.URI
import java.security.MessageDigest

data class RequestContext(
    val accountId: String? = null,
    val visitor: String? = null, val language: String = "en", val region: String = "US",
    val generation: Long = 0, val onBehalfOfUser: String? = null,
    val webSession: WebSession? = null, val visitorCookie: String? = null,
    val actionAttestationProvider: ActionAttestationProvider? = null
) {
    constructor(accountId: String?, visitor: String?, language: String, region: String, generation: Long,
        onBehalfOfUser: String?, webSession: WebSession?, visitorCookie: String?) :
        this(accountId, visitor, language, region, generation, onBehalfOfUser, webSession, visitorCookie, null)

    init {
        require(onBehalfOfUser?.none { it == '\r' || it == '\n' } != false)
        require(visitorCookie?.none { it == '\r' || it == '\n' } != false)
        require(visitor?.none { it == '\r' || it == '\n' } != false)
    }
    val signedIn: Boolean get() = webSession?.signedIn == true
    fun anonymous() = copy(accountId = null, onBehalfOfUser = null, webSession = null)
    internal fun scope() = listOf(accountId, visitor, language, region,
        generation.toString(), onBehalfOfUser, webSession?.scope(), visitorCookie?.let(::textDigest)).joinToString("\u0000") {
        if (it == null) "-" else it.length.toString() + ":" + it
    }
    override fun toString() = "RequestContext(language=$language, region=$region, generation=$generation)"
}

/** Ledger identity: the verified account, otherwise credential/visitor scope, plus generation.
 *  Unlike [scope], rotating cookies do not change it, so credential renewal
 *  mid-playback keeps the position ledger and the playback session alive. */
fun RequestContext.ledgerScope(): String =
    listOf(accountId ?: if (signedIn) webSession?.scope() else visitorIdentity(visitorCookie, visitor), generation.toString(), onBehalfOfUser,
        webSession?.accountIndex?.toString())
        .joinToString(Char.MIN_VALUE.toString()) { if (it == null) "-" else it.length.toString() + ":" + it }
internal fun textDigest(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
fun PlayerQuery.request() = HttpRequest("POST", url, headers, body)
fun legacyPost(http: HttpClient): (PlayerQuery) -> Pair<Int, String> = { query ->
    http.execute(query.request()).let { it.status to it.body }
}
internal fun contextualQuery(query: PlayerQuery, context: RequestContext): PlayerQuery {
    val headers = query.headers.toMutableMap()
    val origin = try { URI(query.url) } catch (_: Exception) { null }
    val api = origin?.scheme == "https" && origin.host == "www.youtube.com" && origin.port in listOf(-1, 443)
        && origin.rawUserInfo == null && origin.path.startsWith("/youtubei/")
    headers.keys.filter { it.equals("Cookie",true) || it.equals("Authorization",true) || it.equals("X-Goog-AuthUser",true) || it.equals("X-Origin",true) || it.equals("X-Goog-PageId",true) || it.equals("Origin",true) || it.equals("X-Goog-Visitor-Id",true) }.toList().forEach { headers.remove(it) }
    if (api) headers.putAll(context.sessionHeaders())
    val root = jsonObject(query.body) ?: return query.copy(headers = headers)
    val client = root.path("context", "client") ?: return query.copy(headers = headers)
    val clientFields = (client.fields - "visitorData") + mapOf("hl" to Json.Str(context.language), "gl" to Json.Str(context.region)) +
        context.visitor?.takeIf { api }?.let { mapOf("visitorData" to Json.Str(it)) }.orEmpty()
    var contextFields = root.obj("context")!!.fields + ("client" to Json.Obj(clientFields))
    val user = root.obj("context")?.obj("user")?.fields.orEmpty() - "onBehalfOfUser"
    val delegated = if (api && context.signedIn) context.onBehalfOfUser else null
    if (user.isNotEmpty() || delegated != null || contextFields.containsKey("user")) {
        contextFields = contextFields + ("user" to Json.Obj(user + delegated?.let {
            mapOf("onBehalfOfUser" to Json.Str(it))
        }.orEmpty()))
    }
    val finalContext = contextFields
    return query.copy(headers = headers, body = Json.Obj(root.fields + ("context" to Json.Obj(finalContext))).encode())
}
internal fun youtubeQuery(endpoint: String, fields: String, context: RequestContext): PlayerQuery {
    return contextualQuery(webQuery(endpoint, fields, context.visitor), context)
}
/** Call only after validating the destination origin. Anonymous visitor cookies cannot replace credentials. */
internal fun RequestContext.sessionHeaders(): Map<String, String> {
    val headers = if (signedIn) webSession!!.headers() + onBehalfOfUser?.let {
        mapOf("X-Goog-PageId" to it)
    }.orEmpty() else visitorCookie?.takeIf { it.isNotBlank() }?.let { mapOf("Cookie" to it) }.orEmpty()
    return headers + visitor?.let { mapOf("X-Goog-Visitor-Id" to it) }.orEmpty()
}
// Topic endpoints (FEtopics_*) require the TV client profile.
internal fun tvQuery(endpoint: String, fields: String, visitor: String?): PlayerQuery {
    val player = playerQuery(PlayerClient.TV, "context", visitor)
    val client = jsonObject(player.body)!!.obj("context")!!
    return PlayerQuery(endpoint, player.headers, "{\"context\":${client.encode()}${if (fields.isEmpty()) "" else "," + fields}}")
}
internal const val YOUTUBE_API = "https://www.youtube.com/youtubei/v1/"
