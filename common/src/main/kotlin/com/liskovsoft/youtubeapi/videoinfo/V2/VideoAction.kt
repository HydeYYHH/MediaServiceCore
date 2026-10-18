package com.liskovsoft.youtubeapi.videoinfo.V2

enum class VideoVote(val endpoint: String) { LIKE("like/like"), UNLIKE("like/removelike"), DISLIKE("like/dislike"), UNDISLIKE("like/removedislike") }
enum class ActionState { CONFIRMED, UNCONFIRMED }
data class ActionReply(val status: String?, val body: String, val state: ActionState = ActionState.UNCONFIRMED) {
    val confirmed: Boolean get() = state == ActionState.CONFIRMED
    override fun toString() = "ActionReply(status=$status, state=$state)"
}
internal fun actionReply(root: Json.Obj): ReadResult<ActionReply> {
    val status = root.text("status")
    if (root.fields.containsKey("error") || root.arr("actionResults").any {
        val result = it as? Json.Obj
        result?.text("status") in listOf("STATUS_FAILED", "STATUS_ERROR", "FAILED", "ERROR") ||
            result?.bool("success") == false || result?.fields?.containsKey("error") == true
    })
        return ReadResult(error = RequestError(RequestFailure.HTTP, "action_failed"))
    if (root.bool("success") == false || status in listOf("ERROR", "FAILED", "STATUS_FAILED", "STATUS_ERROR"))
        return ReadResult(error = RequestError(RequestFailure.HTTP, status ?: "action_failed"))
    val success = setOf("SUCCESS", "SUCCEEDED", "OK", "STATUS_SUCCESS", "STATUS_SUCCEEDED", "STATUS_OK")
    val results = root.arr("actionResults")
    val entity = root.objects("commentRenderer").any { !it.text("commentId").isNullOrBlank() }
        || root.objects("commentEntityPayload").any { !it.obj("properties")?.text("commentId").isNullOrBlank() }
        || root.objects("playlistEditVideoAddedResultData").any { !it.text("setVideoId").isNullOrBlank() }
        || !root.text("playlistId").isNullOrBlank()
    val confirmed = root.bool("success") == true || status in success || entity ||
        (results.isNotEmpty() && results.all { (it as? Json.Obj)?.text("status") in success })
    return ReadResult(ActionReply(status, root.encode(), if (confirmed) ActionState.CONFIRMED else ActionState.UNCONFIRMED))
}
internal fun signedAction(endpoint: String, fields: String, context: RequestContext, http: HttpClient): ReadResult<ActionReply> {
    if (context.accessToken == null) return ReadResult(error = RequestError(RequestFailure.AUTH, "sign_in_required"))
    val reply = requestObject(youtubeQuery(YOUTUBE_API + endpoint, fields, context), http)
    return if (reply.ok) actionReply(reply.value!!) else ReadResult(error = reply.error)
}
fun voteVideo(videoId: String, vote: VideoVote, context: RequestContext, http: HttpClient = JvmHttpClient()): ReadResult<ActionReply> {
    if (!videoIdAccepted(videoId)) return ReadResult(error = RequestError(RequestFailure.INVALID, "invalid_video_id"))
    return signedAction(vote.endpoint, "\"target\":{${payload("videoId" to videoId)}}", context, http)
}
fun subscribeChannel(channelId: String, subscribed: Boolean, context: RequestContext, params: String? = null,
    http: HttpClient = JvmHttpClient()): ReadResult<ActionReply> {
    if (!videoIdAccepted(channelId)) return ReadResult(error = RequestError(RequestFailure.INVALID, "invalid_channel_id"))
    return signedAction("subscription/" + if (subscribed) "subscribe" else "unsubscribe",
        "\"channelIds\":[${jsonQuote(channelId)}],"+payload("params" to (params ?: "")), context, http)
}
