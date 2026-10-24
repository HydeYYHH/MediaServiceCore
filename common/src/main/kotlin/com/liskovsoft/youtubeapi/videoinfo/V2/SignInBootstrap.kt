package com.liskovsoft.youtubeapi.videoinfo.V2

import java.net.URI

// Runtime OAuth client-credential bootstrap, mirroring NewTube's AppInfo/ClientData flow:
// GET youtube.com/tv -> locate the base-js bundle -> first clientId/secret pair wins
// (later occurrences in the bundle carry wrong ids). Verified against the live bundle 2026-10-23.
data class SignInCredentials(val clientId: String, val clientSecret: String) {
    override fun toString() = "SignInCredentials(clientId=$clientId)"
}

private val BASE_JS_TAG = listOf(
    Regex("id=\"base-js\"[^>]*?src=\"([^\"]+)\""),
    Regex("src=\"([^\"]+)\"[^>]*?id=\"base-js\""),
    Regex("\\.src\\s*=\\s*'([^']*m=base[^']*)'")
)
private val CLIENT_CREDENTIALS =
    Regex("clientId:\"([-\\w]+\\.apps\\.googleusercontent\\.com)\",\\n?[$\\w]+:\"(\\w+)\"")

internal fun baseJsUrl(html: String): String? {
    val raw = BASE_JS_TAG.firstNotNullOfOrNull { it.find(html)?.groupValues?.get(1) } ?: return null
    val absolute = when {
        raw.startsWith("//") -> "https:$raw"
        raw.startsWith("/") -> "https://www.youtube.com$raw"
        else -> raw
    }
    val uri = try { URI(absolute) } catch (_: Exception) { null } ?: return null
    if (uri.scheme != "https" || uri.host != "www.youtube.com" || uri.rawUserInfo != null ||
        (uri.port != -1 && uri.port != 443)) return null
    return absolute
}

internal fun signInCredentials(js: String): SignInCredentials? =
    CLIENT_CREDENTIALS.find(js)?.let { SignInCredentials(it.groupValues[1], it.groupValues[2]) }

fun loadSignInCredentials(http: HttpClient = JvmHttpClient()): ReadResult<SignInCredentials> {
    val agent = mapOf("User-Agent" to
        "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.30.1034943-gold (unlike Gecko), Unknown_TV_Unknown_0/Unknown (Unknown, Unknown)")
    val page = requestBody(HttpRequest("GET", "https://www.youtube.com/tv", agent), http)
    if (!page.ok) return ReadResult(error = page.error)
    val jsUrl = baseJsUrl(page.value!!)
        ?: return ReadResult(error = RequestError(RequestFailure.PARSE, "base_js_not_found"))
    val js = requestBody(HttpRequest("GET", jsUrl, agent), http)
    if (!js.ok) return ReadResult(error = js.error)
    return signInCredentials(js.value!!)?.let { ReadResult(it) }
        ?: ReadResult(error = RequestError(RequestFailure.PARSE, "client_credentials_not_found"))
}
