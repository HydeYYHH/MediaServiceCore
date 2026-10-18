package com.liskovsoft.youtubeapi.videoinfo.V2

sealed class PlaylistChange {
    data class Add(val videoId: String) : PlaylistChange()
    data class Remove(val setVideoId: String) : PlaylistChange()
    data class Rename(val title: String) : PlaylistChange()
    data class Order(val order: Int) : PlaylistChange()
    data class Move(val setVideoId: String, val precedingSetVideoId: String?) : PlaylistChange()
}
data class PlaylistActionReply(val playlistId: String?, val addedSetVideoIds: List<String>, val action: ActionReply)
private fun playlistAction(endpoint: String, fields: String, context: RequestContext, http: HttpClient): ReadResult<PlaylistActionReply> {
    val result = signedAction(endpoint, fields, context, http)
    if (!result.ok) return ReadResult(error = result.error)
    val root = jsonObject(result.value!!.body)!!
    val added = root.objects("playlistEditVideoAddedResultData")
    return ReadResult(PlaylistActionReply(root.text("playlistId") ?: added.firstOrNull()?.text("playlistId"),
        added.mapNotNull { it.text("setVideoId") }, result.value))
}
fun editPlaylist(playlistId: String, changes: List<PlaylistChange>, context: RequestContext,
    http: HttpClient = JvmHttpClient()): ReadResult<PlaylistActionReply> {
    if (!videoIdAccepted(playlistId) || changes.isEmpty()) return ReadResult(error = RequestError(RequestFailure.INVALID, "invalid_playlist_edit"))
    val actions = changes.map { change ->
        when (change) {
            is PlaylistChange.Add -> {
                if (!videoIdAccepted(change.videoId)) return ReadResult(error = RequestError(RequestFailure.INVALID, "invalid_video_id"))
                "{"+payload("action" to "ACTION_ADD_VIDEO", "addedVideoId" to change.videoId)+"}"
            }
            is PlaylistChange.Remove -> {
                if (change.setVideoId.isBlank()) return ReadResult(error = RequestError(RequestFailure.INVALID, "missing_set_video_id"))
                "{"+payload("action" to "ACTION_REMOVE_VIDEO", "setVideoId" to change.setVideoId)+"}"
            }
            is PlaylistChange.Rename -> {
                if (change.title.isBlank()) return ReadResult(error = RequestError(RequestFailure.INVALID, "missing_playlist_title"))
                "{"+payload("action" to "ACTION_SET_PLAYLIST_NAME", "playlistName" to change.title)+"}"
            }
            is PlaylistChange.Order -> "{"+payload("action" to "ACTION_SET_PLAYLIST_VIDEO_ORDER", "playlistVideoOrder" to change.order.toString())+"}"
            is PlaylistChange.Move -> "{"+payload("action" to "ACTION_MOVE_VIDEO_AFTER", "setVideoId" to change.setVideoId,
                "movedSetVideoIdPredecessor" to change.precedingSetVideoId)+"}"
        }
    }
    return playlistAction("browse/edit_playlist", payload("playlistId" to playlistId)+",\"actions\":["+actions.joinToString(",")+"]", context, http)
}
fun createPlaylist(title: String, videoId: String?, context: RequestContext, http: HttpClient = JvmHttpClient()): ReadResult<PlaylistActionReply> {
    if (title.isBlank() || (videoId != null && !videoIdAccepted(videoId))) return ReadResult(error = RequestError(RequestFailure.INVALID,"invalid_playlist"))
    return playlistAction("playlist/create", payload("title" to title) + if (videoId == null) "" else ",\"videoIds\":[${jsonQuote(videoId)}]", context, http)
}
fun deletePlaylist(playlistId: String, context: RequestContext, http: HttpClient = JvmHttpClient()) =
    playlistAction("playlist/delete", payload("playlistId" to playlistId), context, http)
fun savePlaylist(playlistId: String, saved: Boolean, context: RequestContext, http: HttpClient = JvmHttpClient()) =
    playlistAction("like/" + if (saved) "like" else "removelike", "\"target\":{${payload("playlistId" to playlistId)}}", context, http)
fun loadPlaylist(playlistId: String?, context: RequestContext, continuation: String? = null,
    http: HttpClient = JvmHttpClient()): ReadResult<PlaylistPage> {
    val query = queryInContext(playlistBrowseQuery(playlistId, continuation, context.visitor), context)
    val reply = requestObject(query, http)
    return if (reply.ok) ReadResult(playlistPage(reply.value!!.encode())) else ReadResult(error = reply.error)
}
fun loadPlaylistChoices(videoId: String, context: RequestContext, http: HttpClient = JvmHttpClient()): ReadResult<List<PlaylistChoice>> {
    val reply = requestObject(queryInContext(playlistChoicesQuery(videoId, context.visitor), context), http)
    return if (reply.ok) ReadResult(playlistChoices(reply.value!!.encode())) else ReadResult(error = reply.error)
}
