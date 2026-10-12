package com.liskovsoft.youtubeapi.videoinfo.V2

fun playerAnswer(body: String): PlayerAnswer {
    val root = try {
        JsonReader(body.trim()).parse() as? Json.Obj
    } catch (failure: JsonEnd) {
        null
    } ?: return emptyAnswer()
    val playability = root.obj("playabilityStatus")
    val streaming = root.obj("streamingData")
    val details = root.obj("videoDetails")
    val muxedFormats = streaming?.formats("formats") ?: emptyList()
    val adaptiveFormats = streaming?.formats("adaptiveFormats") ?: emptyList()
    val muxedUrls = plainUrls(muxedFormats)
    val adaptiveUrls = plainUrls(adaptiveFormats)
    val hlsUrl = streaming?.text("hlsManifestUrl")?.takeIf { it.isNotEmpty() }
    val live = details?.bool("isLive") == true || playability?.fields?.containsKey("liveStreamability") == true
    return PlayerAnswer(
        false,
        playability?.text("status"),
        playability?.text("reason"),
        muxedUrls.isNotEmpty() || adaptiveUrls.isNotEmpty(),
        hlsUrl != null,
        muxedUrls.isNotEmpty(),
        live,
        hlsUrl,
        muxedUrls,
        adaptiveUrls,
        muxedFormats,
        adaptiveFormats
    )
}

private fun emptyAnswer() = PlayerAnswer(false, null, null, false, false, false, false)

private sealed class Json {
    object Null : Json()
    class Bool(val value: Boolean) : Json()
    class Str(val value: String) : Json()
    class Num(val text: String) : Json()
    class Obj(val fields: Map<String, Json>) : Json()
    class Arr(val items: List<Json>) : Json()
}

private fun Json.Obj.obj(name: String) = fields[name] as? Json.Obj

private fun Json.Obj.text(name: String) = (fields[name] as? Json.Str)?.value

private fun Json.Obj.bool(name: String) = (fields[name] as? Json.Bool)?.value

private fun Json.Obj.itag(): Int? {
    val text = (fields["itag"] as? Json.Num)?.text ?: return null
    val digits = if (text.startsWith("-")) text.substring(1) else text
    if (digits.isEmpty() || digits.any { it !in '0'..'9' }) return null
    return text.toIntOrNull()
}

private fun Json.Obj.formats(name: String): List<PlayerFormat> {
    val items = (fields[name] as? Json.Arr)?.items ?: return emptyList()
    val formats = ArrayList<PlayerFormat>()
    for (item in items) {
        val format = item as? Json.Obj ?: continue
        val url = format.text("url")?.takeIf { it.isNotEmpty() }
        val cipher = format.text("cipher")?.takeIf { it.isNotEmpty() }
            ?: format.text("signatureCipher")?.takeIf { it.isNotEmpty() }
        if (url == null && cipher == null) continue
        formats.add(PlayerFormat(format.itag(), format.text("mimeType")?.takeIf { it.isNotEmpty() }, url, cipher))
    }
    return formats
}

private fun plainUrls(formats: List<PlayerFormat>): List<String> {
    val urls = ArrayList<String>()
    for (format in formats) {
        val url = format.url
        if (url != null) urls.add(url)
    }
    return urls
}

private class JsonEnd : RuntimeException()

private class JsonReader(private val text: String) {
    private var i = 0

    fun parse(): Json {
        skip()
        if (i >= text.length) throw JsonEnd()
        return when (text[i]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> Json.Str(str())
            't' -> literal("true", Json.Bool(true))
            'f' -> literal("false", Json.Bool(false))
            'n' -> literal("null", Json.Null)
            else -> num()
        }
    }

    private fun obj(): Json.Obj {
        expect('{')
        val fields = LinkedHashMap<String, Json>()
        skip()
        if (peek('}')) return Json.Obj(fields)
        while (true) {
            skip()
            val name = str()
            skip()
            expect(':')
            fields[name] = parse()
            skip()
            if (peek('}')) return Json.Obj(fields)
            expect(',')
        }
    }

    private fun arr(): Json.Arr {
        expect('[')
        val items = ArrayList<Json>()
        skip()
        if (peek(']')) return Json.Arr(items)
        while (true) {
            items.add(parse())
            skip()
            if (peek(']')) return Json.Arr(items)
            expect(',')
        }
    }

    private fun str(): String {
        expect('"')
        val out = StringBuilder()
        while (i < text.length) {
            val c = text[i++]
            if (c == '"') return out.toString()
            if (c != '\\') {
                out.append(c)
                continue
            }
            if (i >= text.length) throw JsonEnd()
            when (text[i++]) {
                '"' -> out.append('"')
                '\\' -> out.append('\\')
                '/' -> out.append('/')
                'b' -> out.append('\b')
                'f' -> out.append('\u000c')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'u' -> {
                    if (i + 4 > text.length) throw JsonEnd()
                    out.append(text.substring(i, i + 4).toInt(16).toChar())
                    i += 4
                }
                else -> throw JsonEnd()
            }
        }
        throw JsonEnd()
    }

    private fun num(): Json {
        val start = i
        if (peek('-')) i++
        while (i < text.length && text[i] in '0'..'9') i++
        if (i < text.length && text[i] == '.') {
            i++
            while (i < text.length && text[i] in '0'..'9') i++
        }
        if (i < text.length && (text[i] == 'e' || text[i] == 'E')) {
            i++
            if (i < text.length && (text[i] == '+' || text[i] == '-')) i++
            while (i < text.length && text[i] in '0'..'9') i++
        }
        if (i == start) throw JsonEnd()
        return Json.Num(text.substring(start, i))
    }

    private fun literal(token: String, value: Json): Json {
        if (!text.startsWith(token, i)) throw JsonEnd()
        i += token.length
        return value
    }

    private fun expect(c: Char) {
        skip()
        if (i >= text.length || text[i] != c) throw JsonEnd()
        i++
    }

    private fun peek(c: Char): Boolean {
        if (i >= text.length || text[i] != c) return false
        i++
        return true
    }

    private fun skip() {
        while (i < text.length && text[i].isWhitespace()) i++
    }
}
