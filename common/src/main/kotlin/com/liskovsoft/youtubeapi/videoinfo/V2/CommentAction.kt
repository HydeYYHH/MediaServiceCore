package com.liskovsoft.youtubeapi.videoinfo.V2

data class CommentActionReply(val comment: Comment?, val action: ActionReply)
internal fun queryInContext(query: PlayerQuery, context: RequestContext): PlayerQuery {
    val root = jsonObject(query.body) ?: return PlayerQuery("", emptyMap(), "")
    val fields = root.fields.filterKeys { it != "context" }.entries.joinToString(",") { jsonQuote(it.key) + ":" + it.value.encode() }
    return youtubeQuery(query.url, fields, context)
}
fun performComment(query: PlayerQuery, context: RequestContext, http: HttpClient = JvmHttpClient()): ReadResult<CommentActionReply> {
    if (!context.signedIn) return ReadResult(error = RequestError(RequestFailure.AUTH, "sign_in_required"))
    val reply = requestAction(queryInContext(query, context), http)
    if (!reply.ok) return ReadResult(error = reply.error)
    val action = requireNotNull(reply.value)
    return ReadResult(CommentActionReply(commentPage(action.body).comments.firstOrNull(), action))
}
fun createComment(text: String?, createKey: String?, context: RequestContext, http: HttpClient = JvmHttpClient()) =
    performComment(createCommentQuery(text, createKey, context.visitor), context, http)
fun replyComment(text: String?, replyCreateKey: String?, context: RequestContext, http: HttpClient = JvmHttpClient()) =
    performComment(replyCommentQuery(text, replyCreateKey, context.visitor), context, http)
fun createVideoComment(videoId: String, text: String?, createKey: String?, context: RequestContext,
    http: HttpClient = JvmHttpClient()): ReadResult<CommentActionReply> {
    if (!videoIdAccepted(videoId)) return ReadResult(error = RequestError(RequestFailure.INVALID, "invalid_video_id"))
    return createComment(text, createKey?.takeIf(String::isNotBlank) ?: commentCreationParameters(videoId), context, http)
}
fun replyVideoComment(videoId: String, parentId: String, text: String?, replyCreateKey: String?, context: RequestContext,
    http: HttpClient = JvmHttpClient()): ReadResult<CommentActionReply> {
    if (!videoIdAccepted(videoId) || parentId.isBlank() || parentId.length > 1024)
        return ReadResult(error = RequestError(RequestFailure.INVALID, "invalid_comment_target"))
    return replyComment(text, replyCreateKey?.takeIf(String::isNotBlank) ?: commentCreationParameters(videoId, parentId), context, http)
}
fun deleteComment(comment: Comment, context: RequestContext, http: HttpClient = JvmHttpClient()) =
    performComment(commentActionQuery(comment.deleteKey, context.visitor), context, http)
fun likeComment(comment: Comment, context: RequestContext, http: HttpClient = JvmHttpClient()) =
    performComment(commentActionQuery(if (comment.liked) comment.unlikeKey else comment.likeKey, context.visitor), context, http)
fun dislikeComment(comment: Comment, context: RequestContext, http: HttpClient = JvmHttpClient()) =
    performComment(commentActionQuery(if (comment.disliked) comment.undislikeKey else comment.dislikeKey, context.visitor), context, http)
