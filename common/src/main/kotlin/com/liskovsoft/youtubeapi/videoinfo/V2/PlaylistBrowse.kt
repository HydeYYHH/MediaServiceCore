package com.liskovsoft.youtubeapi.videoinfo.V2

private const val BROWSE_URL = "https://www.youtube.com/youtubei/v1/browse?prettyPrint=false"

private val emptyPage = PlaylistPage(null, null, emptyList(), null)

data class PlaylistVideo(
    val videoId: String,
    val title: String?,
    val author: String?,
    val length: String?,
    val index: String?,
    val thumbnail: String?
)

data class PlaylistPage(
    val title: String?,
    val owner: String?,
    val videos: List<PlaylistVideo>,
    val nextKey: String?
)

fun playlistBrowseQuery(playlistId: String?, continuation: String?, visitor: String?): PlayerQuery {
    if (!continuation.isNullOrEmpty()) {
        return webQuery(BROWSE_URL, """"continuation":"${escapeJson(continuation)}"""", visitor)
    }
    if (playlistId == null || !videoIdAccepted(playlistId)) return PlayerQuery("", emptyMap(), "")
    return webQuery(BROWSE_URL, """"browseId":"VL${escapeJson(playlistId)}"""", visitor)
}

fun playlistPage(body: String): PlaylistPage {
    val root = jsonObject(body) ?: return emptyPage
    val header = findHeader(root)
    val videos = ArrayList<PlaylistVideo>()
    collectVideos(root, videos)
    return PlaylistPage(
        displayText(header?.obj("title")),
        displayText(header?.obj("ownerText")),
        videos,
        listContinuation(root) ?: appendContinuation(root)
    )
}

fun loadPlaylist(
    playlistId: String,
    visitor: String?,
    post: (PlayerQuery) -> Pair<Int, String>
): PlaylistPage {
    val query = playlistBrowseQuery(playlistId, null, visitor)
    if (query.url.isEmpty()) return emptyPage
    val http = try {
        post(query)
    } catch (failure: Exception) {
        return emptyPage
    }
    if (http.first != 200) return emptyPage
    return playlistPage(http.second)
}

private fun collectVideos(node: Json, videos: ArrayList<PlaylistVideo>) {
    when (node) {
        is Json.Obj -> {
            val renderer = node.obj("playlistVideoRenderer")
            val videoId = renderer?.text("videoId")?.takeIf { it.isNotEmpty() }
            if (renderer != null && videoId != null) videos.add(playlistVideo(renderer, videoId))
            for (child in node.fields.values) collectVideos(child, videos)
        }
        is Json.Arr -> for (child in node.items) collectVideos(child, videos)
        else -> return
    }
}

private fun playlistVideo(renderer: Json.Obj, videoId: String): PlaylistVideo {
    return PlaylistVideo(
        videoId,
        displayText(renderer.obj("title")),
        displayText(renderer.obj("shortBylineText")) ?: displayText(renderer.obj("longBylineText")),
        displayText(renderer.obj("lengthText")),
        videoIndex(renderer),
        firstThumbnail(renderer.obj("thumbnail"))
    )
}

private fun videoIndex(renderer: Json.Obj): String? {
    val index = renderer.fields["index"]
    if (index is Json.Obj) {
        val text = displayText(index)
        if (text != null) return text
    }
    if (index is Json.Num) {
        val text = wholeText(index.text)
        if (text != null) return text
    }
    val watch = renderer.obj("navigationEndpoint")?.obj("watchEndpoint") ?: return null
    val number = wholeNumber(watch, "index") ?: return null
    return number.toString()
}

private fun listContinuation(node: Json): String? = when (node) {
    is Json.Obj -> {
        val list = node.obj("playlistVideoListRenderer")
        val token = if (list == null) null else continuationIn(list.fields["contents"])
        if (token != null) token
        else node.fields.values.asSequence().mapNotNull { listContinuation(it) }.firstOrNull()
    }
    is Json.Arr -> node.items.asSequence().mapNotNull { listContinuation(it) }.firstOrNull()
    else -> null
}

private fun appendContinuation(node: Json): String? = when (node) {
    is Json.Obj -> {
        val action = node.obj("appendContinuationItemsAction")
        val token = if (action == null) null else continuationIn(action.fields["continuationItems"])
        if (token != null) token
        else node.fields.values.asSequence().mapNotNull { appendContinuation(it) }.firstOrNull()
    }
    is Json.Arr -> node.items.asSequence().mapNotNull { appendContinuation(it) }.firstOrNull()
    else -> null
}

private fun continuationIn(node: Json?): String? {
    val items = node as? Json.Arr ?: return null
    for (item in items.items) {
        val token = (item as? Json.Obj)
            ?.obj("continuationItemRenderer")
            ?.obj("continuationEndpoint")
            ?.obj("continuationCommand")
            ?.text("token")
        if (!token.isNullOrEmpty()) return token
    }
    return null
}

private fun findHeader(node: Json): Json.Obj? = when (node) {
    is Json.Obj -> node.obj("playlistHeaderRenderer")
        ?: node.fields.values.asSequence().mapNotNull { findHeader(it) }.firstOrNull()
    is Json.Arr -> node.items.asSequence().mapNotNull { findHeader(it) }.firstOrNull()
    else -> null
}

private fun displayText(node: Json.Obj?): String? {
    if (node == null) return null
    val simple = node.text("simpleText")
    if (simple != null && simple.isNotEmpty()) return simple
    val runs = node.fields["runs"] as? Json.Arr ?: return null
    val out = StringBuilder()
    for (run in runs.items) {
        val text = (run as? Json.Obj)?.text("text")
        if (text != null) out.append(text)
    }
    return if (out.isEmpty()) null else out.toString()
}

private fun firstThumbnail(node: Json.Obj?): String? {
    val thumbs = node?.fields?.get("thumbnails") as? Json.Arr ?: return null
    for (item in thumbs.items) {
        val url = (item as? Json.Obj)?.text("url")
        if (url != null && url.isNotEmpty()) return url
    }
    return null
}

private fun wholeNumber(node: Json.Obj, name: String): Int? {
    val number = node.fields[name] as? Json.Num ?: return null
    return wholeText(number.text)?.toIntOrNull()
}

private fun wholeText(text: String): String? {
    val digits = if (text.startsWith("-")) text.substring(1) else text
    if (digits.isEmpty() || digits.any { it !in '0'..'9' }) return null
    return text
}

private fun escapeJson(value: String): String {
    val out = StringBuilder()
    for (c in value) {
        when (c) {
            '\\' -> out.append("\\\\")
            '"' -> out.append("\\\"")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            else -> out.append(c)
        }
    }
    return out.toString()
}
