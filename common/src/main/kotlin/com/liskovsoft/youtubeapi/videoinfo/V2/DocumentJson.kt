package com.liskovsoft.youtubeapi.videoinfo.V2

/** Balanced strict JSON literal, including its exclusive end offset. No JavaScript is evaluated. */
internal fun documentJsonObject(text: String, start: Int = 0): Pair<Json.Obj, Int>? {
    var begin = start
    while (begin < text.length && text[begin].isWhitespace()) begin++
    if (text.getOrNull(begin) != '{') return null
    var depth = 0
    var quoted = false
    var escaped = false
    for (index in begin until text.length) {
        val char = text[index]
        if (quoted) {
            if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
        } else when (char) {
            '"' -> quoted = true
            '{' -> depth++
            '}' -> if (--depth == 0) return jsonObject(text.substring(begin, index + 1))?.let { it to index + 1 }
        }
    }
    return null
}

/** Reads literal ytcfg.set({...}) calls in script bodies while skipping JS strings and comments. */
internal fun documentConfigurations(html: String): List<Json.Obj> {
    return documentLiterals(html, Regex("ytcfg\\s*\\.\\s*set\\s*\\(\\s*"), ')')
}

internal fun documentPlayerResponse(html: String): Json.Obj? = documentLiterals(html,
    Regex("(?:var\\s+)?ytInitialPlayerResponse\\s*=\\s*|window\\s*\\[\\s*[\"']ytInitialPlayerResponse[\"']\\s*]\\s*=\\s*"), null).singleOrNull()

private fun documentLiterals(html: String, marker: Regex, closing: Char?): List<Json.Obj> {
    val result = ArrayList<Json.Obj>()
    val scripts = Regex("<script\\b[^>]*>(.*?)</script\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    for (script in scripts.findAll(html).map { it.groupValues[1] }) {
        // Android resets the ICU input when creating a Matcher or changing its region.
        // Scan candidates once with find(), alongside the string/comment-aware lexer.
        val matcher = marker.toPattern().matcher(script)
        var hasMarker = matcher.find()
        var index = 0
        while (index < script.length) {
            while (hasMarker && matcher.start() < index) hasMarker = matcher.find()
            val char = script[index]
            if (char == '\'' || char == '"' || char == '`') {
                val quote = char
                index++
                while (index < script.length) {
                    val next = script[index++]
                    if (next == '\\') index++ else if (next == quote) break
                }
                continue
            }
            if (script.startsWith("//", index)) {
                index = script.indexOf('\n', index + 2).takeIf { it >= 0 } ?: script.length
                continue
            }
            if (script.startsWith("/*", index)) {
                index = script.indexOf("*/", index + 2).takeIf { it >= 0 }?.plus(2) ?: script.length
                continue
            }
            if (hasMarker && matcher.start() == index &&
                (index == 0 || script[index - 1].let { !it.isLetterOrDigit() && it != '_' && it != '$' })) {
                val parsed = documentJsonObject(script, matcher.end())
                if (parsed != null) {
                    var end = parsed.second
                    while (end < script.length && script[end].isWhitespace()) end++
                    if (closing?.let { script.getOrNull(end) == it } ?: (script.getOrNull(end) in listOf(';', null))) {
                        result += parsed.first
                        index = end + 1
                        continue
                    }
                }
            }
            index++
        }
    }
    return result
}
