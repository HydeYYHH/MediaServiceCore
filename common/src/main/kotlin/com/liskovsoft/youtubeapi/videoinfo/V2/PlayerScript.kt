package com.liskovsoft.youtubeapi.videoinfo.V2

private val JS_URL = Regex("\"jsUrl\"\\s*:\\s*\"([^\"]*)\"")
private const val ORIGIN = "https://www.youtube.com"

fun playerScriptUrl(page: String): String? {
    val raw = JS_URL.find(page)?.groupValues?.get(1) ?: return null
    val path = raw.replace("\\/", "/")
    if (path.isEmpty()) return null
    if (path.startsWith("https://")) return path
    if (path.startsWith("/")) return ORIGIN + path
    return null
}
