package com.liskovsoft.youtubeapi.videoinfo.V2

internal class JsonReader(private val text: String) {
    private var i = 0
    private var depth = 0
    fun parse(): Json {
        val result = value()
        skip()
        if (i != text.length) throw JsonEnd()
        return result
    }
    private fun value(): Json {
        skip()
        if (i >= text.length || ++depth > 128) throw JsonEnd()
        try {
            return when (text[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> Json.Str(str())
                't' -> literal("true", Json.Bool(true))
                'f' -> literal("false", Json.Bool(false))
                'n' -> literal("null", Json.Null)
                else -> number()
            }
        } finally { depth-- }
    }
    private fun obj(): Json.Obj {
        expect('{')
        val fields = LinkedHashMap<String, Json>()
        skip()
        if (take('}')) return Json.Obj(fields)
        while (true) {
            skip()
            val key = str()
            expect(':')
            fields[key] = value()
            skip()
            if (take('}')) return Json.Obj(fields)
            expect(',')
        }
    }
    private fun arr(): Json.Arr {
        expect('[')
        val items = ArrayList<Json>()
        skip()
        if (take(']')) return Json.Arr(items)
        while (true) {
            items.add(value())
            skip()
            if (take(']')) return Json.Arr(items)
            expect(',')
        }
    }
    private fun str(): String {
        expect('"')
        val out = StringBuilder()
        while (i < text.length) {
            val c = text[i++]
            if (c == '"') return out.toString()
            if (c < ' ') throw JsonEnd()
            if (c != '\\') { out.append(c); continue }
            if (i == text.length) throw JsonEnd()
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
                    val hex = text.substring(i, i + 4)
                    if (hex.any { it !in "0123456789abcdefABCDEF" }) throw JsonEnd()
                    out.append(hex.toInt(16).toChar())
                    i += 4
                }
                else -> throw JsonEnd()
            }
        }
        throw JsonEnd()
    }
    private fun number(): Json {
        val start = i
        take('-')
        if (!take('0')) digits()
        if (take('.')) digits()
        if (take('e') || take('E')) {
            if (!take('+')) take('-')
            digits()
        }
        return Json.Num(text.substring(start, i))
    }
    private fun digits() {
        val start = i
        while (i < text.length && text[i] in '0'..'9') i++
        if (start == i) throw JsonEnd()
    }
    private fun literal(word: String, result: Json): Json {
        if (!text.startsWith(word, i)) throw JsonEnd()
        i += word.length
        return result
    }
    private fun skip() { while (i < text.length && text[i] in " \r\n\t") i++ }
    private fun take(c: Char): Boolean {
        if (i >= text.length || text[i] != c) return false
        i++
        return true
    }
    private fun expect(c: Char) { skip(); if (!take(c)) throw JsonEnd() }
}

internal fun jsonValue(text: String): Json? = try { JsonReader(text).parse() } catch (_: JsonEnd) { null }
internal fun jsonQuote(text: String): String = buildString {
    append('"')
    for (c in text) when (c) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> if (c < ' ') append("\\u%04x".format(c.toInt())) else append(c)
    }
    append('"')
}
internal fun Json.Obj.arr(name: String): List<Json> = (fields[name] as? Json.Arr)?.items.orEmpty()
internal fun jsonEscape(text: String): String = jsonQuote(text).let { it.substring(1, it.length - 1) }
internal fun Json.Obj.scalar(name: String): String? = when (val v = fields[name]) {
    is Json.Str -> v.value
    is Json.Num -> v.text
    else -> null
}
internal fun Json.Obj.long(name: String) = scalar(name)?.toLongOrNull()
internal fun Json.Obj.int(name: String) = scalar(name)?.toIntOrNull()
internal fun Json.Obj.path(vararg names: String): Json.Obj? {
    var node: Json.Obj = this
    for (name in names) node = node.obj(name) ?: return null
    return node
}
internal fun Json.objects(name: String): List<Json.Obj> {
    val result = ArrayList<Json.Obj>()
    fun visit(node: Json) {
        when (node) {
            is Json.Obj -> {
                node.obj(name)?.let { result.add(it) }
                node.fields.values.forEach { visit(it) }
            }
            is Json.Arr -> node.items.forEach { visit(it) }
            else -> Unit
        }
    }
    visit(this)
    return result
}
internal fun Json.Obj.display(name: String): String? = obj(name)?.let { text ->
    text.text("simpleText") ?: text.arr("runs").mapNotNull { (it as? Json.Obj)?.text("text") }.joinToString("").takeIf { it.isNotEmpty() }
}
internal fun Json.Obj.thumbnail(name: String): String? =
    obj(name)?.arr("thumbnails")?.mapNotNull { (it as? Json.Obj)?.text("url") }?.lastOrNull()
