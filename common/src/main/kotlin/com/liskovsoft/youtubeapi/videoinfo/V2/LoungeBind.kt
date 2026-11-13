package com.liskovsoft.youtubeapi.videoinfo.V2
import java.net.URLEncoder

private const val BIND_BASE = "https://www.youtube.com/api/lounge/bc/bind" +
    "?device=LOUNGE_SCREEN" +
    "&theme=cl" +
    "&capabilities=dsp%2Cmic%2Cdpa" +
    "&mdxVersion=2" +
    "&VER=8" +
    "&v=2" +
    "&t=1" +
    "&app=lb-v4" +
    "&AID=42" +
    "&zx=xxxxxxxxxxxx"
private const val BIND_URL = BIND_BASE + "&RID=1337"
private const val LISTEN_URL = BIND_BASE + "&RID=rpc&CI=0"
private const val FORM = "application/x-www-form-urlencoded"
private const val HEX = "0123456789ABCDEF"

data class LoungeSession(val sessionId: String, val gSessionId: String, val listenUrl: String)

fun bindQuery(screenName: String?, deviceId: String?, loungeToken: String?): PlayerQuery {
    if (screenName.isNullOrBlank() || deviceId.isNullOrBlank() || loungeToken.isNullOrBlank()) {
        return PlayerQuery("", emptyMap(), "")
    }
    val headers = linkedMapOf("Content-Type" to FORM)
    return PlayerQuery(identityUrl(BIND_URL, screenName, deviceId, loungeToken), headers, "count=0")
}

fun loungeSession(body: String, screenName: String?, deviceId: String?, loungeToken: String?): LoungeSession? {
    if (screenName.isNullOrBlank() || deviceId.isNullOrBlank() || loungeToken.isNullOrBlank()) return null
    val commands = commandArray(body) ?: return null
    var sessionId: String? = null
    var gSessionId: String? = null
    for (item in commands.items) {
        val row = item as? Json.Arr ?: continue
        if (row.items.size < 2) continue
        val command = row.items[1] as? Json.Arr ?: continue
        val type = (command.items.firstOrNull() as? Json.Str)?.value ?: continue
        val param = firstParam(command) ?: continue
        if (type == "c" && sessionId == null) sessionId = param
        if (type == "S" && gSessionId == null) gSessionId = param
    }
    val sid = sessionId ?: return null
    val gsid = gSessionId ?: return null
    val listen = identityUrl(LISTEN_URL, screenName, deviceId, loungeToken) +
        "&SID=" + encode(sid) +
        "&gsessionid=" + encode(gsid)
    return LoungeSession(sid, gsid, listen)
}

fun loadSession(
    screenName: String?,
    deviceId: String?,
    loungeToken: String?,
    post: (PlayerQuery) -> Pair<Int, String>
): LoungeSession? {
    val query = bindQuery(screenName, deviceId, loungeToken)
    if (query.url.isEmpty()) return null
    val http = try {
        post(query)
    } catch (failure: Exception) {
        return null
    }
    if (http.first != 200) return null
    return loungeSession(http.second, screenName, deviceId, loungeToken)
}

private fun identityUrl(base: String, screenName: String, deviceId: String, loungeToken: String): String {
    return base +
        "&name=" + encode(screenName) +
        "&id=" + encode(deviceId) +
        "&loungeIdToken=" + encode(loungeToken)
}

private fun commandArray(body: String): Json.Arr? {
    val json = jsonText(body) ?: return null
    return try {
        JsonReader(json).parse() as? Json.Arr
    } catch (failure: JsonEnd) {
        null
    }
}

private fun jsonText(body: String): String? {
    val trimmed = body.trim()
    if (trimmed.isEmpty()) return null
    val newline = trimmed.indexOf('\n')
    if (newline < 0) return trimmed
    val first = trimmed.substring(0, newline).trim()
    if (first.isNotEmpty() && first.all { it in '0'..'9' }) {
        val rest = trimmed.substring(newline + 1).trim()
        return if (rest.isEmpty()) null else rest
    }
    return trimmed
}

private fun firstParam(command: Json.Arr): String? {
    for (i in 1 until command.items.size) {
        val text = (command.items[i] as? Json.Str)?.value ?: continue
        if (text.isNotEmpty()) return text
    }
    return null
}

private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
