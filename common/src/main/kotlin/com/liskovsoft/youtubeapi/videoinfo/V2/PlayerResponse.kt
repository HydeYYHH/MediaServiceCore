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

internal fun jsonObject(body: String): Json.Obj? = try {
    JsonReader(body.trim()).parse() as? Json.Obj
} catch (failure: JsonEnd) {
    null
}

internal sealed class Json {
    object Null : Json()
    class Bool(val value: Boolean) : Json()
    class Str(val value: String) : Json()
    class Num(val text: String) : Json()
    class Obj(val fields: Map<String, Json>) : Json()
    class Arr(val items: List<Json>) : Json()
}

internal fun Json.Obj.obj(name: String) = fields[name] as? Json.Obj

internal fun Json.Obj.text(name: String) = (fields[name] as? Json.Str)?.value

internal fun Json.Obj.bool(name: String) = (fields[name] as? Json.Bool)?.value

private fun Json.Obj.integer(name: String): Int? {
    val text = (fields[name] as? Json.Num)?.text ?: return null
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
        formats.add(PlayerFormat(
            format.integer("itag"),
            format.text("mimeType")?.takeIf { it.isNotEmpty() },
            url,
            cipher,
            format.integer("height"),
            format.integer("bitrate"),
            format.integer("fps")
        ))
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

internal class JsonEnd : RuntimeException()

