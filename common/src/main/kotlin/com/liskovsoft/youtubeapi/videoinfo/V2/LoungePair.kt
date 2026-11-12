package com.liskovsoft.youtubeapi.videoinfo.V2

private const val SCREEN_URL = "https://www.youtube.com/api/lounge/pairing/generate_screen_id"
private const val TOKEN_URL = "https://www.youtube.com/api/lounge/pairing/get_lounge_token_batch"
private const val PAIR_URL = "https://www.youtube.com/api/lounge/pairing/get_pairing_code?ctx=pair"
private const val FORM = "application/x-www-form-urlencoded"
private const val HEX = "0123456789ABCDEF"

data class LoungeScreen(val screenId: String, val loungeToken: String) {
    override fun toString() = "LoungeScreen"
}

fun screenIdUrl(): String = SCREEN_URL

fun screenId(text: String?): String? {
    if (text == null) return null
    val trimmed = text.trim()
    return if (trimmed.isEmpty()) null else trimmed
}

fun loungeTokenQuery(screenId: String?): PlayerQuery {
    if (screenId.isNullOrBlank()) return PlayerQuery("", emptyMap(), "")
    return formQuery(TOKEN_URL, listOf(Pair("screen_ids", screenId)))
}

fun loungeScreen(body: String): LoungeScreen? {
    val screens = jsonObject(body)?.fields?.get("screens") as? Json.Arr ?: return null
    val first = screens.items.firstOrNull() as? Json.Obj ?: return null
    val id = first.text("screenId")?.takeIf { it.isNotEmpty() } ?: return null
    val token = first.text("loungeToken")?.takeIf { it.isNotEmpty() } ?: return null
    return LoungeScreen(id, token)
}

fun pairingQuery(
    loungeToken: String?,
    screenId: String?,
    screenName: String?,
    deviceId: String?
): PlayerQuery {
    if (loungeToken.isNullOrBlank() || screenId.isNullOrBlank() || screenName.isNullOrBlank() || deviceId.isNullOrBlank()) {
        return PlayerQuery("", emptyMap(), "")
    }
    return formQuery(PAIR_URL, listOf(
        Pair("lounge_token", loungeToken),
        Pair("screen_id", screenId),
        Pair("screen_name", screenName),
        Pair("access_type", "permanent"),
        Pair("app", "lb-v4"),
        Pair("device_id", deviceId),
        Pair("qr", "1")
    ))
}

fun pairingCode(body: String): String? {
    // The live manual-link endpoint returns a bare decimal code. Keep it lexical
    // so leading zeroes survive; never coerce arbitrary JSON numbers or HTML.
    val raw = body.trim()
    if (raw.length in 6..16 && raw.all { it in '0'..'9' }) return groupCode(raw)
    val code = jsonObject(body)?.text("code")?.takeIf { it.isNotEmpty() } ?: return null
    return groupCode(code)
}

fun loadPairing(
    screenName: String?,
    deviceId: String?,
    get: (String) -> String,
    post: (PlayerQuery) -> Pair<Int, String>
): String? {
    if (screenName.isNullOrBlank() || deviceId.isNullOrBlank()) return null
    val rawId = try {
        get(screenIdUrl())
    } catch (failure: Exception) {
        return null
    }
    val id = screenId(rawId) ?: return null
    val tokenHttp = posted(loungeTokenQuery(id), post) ?: return null
    val screen = loungeScreen(tokenHttp) ?: return null
    val query = pairingQuery(screen.loungeToken, screen.screenId, screenName, deviceId)
    val codeHttp = posted(query, post) ?: return null
    return pairingCode(codeHttp)
}

private fun posted(query: PlayerQuery, post: (PlayerQuery) -> Pair<Int, String>): String? {
    if (query.url.isEmpty()) return null
    val http = try {
        post(query)
    } catch (failure: Exception) {
        return null
    }
    if (http.first != 200) return null
    return http.second
}

private fun formQuery(url: String, fields: List<Pair<String, String>>): PlayerQuery {
    val headers = linkedMapOf("Content-Type" to FORM)
    return PlayerQuery(url, headers, formBody(fields))
}

private fun formBody(fields: List<Pair<String, String>>): String {
    val out = StringBuilder()
    for (i in fields.indices) {
        if (i > 0) out.append('&')
        out.append(encode(fields[i].first))
        out.append('=')
        out.append(encode(fields[i].second))
    }
    return out.toString()
}

private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

private fun groupCode(code: String): String {
    val out = StringBuilder()
    for (i in code.indices) {
        if (i > 0 && i % 3 == 0) out.append(' ')
        out.append(code[i])
    }
    return out.toString()
}
