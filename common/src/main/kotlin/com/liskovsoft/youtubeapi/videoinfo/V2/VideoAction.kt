package com.liskovsoft.youtubeapi.videoinfo.V2

enum class VideoVote(val endpoint: String) { LIKE("like/like"), UNLIKE("like/removelike"), DISLIKE("like/dislike"), UNDISLIKE("like/removedislike") }
enum class ActionState { CONFIRMED, UNCONFIRMED }
data class ActionReply @JvmOverloads constructor(val status: String?, val body: String, val state: ActionState = ActionState.UNCONFIRMED,
    val httpStatus: Int? = null, val responseShape: String? = null) {
    val confirmed: Boolean get() = state == ActionState.CONFIRMED
    override fun toString() = "ActionReply(status=$status, state=$state)"
}
internal fun actionReply(root: Json.Obj): ReadResult<ActionReply> {
    val rawResults = root.arr("actionResults")
    val results = rawResults.mapNotNull { it as? Json.Obj } + root.objects("actionResult")
    val status = root.text("status") ?: results.firstNotNullOfOrNull { it.text("status") }
    val failures = setOf("STATUS_FAILED", "STATUS_ERROR", "FAILED", "ERROR")
    if (root.fields.containsKey("error") || results.any { result ->
        result.text("status") in failures || result.bool("success") == false || result.fields.containsKey("error")
    })
        return ReadResult(error = RequestError(RequestFailure.HTTP, "action_failed"))
    if (root.bool("success") == false || status in listOf("ERROR", "FAILED", "STATUS_FAILED", "STATUS_ERROR"))
        return ReadResult(error = RequestError(RequestFailure.HTTP, status ?: "action_failed"))
    val success = setOf("SUCCESS", "SUCCEEDED", "OK", "STATUS_SUCCESS", "STATUS_SUCCEEDED", "STATUS_OK")
    val entity = root.objects("commentRenderer").any { !it.text("commentId").isNullOrBlank() }
        || root.objects("commentEntityPayload").any { !it.obj("properties")?.text("commentId").isNullOrBlank() }
        || root.objects("playlistEditVideoAddedResultData").any { !it.text("setVideoId").isNullOrBlank() }
        || !root.text("playlistId").isNullOrBlank()
    val confirmed = if (rawResults.any { it !is Json.Obj }) false
        else if (results.isNotEmpty()) results.all { it.text("status") in success || it.bool("success") == true }
        else root.bool("success") == true || status in success || entity
    val shapes = listOf("responseContext", "actions", "onResponseReceivedActions", "onResponseReceivedEndpoints",
        "frameworkUpdates", "actionResults", "actionResult", "status", "success", "commentRenderer",
        "commentEntityPayload", "playlistId").filter(root.fields::containsKey)
    return ReadResult(ActionReply(status, root.encode(), if (confirmed) ActionState.CONFIRMED else ActionState.UNCONFIRMED,
        responseShape = shapes.joinToString(",").ifEmpty { "unrecognized" }))
}
internal fun signedAction(endpoint: String, fields: String, context: RequestContext, http: HttpClient): ReadResult<ActionReply> {
    if (!context.signedIn) return ReadResult(error = RequestError(RequestFailure.AUTH, "sign_in_required"))
    return requestAction(youtubeQuery(YOUTUBE_API + endpoint, fields, context), http)
}

internal fun requestAction(query: PlayerQuery, http: HttpClient): ReadResult<ActionReply> {
    var status: Int? = null
    val transport = object : HttpClient {
        override fun execute(request: HttpRequest): HttpReply = http.execute(request).also { status = it.status }
    }
    val reply = requestObject(query, transport)
    if (!reply.ok) return ReadResult(error = reply.error?.let { it.copy(status = it.status ?: status) })
    val action = actionReply(requireNotNull(reply.value))
    return if (action.ok) ReadResult(requireNotNull(action.value).copy(httpStatus = status))
        else ReadResult(error = action.error?.let { it.copy(status = it.status ?: status) })
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
