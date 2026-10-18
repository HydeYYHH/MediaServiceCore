package com.liskovsoft.youtubeapi.videoinfo.V2

private const val CHOICES_URL =
    "https://www.youtube.com/youtubei/v1/playlist/get_add_to_playlist?prettyPrint=false"
private const val EDIT_URL =
    "https://www.youtube.com/youtubei/v1/browse/edit_playlist?prettyPrint=false"
private const val CREATE_URL =
    "https://www.youtube.com/youtubei/v1/playlist/create?prettyPrint=false"
private const val DELETE_URL =
    "https://www.youtube.com/youtubei/v1/playlist/delete?prettyPrint=false"
private const val SAVE_URL =
    "https://www.youtube.com/youtubei/v1/like/like?prettyPrint=false"
private const val FORGET_URL =
    "https://www.youtube.com/youtubei/v1/like/removelike?prettyPrint=false"

private val failed = PlaylistWrite(false, null)

data class PlaylistChoice(val id: String, val title: String?, val contains: Boolean)

data class PlaylistWrite(val ok: Boolean, val playlistId: String?)

fun playlistChoicesQuery(videoId: String?, visitor: String?): PlayerQuery {
    if (videoId == null || !videoIdAccepted(videoId)) return PlayerQuery("", emptyMap(), "")
    return webQuery(CHOICES_URL, """"videoIds":["${escapeJson(videoId)}"]""", visitor)
}

fun playlistChoices(body: String): List<PlaylistChoice> {
    val contents = jsonObject(body)?.fields?.get("contents") as? Json.Arr ?: return emptyList()
    val playlists = (contents.items.firstOrNull() as? Json.Obj)
        ?.obj("addToPlaylistRenderer")
        ?.fields?.get("playlists") as? Json.Arr ?: return emptyList()
    val choices = ArrayList<PlaylistChoice>()
    for (item in playlists.items) {
        val option = (item as? Json.Obj)?.obj("playlistAddToOptionRenderer") ?: continue
        val id = option.text("playlistId")?.takeIf { it.isNotEmpty() } ?: continue
        val title = displayText(option.obj("title"))
        choices.add(PlaylistChoice(id, title, option.text("containsSelectedVideos") == "ALL"))
    }
    return choices
}

fun addVideo(
    playlistId: String?,
    videoId: String?,
    visitor: String?,
    post: (PlayerQuery) -> Pair<Int, String>
): PlaylistWrite {
    if (!accepted(playlistId) || !accepted(videoId)) return failed
    val payload = """"playlistId":"${escapeJson(playlistId!!)}","actions":[{"addedVideoId":"${escapeJson(videoId!!)}","action":"ACTION_ADD_VIDEO"}]"""
    return sendPlaylist(webQuery(EDIT_URL, payload, visitor), post)
}

fun removeVideo(
    playlistId: String?,
    videoId: String?,
    visitor: String?,
    post: (PlayerQuery) -> Pair<Int, String>
): PlaylistWrite {
    if (!accepted(playlistId) || !accepted(videoId)) return failed
    val payload = """"playlistId":"${escapeJson(playlistId!!)}","actions":[{"removedVideoId":"${escapeJson(videoId!!)}","action":"ACTION_REMOVE_VIDEO_BY_VIDEO_ID"}]"""
    return sendPlaylist(webQuery(EDIT_URL, payload, visitor), post)
}

fun renamePlaylist(
    playlistId: String?,
    title: String?,
    visitor: String?,
    post: (PlayerQuery) -> Pair<Int, String>
): PlaylistWrite {
    if (!accepted(playlistId) || title.isNullOrBlank()) return failed
    val payload = """"playlistId":"${escapeJson(playlistId!!)}","actions":[{"playlistName":"${escapeJson(title)}","action":"ACTION_SET_PLAYLIST_NAME"}]"""
    return sendPlaylist(webQuery(EDIT_URL, payload, visitor), post)
}

fun orderPlaylist(
    playlistId: String?,
    order: Int,
    visitor: String?,
    post: (PlayerQuery) -> Pair<Int, String>
): PlaylistWrite {
    if (!accepted(playlistId)) return failed
    val payload = """"playlistId":"${escapeJson(playlistId!!)}","actions":[{"playlistVideoOrder":"$order","action":"ACTION_SET_PLAYLIST_VIDEO_ORDER"}]"""
    return sendPlaylist(webQuery(EDIT_URL, payload, visitor), post)
}

fun createPlaylist(
    title: String?,
    videoId: String?,
    visitor: String?,
    post: (PlayerQuery) -> Pair<Int, String>
): PlaylistWrite {
    if (title.isNullOrBlank()) return failed
    if (videoId != null && videoId.isNotEmpty() && !videoIdAccepted(videoId)) return failed
    val payload = if (videoId.isNullOrEmpty()) """"title":"${escapeJson(title)}""""
    else """"title":"${escapeJson(title)}","videoIds":["${escapeJson(videoId)}"]"""
    return sendPlaylist(webQuery(CREATE_URL, payload, visitor), post)
}

fun deletePlaylist(
    playlistId: String?,
    visitor: String?,
    post: (PlayerQuery) -> Pair<Int, String>
): PlaylistWrite {
    if (!accepted(playlistId)) return failed
    return sendPlaylist(webQuery(DELETE_URL, """"playlistId":"${escapeJson(playlistId!!)}"""", visitor), post)
}

fun savePlaylist(
    playlistId: String?,
    visitor: String?,
    post: (PlayerQuery) -> Pair<Int, String>
): PlaylistWrite = targetPlaylist(SAVE_URL, playlistId, visitor, post)

fun forgetPlaylist(
    playlistId: String?,
    visitor: String?,
    post: (PlayerQuery) -> Pair<Int, String>
): PlaylistWrite = targetPlaylist(FORGET_URL, playlistId, visitor, post)

private fun targetPlaylist(
    url: String,
    playlistId: String?,
    visitor: String?,
    post: (PlayerQuery) -> Pair<Int, String>
): PlaylistWrite {
    if (!accepted(playlistId)) return failed
    return sendPlaylist(webQuery(url, """"target":{"playlistId":"${escapeJson(playlistId!!)}"}""", visitor), post)
}

private fun sendPlaylist(query: PlayerQuery, post: (PlayerQuery) -> Pair<Int, String>): PlaylistWrite {
    if (query.url.isEmpty()) return failed
    val http = try {
        post(query)
    } catch (failure: Exception) {
        return failed
    }
    if (http.first != 200) return failed
    if (http.second.isNotBlank() && jsonObject(http.second)?.let { actionReply(it).ok } != true) return failed
    val id = jsonObject(http.second)?.let { firstNamed(it, "playlistId") }
    return PlaylistWrite(true, id)
}

private fun accepted(id: String?) = id != null && videoIdAccepted(id)

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

private fun firstNamed(node: Json, name: String): String? = when (node) {
    is Json.Obj -> {
        val value = node.text(name)
        if (value != null && value.isNotEmpty()) value
        else node.fields.values.asSequence().mapNotNull { firstNamed(it, name) }.firstOrNull()
    }
    is Json.Arr -> node.items.asSequence().mapNotNull { firstNamed(it, name) }.firstOrNull()
    else -> null
}

private fun escapeJson(value: String): String = jsonEscape(value)
