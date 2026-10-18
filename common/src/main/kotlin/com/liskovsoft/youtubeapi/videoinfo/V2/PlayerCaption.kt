package com.liskovsoft.youtubeapi.videoinfo.V2

import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

data class PlayerCaption(val url: String?, val language: String?, val name: String?, val id: String?,
    val automatic: Boolean, val translatable: Boolean, val translated: Boolean = false)
data class CaptionLanguage(val code: String, val name: String?)
fun playerCaptions(body: String): List<PlayerCaption> {
    val root = jsonObject(body)?.path("captions", "playerCaptionsTracklistRenderer") ?: return emptyList()
    return root.arr("captionTracks").mapNotNull { it as? Json.Obj }.map {
        PlayerCaption(it.text("baseUrl"), it.text("languageCode"), it.display("name"), it.text("vssId"),
            it.text("kind") == "asr", it.bool("isTranslatable") == true)
    }
}
fun captionLanguages(body: String): List<CaptionLanguage> {
    val root = jsonObject(body)?.path("captions", "playerCaptionsTracklistRenderer") ?: return emptyList()
    return root.arr("translationLanguages").mapNotNull { it as? Json.Obj }.mapNotNull {
        it.text("languageCode")?.let { code -> CaptionLanguage(code, it.display("languageName")) }
    }.distinctBy { it.code.toLowerCase(Locale.ROOT) }
}
fun translateCaptions(tracks: List<PlayerCaption>, languages: List<CaptionLanguage>): List<PlayerCaption> {
    if (tracks.isEmpty() || languages.isEmpty()) return tracks
    val origin = tracks.firstOrNull { it.automatic && it.translatable && it.url != null }
        ?: tracks.firstOrNull { it.translatable && it.url != null } ?: return tracks
    val known = tracks.mapNotNull { it.language?.toLowerCase(Locale.ROOT) }.toMutableSet()
    val result = tracks.toMutableList()
    for (language in languages) {
        if (!known.add(language.code.toLowerCase(Locale.ROOT))) continue
        result.add(origin.copy(url = urlWithParameter(origin.url!!, "tlang", language.code),
            language = language.code, name = language.name, translated = true))
    }
    return result
}
internal fun urlParameter(url: String, name: String): String? = url.substringBefore('#').substringAfter('?', "")
    .split('&').firstOrNull { it.substringBefore('=') == name }?.substringAfter('=', "")?.let {
        try { URLDecoder.decode(it, "UTF-8") } catch (_: IllegalArgumentException) { null }
    }
internal fun urlWithParameter(url: String, name: String, value: String): String {
    val main = url.substringBefore('#')
    val fragment = if (url.contains('#')) "#" + url.substringAfter('#') else ""
    val query = main.substringAfter('?', "").split('&').filter { it.isNotEmpty() && it.substringBefore('=') != name }
    val encoded = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    return main.substringBefore('?') + "?" + (query + (name + "=" + encoded)).joinToString("&") + fragment
}
