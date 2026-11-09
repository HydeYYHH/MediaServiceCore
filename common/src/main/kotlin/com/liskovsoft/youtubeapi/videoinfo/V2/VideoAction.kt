package com.liskovsoft.youtubeapi.videoinfo.V2

// YouTube uses the same neutral-rating endpoint to remove a like or a dislike.
enum class VideoVote(val endpoint: String) { LIKE("like/like"), UNLIKE("like/removelike"), DISLIKE("like/dislike"), UNDISLIKE("like/removelike") }
enum class ActionState { CONFIRMED, UNCONFIRMED }

/** Returned entities prove only the kind of mutation that requested them. */
internal sealed class ActionEntityScope {
    object Generic : ActionEntityScope()
    object None : ActionEntityScope()
    data class Comment(val id: String) : ActionEntityScope()
}
data class ActionReply @JvmOverloads constructor(val status: String?, val body: String, val state: ActionState = ActionState.UNCONFIRMED,
    val httpStatus: Int? = null, val responseShape: String? = null, val metadata: Map<String, String> = emptyMap()) {
    val confirmed: Boolean get() = state == ActionState.CONFIRMED
    override fun toString() = "ActionReply(status=$status, state=$state)"
}
internal fun actionReply(root: Json.Obj, confirmsState: Boolean = false,
    entityScope: ActionEntityScope = ActionEntityScope.Generic): ReadResult<ActionReply> {
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
    fun matchingComment(id: String?) = !id.isNullOrBlank() && when (entityScope) {
        ActionEntityScope.Generic -> true
        ActionEntityScope.None -> false
        is ActionEntityScope.Comment -> id == entityScope.id
    }
    val entity = root.objects("commentRenderer").any { matchingComment(it.text("commentId")) }
        || root.objects("commentEntityPayload").any { matchingComment(it.obj("properties")?.text("commentId")) }
        || entityScope == ActionEntityScope.Generic && (
            root.objects("playlistEditVideoAddedResultData").any { !it.text("setVideoId").isNullOrBlank() }
                || !root.text("playlistId").isNullOrBlank())
    fun succeeded(result: Json.Obj) = result.text("status")?.let { it in success } ?: (result.bool("success") == true)
    val confirmed = if (rawResults.any { it !is Json.Obj }) false
        else if (root.text("status")?.let { it !in success } == true) false
        else if (results.isNotEmpty()) results.all(::succeeded)
        else root.bool("success") == true || status in success || entity || confirmsState
    val shapes = listOf("responseContext", "actions", "onResponseReceivedActions", "onResponseReceivedEndpoints",
        "frameworkUpdates", "actionResults", "actionResult", "status", "success", "commentRenderer",
        "commentEntityPayload", "playlistId").filter(root.fields::containsKey)
    return ReadResult(ActionReply(status, root.encode(), if (confirmed) ActionState.CONFIRMED else ActionState.UNCONFIRMED,
        responseShape = shapes.joinToString(",").ifEmpty { "unrecognized" }))
}
internal fun signedAction(endpoint: String, fields: String, context: RequestContext, http: HttpClient,
    expected: ExpectedViewerState? = null): ReadResult<ActionReply> {
    if (!context.signedIn) return ReadResult(error = RequestError(RequestFailure.AUTH, "sign_in_required"))
    return requestAction(youtubeQuery(YOUTUBE_API + endpoint, fields, context), http, expected)
}

internal fun requestAction(query: PlayerQuery, http: HttpClient, expected: ExpectedViewerState? = null,
    entityScope: ActionEntityScope = ActionEntityScope.Generic): ReadResult<ActionReply> {
    val prepared = withTvActionContext(query)
    var status: Int? = null
    val transport = object : HttpClient {
        override fun execute(request: HttpRequest): HttpReply = http.execute(request).also { status = it.status }
    }
    val reply = requestObject(prepared, transport)
    if (!reply.ok) return ReadResult(error = reply.error?.let { it.copy(status = it.status ?: status) })
    val root = requireNotNull(reply.value)
    val observed = expected?.let { viewerEntityState(root, it.kind, it.target) }
    val confirmsState = expected != null && observed?.mutation == "replace" && observed.state == expected.state
    val action = actionReply(root, confirmsState, if (expected != null) ActionEntityScope.None else entityScope)
    val metadata = actionRequestMetadata(prepared) + actionResponseMetadata(root) + if (expected == null) emptyMap() else
        (observed ?: ViewerEntityState(expected.kind, null, "missing")).metadata()
    return if (action.ok) ReadResult(requireNotNull(action.value).copy(httpStatus = status, metadata = metadata))
        else ReadResult(error = action.error?.let { it.copy(status = it.status ?: status) })
}
fun voteVideo(videoId: String, vote: VideoVote, context: RequestContext, http: HttpClient = JvmHttpClient()): ReadResult<ActionReply> {
    if (!videoIdAccepted(videoId)) return ReadResult(error = RequestError(RequestFailure.INVALID, "invalid_video_id"))
    val expected = when (vote) {
        VideoVote.LIKE -> "LIKE"
        VideoVote.DISLIKE -> "DISLIKE"
        VideoVote.UNLIKE, VideoVote.UNDISLIKE -> "INDIFFERENT"
    }
    return signedAction(vote.endpoint, "\"target\":{${payload("videoId" to videoId)}}", context, http,
        ExpectedViewerState(ViewerEntityKind.RATING, videoId, expected))
}
fun subscribeChannel(channelId: String, subscribed: Boolean, context: RequestContext, params: String? = null,
    http: HttpClient = JvmHttpClient()): ReadResult<ActionReply> {
    if (!videoIdAccepted(channelId)) return ReadResult(error = RequestError(RequestFailure.INVALID, "invalid_channel_id"))
    return signedAction("subscription/" + if (subscribed) "subscribe" else "unsubscribe",
        "\"channelIds\":[${jsonQuote(channelId)}],"+payload("params" to (params ?: "")), context, http,
        ExpectedViewerState(ViewerEntityKind.SUBSCRIPTION, channelId, subscribed.toString()))
}
