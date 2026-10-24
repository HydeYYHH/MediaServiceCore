package com.liskovsoft.youtubeapi.videoinfo.V2

internal fun initialDataDocument(html: String): Json.Obj? {
    val match = Regex("(?:var\\s+)?ytInitialData\\s*=\\s*").find(html) ?: return null
    val tail = html.substring(match.range.last + 1).trimStart()
    if (tail.startsWith("{") ) {
        var depth = 0
        var quoted = false
        var escaped = false
        for (i in tail.indices) {
            val c = tail[i]
            if (quoted) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '{' -> depth++
                '}' -> if (--depth == 0) return jsonObject(tail.substring(0, i + 1))
            }
        }
        return null
    }
    val quote = tail.firstOrNull()?.takeIf { it == '\'' || it == '"' } ?: return null
    val decoded = StringBuilder()
    var i = 1
    while (i < tail.length) {
        val c = tail[i++]
        if (c == quote) return jsonObject(decoded.toString())
        if (c != '\\') { decoded.append(c); continue }
        if (i >= tail.length) return null
        when (val escape = tail[i++]) {
            'x', 'u' -> {
                val count = if (escape == 'x') 2 else 4
                if (i + count > tail.length) return null
                val value = tail.substring(i, i + count).toIntOrNull(16) ?: return null
                decoded.append(value.toChar()); i += count
            }
            'n' -> decoded.append('\n')
            'r' -> decoded.append('\r')
            't' -> decoded.append('\t')
            'b' -> decoded.append('\b')
            'f' -> decoded.append('\u000C')
            '\\', '/', '\'', '"' -> decoded.append(escape)
            else -> return null
        }
    }
    return null
}

internal fun loadShortsDocument(context: RequestContext, http: HttpClient): ReadResult<BrowsePage> {
    val reply = requestBody(HttpRequest("GET", "https://m.youtube.com/shorts?hl=${java.net.URLEncoder.encode(context.language, "UTF-8")}",
        mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36")), http)
    if (!reply.ok) return ReadResult(error = reply.error)
    val root = initialDataDocument(reply.value!!) ?: return ReadResult(error = RequestError(RequestFailure.PARSE, "missing_shorts_data"))
    return readShorts(root)
}
