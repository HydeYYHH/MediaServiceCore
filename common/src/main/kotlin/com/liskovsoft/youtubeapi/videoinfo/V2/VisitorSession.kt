package com.liskovsoft.youtubeapi.videoinfo.V2

import java.net.HttpCookie
import java.net.URLEncoder

/** Host-persisted anonymous identity. This type never accepts signed-in account cookies. */
class VisitorSession(cookies: String, val visitorData: String) {
    val cookies: String = visitorCookieHeader(parseVisitorCookies(cookies))
    init {
        require(visitorData.isNotBlank() && visitorData.length <= 8192 && visitorData.all { it.code in 33..126 }) {
            "Invalid visitor data"
        }
    }
    @JvmOverloads fun requestContext(language: String = "en", region: String = "US", generation: Long = 0): RequestContext {
        require(generation >= 0) { "Invalid visitor generation" }
        return RequestContext(visitor = visitorData, visitorCookie = cookies, language = language, region = region, generation = generation)
    }
    override fun toString() = "VisitorSession(cookieCount=${if (cookies.isEmpty()) 0 else cookies.count { it == ';' } + 1})"
}

private val visitorCookieNames = setOf("VISITOR_INFO1_LIVE", "VISITOR_PRIVACY_METADATA", "YSC", "PREF", "SOCS", "CONSENT", "GPS", "__Secure-ROLLOUT_TOKEN")
private const val VISITOR_BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"

internal fun parseVisitorCookies(cookies: String): LinkedHashMap<String, String> {
    require(cookies.length <= 16_384 && cookies.none { it.code < 32 || it.code > 126 }) { "Invalid visitor cookies" }
    val jar = LinkedHashMap<String, String>()
    if (cookies.isBlank()) return jar
    for (part in cookies.split(';')) {
        val pair = part.trim()
        val separator = pair.indexOf('=')
        require(separator > 0) { "Malformed visitor cookie" }
        val name = pair.substring(0, separator)
        val value = pair.substring(separator + 1)
        require(name in visitorCookieNames && !jar.containsKey(name)) { "Unsupported or duplicate visitor cookie" }
        // RFC cookie-octet: no quoting, whitespace, separators, controls or backslashes.
        require(value.all { it.code == 0x21 || it.code in 0x23..0x2B || it.code in 0x2D..0x3A ||
            it.code in 0x3C..0x5B || it.code in 0x5D..0x7E }) { "Malformed visitor cookie value" }
        jar[name] = value
    }
    return jar
}

/** Stable anonymous identity; renewal cookies and refreshed visitor data cannot replace its stable cookie. */
internal fun visitorIdentity(cookies: String?, visitorData: String?): String? = runCatching {
    parseVisitorCookies(cookies.orEmpty())["VISITOR_INFO1_LIVE"]?.takeIf(String::isNotBlank)
}.getOrNull()?.let { "visitor-cookie:${textDigest(it)}" }
    ?: visitorData?.let { "visitor-data:${textDigest(it)}" }

private fun visitorCookieHeader(jar: Map<String, String>) = jar.entries.joinToString("; ") { "${it.key}=${it.value}" }

/**
 * Bootstraps or refreshes only an anonymous www.youtube.com identity. The host explicitly persists
 * [VisitorSession.cookies] and [VisitorSession.visitorData]; MSC performs no persistence or consent action.
 */
@JvmOverloads fun loadVisitorSession(context: RequestContext,
    http: HttpClient = JvmHttpClient()): ReadResult<VisitorSession> {
    if (context.signedIn || context.onBehalfOfUser != null || context.accountId != null)
        return ReadResult(error = RequestError(RequestFailure.AUTH, "visitor_requires_anonymous_context"))
    val jar = try { parseVisitorCookies(context.visitorCookie.orEmpty()) } catch (_: IllegalArgumentException) {
        return ReadResult(error = RequestError(RequestFailure.INVALID, "invalid_visitor_cookies"))
    }
    val cookie = visitorCookieHeader(jar)
    val headers = mapOf("User-Agent" to VISITOR_BROWSER_UA) + cookie.takeIf(String::isNotEmpty)?.let { mapOf("Cookie" to it) }.orEmpty()
    val url = "https://www.youtube.com/?hl=${URLEncoder.encode(context.language, "UTF-8")}&gl=${URLEncoder.encode(context.region, "UTF-8")}"
    var reply: HttpReply? = null
    val body = requestBody(HttpRequest("GET", url, headers), object : HttpClient {
        override fun execute(request: HttpRequest) = http.execute(request).also { reply = it }
    })
    if (!body.ok) return ReadResult(error = body.error)
    val configs = documentConfigurations(body.value!!)
    if (configs.isEmpty()) return ReadResult(error = RequestError(RequestFailure.PARSE, "missing_visitor_config"))
    if (configs.any { it.bool("LOGGED_IN") == true })
        return ReadResult(error = RequestError(RequestFailure.AUTH, "unexpected_signed_in_document"))
    val merged = Json.Obj(configs.fold(emptyMap<String, Json>()) { fields, config -> fields + config.fields })
    val visitor = merged.text("VISITOR_DATA")?.takeIf(String::isNotBlank)
        ?: merged.path("INNERTUBE_CONTEXT", "client")?.text("visitorData")?.takeIf(String::isNotBlank)
        ?: return ReadResult(error = RequestError(RequestFailure.PARSE, "missing_visitor_data"))
    mergeVisitorCookies(jar, reply!!)
    return try { ReadResult(VisitorSession(visitorCookieHeader(jar), visitor)) } catch (_: IllegalArgumentException) {
        ReadResult(error = RequestError(RequestFailure.PARSE, "invalid_visitor_session"))
    }
}

/** Set-Cookie is accepted only for the fixed HTTPS www.youtube.com origin and root path. */
private fun mergeVisitorCookies(jar: LinkedHashMap<String, String>, reply: HttpReply) {
    val header = reply.header("Set-Cookie") ?: return
    if (header.length > 65_536 || header.any { it == '\r' || it == '\n' }) return
    val parts = header.split(Regex(",(?=\\s*[A-Za-z0-9_\\-]+=)"))
    for (part in parts) {
        val pair = part.substringBefore(';').trim()
        val parsed = runCatching { parseVisitorCookies(pair) }.getOrNull() ?: continue
        val name = parsed.keys.singleOrNull() ?: continue
        val attributes = LinkedHashMap<String, String>()
        var malformed = false
        for (raw in part.split(';').drop(1)) {
            val attribute = raw.trim()
            if (attribute.isEmpty()) continue
            val key = attribute.substringBefore('=').lowercase(java.util.Locale.ROOT)
            if (key in attributes) { malformed = true; break }
            attributes[key] = attribute.substringAfter('=', "").trim()
        }
        if (malformed) continue
        val domain = attributes["domain"]?.removePrefix(".")?.lowercase(java.util.Locale.ROOT)
        if (domain != null && domain !in setOf("youtube.com", "www.youtube.com")) continue
        if (attributes["path"]?.let { it != "/" } == true) continue
        if (name.startsWith("__Secure-") && "secure" !in attributes) continue
        val maxAge = attributes["max-age"]?.toLongOrNull()
        if ("max-age" in attributes && maxAge == null) continue
        val cookie = runCatching { HttpCookie.parse(part.trim()).singleOrNull() }.getOrNull() ?: continue
        if (maxAge != null && maxAge <= 0 || cookie.hasExpired()) jar.remove(name)
        else jar[name] = parsed.getValue(name)
    }
}
