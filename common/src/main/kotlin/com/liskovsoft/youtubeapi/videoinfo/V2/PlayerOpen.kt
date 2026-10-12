package com.liskovsoft.youtubeapi.videoinfo.V2

import java.net.URLDecoder
import java.net.URLEncoder

data class PlayerChallenge(
    val signatures: Map<String, String>,
    val throttles: Map<String, String>
)

private class Pending(
    val format: PlayerFormat,
    val baseUrl: String?,
    val signature: String?,
    val parameter: String,
    val throttle: String?
)

fun openFormats(
    answer: PlayerAnswer,
    solve: (Set<String>, Set<String>) -> PlayerChallenge
): PlayerAnswer {
    if (answer.muxedFormats.isEmpty() && answer.adaptiveFormats.isEmpty()) return answer
    val muxedPending = answer.muxedFormats.map { pending(it) }
    val adaptivePending = answer.adaptiveFormats.map { pending(it) }
    val signatures = LinkedHashSet<String>()
    val throttles = LinkedHashSet<String>()
    for (item in muxedPending + adaptivePending) {
        val signature = item.signature
        if (signature != null) signatures.add(signature)
        val throttle = item.throttle
        if (throttle != null) throttles.add(throttle)
    }
    val challenge = if (signatures.isEmpty() && throttles.isEmpty()) {
        null
    } else {
        try {
            solve(signatures, throttles)
        } catch (failure: Exception) {
            return answer
        }
    }
    val muxed = muxedPending.map { apply(it, challenge) }
    val adaptive = adaptivePending.map { apply(it, challenge) }
    val muxedUrls = urls(muxed)
    val adaptiveUrls = urls(adaptive)
    return answer.copy(
        hasDirectUrl = muxedUrls.isNotEmpty() || adaptiveUrls.isNotEmpty(),
        hasMuxed = muxedUrls.isNotEmpty(),
        muxedUrls = muxedUrls,
        adaptiveUrls = adaptiveUrls,
        muxedFormats = muxed,
        adaptiveFormats = adaptive
    )
}

private fun pending(format: PlayerFormat): Pending {
    var base = format.url
    var signature: String? = null
    var parameter = "sig"
    val cipher = format.cipher
    if (cipher != null && cipher.isNotEmpty()) {
        val params = query(cipher)
        val cipherUrl = params["url"]
        if (cipherUrl != null && cipherUrl.isNotEmpty()) base = cipherUrl
        val extracted = params["s"]
        if (extracted != null && extracted.isNotEmpty()) signature = extracted
        val name = params["sp"]
        if (name != null && name.isNotEmpty()) parameter = name
    }
    val throttle = if (base == null) null else queryParam(base, "n")
    return Pending(format, base, signature, parameter, throttle)
}

private fun apply(pending: Pending, challenge: PlayerChallenge?): PlayerFormat {
    val signature = pending.signature
    if (signature != null) {
        val solved = challenge?.signatures?.get(signature)
        if (solved.isNullOrEmpty() || pending.baseUrl == null) return pending.format.copy(url = null)
        var url: String? = appendParam(pending.baseUrl, pending.parameter, solved)
        url = replaceThrottle(url, pending.throttle, challenge)
        return pending.format.copy(url = url)
    }
    val url = replaceThrottle(pending.baseUrl, pending.throttle, challenge)
    return pending.format.copy(url = url)
}

private fun replaceThrottle(url: String?, throttle: String?, challenge: PlayerChallenge?): String? {
    if (url == null || throttle == null || challenge == null) return url
    val next = challenge.throttles[throttle]
    if (next.isNullOrEmpty()) return url
    return replaceParam(url, "n", next)
}

private fun urls(formats: List<PlayerFormat>): List<String> {
    val urls = ArrayList<String>()
    for (format in formats) {
        val url = format.url
        if (url != null && url.isNotEmpty()) urls.add(url)
    }
    return urls
}

private fun queryParam(url: String, name: String): String? {
    val hash = url.indexOf('#')
    val main = if (hash >= 0) url.substring(0, hash) else url
    val mark = main.indexOf('?')
    if (mark < 0) return null
    return query(main.substring(mark + 1))[name]?.takeIf { it.isNotEmpty() }
}

private fun query(text: String): Map<String, String> {
    val map = LinkedHashMap<String, String>()
    if (text.isEmpty()) return map
    for (part in text.split("&")) {
        if (part.isEmpty()) continue
        val eq = part.indexOf('=')
        val key = decode(if (eq < 0) part else part.substring(0, eq))
        val value = decode(if (eq < 0) "" else part.substring(eq + 1))
        map[key] = value
    }
    return map
}

private fun appendParam(url: String, name: String, value: String): String {
    val hash = url.indexOf('#')
    val main = if (hash >= 0) url.substring(0, hash) else url
    val fragment = if (hash >= 0) url.substring(hash) else ""
    val join = if (main.indexOf('?') >= 0) "&" else "?"
    return main + join + encode(name) + "=" + encode(value) + fragment
}

private fun replaceParam(url: String, name: String, value: String): String {
    val hash = url.indexOf('#')
    val main = if (hash >= 0) url.substring(0, hash) else url
    val fragment = if (hash >= 0) url.substring(hash) else ""
    val query = main.indexOf('?')
    if (query < 0) return url
    val pairs = main.substring(query + 1).split("&")
    val out = ArrayList<String>(pairs.size)
    for (pair in pairs) {
        if (pair.isEmpty()) continue
        val eq = pair.indexOf('=')
        val key = decode(if (eq < 0) pair else pair.substring(0, eq))
        if (key == name) out.add(encode(name) + "=" + encode(value))
        else out.add(pair)
    }
    return main.substring(0, query) + "?" + out.joinToString("&") + fragment
}

private fun decode(text: String): String = try {
    URLDecoder.decode(text, "UTF-8")
} catch (failure: IllegalArgumentException) {
    text
}

private fun encode(text: String): String = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
