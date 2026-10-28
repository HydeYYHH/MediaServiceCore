package com.liskovsoft.youtubeapi.videoinfo.V2

private const val BROWSE_URL = "https://www.youtube.com/youtubei/v1/browse?prettyPrint=false"

private val emptyPage = PlaylistPage(null, null, emptyList(), null)

data class PlaylistVideo(
    val videoId: String,
    val title: String?,
    val author: String?,
    val length: String?,
    val index: String?,
    val thumbnail: String?,
    val setVideoId: String? = null
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
    val browseId = if (playlistId.startsWith("VL")) playlistId else "VL$playlistId"
    return webQuery(BROWSE_URL, """"browseId":"${escapeJson(browseId)}"""", visitor)
}

fun playlistPage(body: String): PlaylistPage {
    val root = jsonObject(body) ?: return emptyPage
    val header = findHeader(root)
    val videos = ArrayList<PlaylistVideo>()
    val columns = root.path("contents", "tvBrowseRenderer", "content", "tvSurfaceContentRenderer", "content", "twoColumnRenderer")
    val entity = columns?.path("leftColumn", "entityMetadataRenderer")
    val primary = columns?.path("rightColumn", "playlistVideoListRenderer")
        ?: root.path("contents", "twoColumnBrowseResultsRenderer")
        ?: root.path("contents", "singleColumnBrowseResultsRenderer")
        ?: root.obj("continuationContents") ?: root
    collectVideos(primary, videos)
    val modern = root.path("header", "pageHeaderRenderer")
    return PlaylistPage(
        displayText(header?.obj("title"))
            ?: root.path("metadata", "playlistMetadataRenderer")?.text("title") ?: modern?.text("pageTitle") ?: entity?.display("title"),
        displayText(header?.obj("ownerText"))
            ?: modern?.objects("avatarStackViewModel")?.firstOrNull()?.display("text")
            ?: entity?.arr("bylines")?.firstOrNull()?.objects("lineItemRenderer")?.firstNotNullOfOrNull { it.display("text") },
        videos,
        listContinuation(primary) ?: appendContinuation(root) ?: pageKey(primary)
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
            if (renderer != null && videoId != null) {
                videos.add(playlistVideo(renderer, videoId))
                return
            }
            node.obj("tileRenderer")?.let { tile ->
                val watch = tile.path("onSelectCommand", "watchEndpoint")
                val id = watch?.text("videoId")
                val item = contentItems(Json.Obj(mapOf("tileRenderer" to tile))).firstOrNull { it.kind == "video" }
                if (id != null && item != null) videos.add(PlaylistVideo(id, item.title, item.author, item.duration,
                    watch?.int("index")?.plus(1)?.toString(), item.thumbnail,
                    watch?.text("playlistSetVideoId") ?: tile.text("setVideoId")))
                return
            }
            node.obj("lockupViewModel")?.let { modern ->
                val watch = modern.path("rendererContext", "commandContext", "onTap", "innertubeCommand", "watchEndpoint")
                    ?: modern.path("onTap", "innertubeCommand", "watchEndpoint")
                val id = watch?.text("videoId") ?: modern.text("contentId")
                if (id != null && modern.text("contentType")?.contains("VIDEO") == true) {
                    val item = contentItems(Json.Obj(mapOf("lockupViewModel" to modern))).firstOrNull()
                    if (item != null) videos.add(PlaylistVideo(id, item.title, item.author, item.duration,
                        watch?.int("index")?.plus(1)?.toString(), item.thumbnail,
                        watch?.text("playlistSetVideoId") ?: modern.text("setVideoId")))
                }
                return
            }
            for ((name, child) in node.fields) {
                if (name !in setOf("menu", "header", "secondaryContents", "shelfRenderer", "engagementPanels"))
                    collectVideos(child, videos)
            }
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
        firstThumbnail(renderer.obj("thumbnail")),
        renderer.text("setVideoId") ?: renderer.path("navigationEndpoint", "watchEndpoint")?.text("playlistSetVideoId")
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

private fun escapeJson(value: String): String = jsonEscape(value)
