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
    val owned = ownedPlaylistCollection(root) ?: return emptyPage
    return playlistPage(root, owned) ?: emptyPage
}

internal fun playlistPage(root: Json.Obj, owned: OwnedPlaylistCollection): PlaylistPage? = runCatching {
    val header = findHeader(root)
    val columns = root.path("contents", "tvBrowseRenderer", "content", "tvSurfaceContentRenderer", "content", "twoColumnRenderer")
    val entity = columns?.path("leftColumn", "entityMetadataRenderer")
    val modern = root.path("header", "pageHeaderRenderer")
    PlaylistPage(
        displayText(header?.obj("title"))
            ?: root.path("metadata", "playlistMetadataRenderer")?.text("title") ?: modern?.text("pageTitle") ?: entity?.display("title"),
        displayText(header?.obj("ownerText"))
            ?: modern?.objects("avatarStackViewModel")?.firstOrNull()?.display("text")
            ?: entity?.arr("bylines")?.firstOrNull()?.objects("lineItemRenderer")?.firstNotNullOfOrNull { it.display("text") },
        owned.entries.mapNotNull { playlistEntry(it, owned.playlistId) },
        owned.nextKey
    )
}.getOrNull()

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
    val root = jsonObject(http.second) ?: return emptyPage
    val owned = ownedPlaylistCollection(root, false, playlistId) ?: return emptyPage
    return playlistPage(root, owned) ?: emptyPage
}

/** Decode one already-owned direct slot. Renderer internals never become another entry. */
private fun playlistEntry(node: Json.Obj, expectedPlaylistId: String?): PlaylistVideo? {
    node.obj("playlistVideoRenderer")?.let { renderer ->
        entryPlaylistIdentity(renderer, expectedPlaylistId)
        if ("videoId" in renderer.fields) require(renderer.fields["videoId"] is Json.Str)
        physicalEntry(renderer, "setVideoId")
        val id = renderer.text("videoId")?.takeIf(String::isNotBlank) ?: return null
        if ("navigationEndpoint" in renderer.fields) {
            val endpoint = renderer.obj("navigationEndpoint") ?: error("playlist_watch")
            if ("watchEndpoint" in endpoint.fields) {
                val watch = playlistWatch(renderer, listOf("navigationEndpoint", "watchEndpoint"), expectedPlaylistId) ?: error("playlist_watch")
                physicalEntry(watch, "playlistSetVideoId")
                if ("videoId" in watch.fields) require(watch.text("videoId") == id)
                val physical = renderer.text("setVideoId")
                val watchPhysical = watch.text("playlistSetVideoId")
                if (physical != null && watchPhysical != null) require(physical == watchPhysical)
            }
        }
        return playlistVideo(renderer, id)
    }
    node.obj("tileRenderer")?.let { tile ->
        entryPlaylistIdentity(tile, expectedPlaylistId)
        val watch = playlistWatch(tile, listOf("onSelectCommand", "watchEndpoint"), expectedPlaylistId)
        physicalEntry(tile, "setVideoId")
        if (watch != null) {
            if ("videoId" in watch.fields) require(watch.fields["videoId"] is Json.Str)
            physicalEntry(watch, "playlistSetVideoId")
        }
        val id = watch?.text("videoId") ?: return null // Known non-video TV tile.
        require(id.isNotBlank())
        if ("contentId" in tile.fields) require(tile.text("contentId") == id)
        val physical = tile.text("setVideoId")
        val watchPhysical = watch.text("playlistSetVideoId")
        if (physical != null && watchPhysical != null) require(physical == watchPhysical)
        val item = contentItems(Json.Obj(mapOf("tileRenderer" to tile))).firstOrNull { it.kind == "video" }
            ?: error("playlist_tile")
        require(item.id == id)
        return PlaylistVideo(id, item.title, item.author, item.duration,
            watch.int("index")?.plus(1)?.toString(), item.thumbnail,
            watch.text("playlistSetVideoId") ?: tile.text("setVideoId"))
    }
    node.obj("lockupViewModel")?.let { modern ->
        entryPlaylistIdentity(modern, expectedPlaylistId)
        val contextual = playlistWatch(modern, listOf("rendererContext", "commandContext", "onTap", "innertubeCommand", "watchEndpoint"), expectedPlaylistId)
        val direct = playlistWatch(modern, listOf("onTap", "innertubeCommand", "watchEndpoint"), expectedPlaylistId)
        physicalEntry(modern, "setVideoId")
        if ("contentId" in modern.fields) require(modern.fields["contentId"] is Json.Str)
        if (contextual != null && "videoId" in contextual.fields) require(contextual.fields["videoId"] is Json.Str)
        if (direct != null && "videoId" in direct.fields) require(direct.fields["videoId"] is Json.Str)
        if (contextual != null && direct != null) require(contextual == direct)
        val watch = contextual ?: direct
        val contentId = modern.text("contentId")
        val watchId = watch?.text("videoId")
        if (contentId != null && watchId != null) require(contentId == watchId)
        val id = watchId ?: contentId ?: return null
        if (watch != null) {
            physicalEntry(watch, "playlistSetVideoId")
            val physical = modern.text("setVideoId")
            val watchPhysical = watch.text("playlistSetVideoId")
            if (physical != null && watchPhysical != null) require(physical == watchPhysical)
        }
        if (modern.text("contentType")?.contains("VIDEO") != true) return null
        require(id.isNotBlank())
        val item = contentItems(Json.Obj(mapOf("lockupViewModel" to modern))).firstOrNull { it.kind == "video" }
            ?: error("playlist_lockup")
        require(item.id == id)
        return PlaylistVideo(id, item.title, item.author, item.duration,
            watch?.int("index")?.plus(1)?.toString(), item.thumbnail,
            watch?.text("playlistSetVideoId") ?: modern.text("setVideoId"))
    }
    error("playlist_entry")
}

private fun physicalEntry(node: Json.Obj, field: String) {
    if (field in node.fields) require(node.fields[field] is Json.Str && !node.text(field).isNullOrBlank())
}

private fun entryPlaylistIdentity(node: Json.Obj, expected: String?) {
    if ("playlistId" !in node.fields) return
    val declared = node.text("playlistId")?.takeIf(String::isNotBlank)?.removePrefix("VL") ?: error("playlist_entry_identity")
    if (expected != null) require(declared == expected)
}

private fun playlistWatch(node: Json.Obj, path: List<String>, expectedPlaylistId: String?): Json.Obj? {
    var current = node
    for (field in path) {
        if (field !in current.fields) return null
        current = current.obj(field) ?: error("playlist_watch")
        entryPlaylistIdentity(current, expectedPlaylistId)
        if ("commandMetadata" in current.fields) {
            val metadata = current.obj("commandMetadata") ?: error("playlist_watch_route")
            if ("webCommandMetadata" in metadata.fields) {
                val web = metadata.obj("webCommandMetadata") ?: error("playlist_watch_route")
                if ("apiUrl" in web.fields) require(web.text("apiUrl") in
                    setOf("/youtubei/v1/next", "https://www.youtube.com/youtubei/v1/next"))
            }
        }
        if (field != "watchEndpoint" && "watchEndpoint" in current.fields)
            require(current.fields.keys.none { it != "watchEndpoint" && it.endsWith("Endpoint") })
    }
    return current
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
