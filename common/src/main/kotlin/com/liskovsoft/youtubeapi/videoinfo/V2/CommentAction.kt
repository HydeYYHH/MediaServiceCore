package com.liskovsoft.youtubeapi.videoinfo.V2

data class CommentActionReply(val comment: Comment?, val action: ActionReply)
internal fun queryInContext(query: PlayerQuery, context: RequestContext): PlayerQuery {
    val root = jsonObject(query.body) ?: return PlayerQuery("", emptyMap(), "")
    val fields = root.fields.filterKeys { it != "context" }.entries.joinToString(",") { jsonQuote(it.key) + ":" + it.value.encode() }
    return youtubeQuery(query.url, fields, context)
}
fun performComment(query: PlayerQuery, context: RequestContext, http: HttpClient = JvmHttpClient()): ReadResult<CommentActionReply> =
    performComment(query, context, http, ActionEntityScope.Generic)

private fun performComment(query: PlayerQuery, context: RequestContext, http: HttpClient,
    entityScope: ActionEntityScope): ReadResult<CommentActionReply> {
    if (!context.signedIn) return ReadResult(error = RequestError(RequestFailure.AUTH, "sign_in_required"))
    val reply = requestAction(queryInContext(query, context), http, entityScope = entityScope, context = context)
    if (reply.value == null) return ReadResult(error = reply.error)
    val action = requireNotNull(reply.value)
    val comments = jsonObject(action.body)?.let(::ownedCommentMutation)?.let(::commentPage)?.comments.orEmpty()
    val comment = if (entityScope is ActionEntityScope.Comment) comments.firstOrNull { it.id == entityScope.id }
        else comments.firstOrNull()
    return ReadResult(CommentActionReply(comment, action), error = reply.error)
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

/** An explicit viewer command; detail reads may fill capabilities but cannot change this intent. */
enum class CommentVote { LIKE, DISLIKE, UNLIKE, UNDISLIKE }

/** Compatibility toggle: missing capabilities are resolved before interpreting the server's viewer state. */
fun likeComment(comment: Comment, context: RequestContext, http: HttpClient = JvmHttpClient()) =
    rateCommentWithIntent(comment, { if (it.liked) CommentVote.UNLIKE else CommentVote.LIKE }, context, http)

/** Compatibility toggle; app callers with displayed state should use the explicit [rateComment]. */
fun dislikeComment(comment: Comment, context: RequestContext, http: HttpClient = JvmHttpClient()) =
    rateCommentWithIntent(comment, { if (it.disliked) CommentVote.UNDISLIKE else CommentVote.DISLIKE }, context, http)

/** A fixed caller intent. A detail read fills action tokens without changing [vote]. */
fun rateComment(comment: Comment, vote: CommentVote, context: RequestContext,
    http: HttpClient = JvmHttpClient()): ReadResult<CommentActionReply> =
    rateCommentWithIntent(comment, { vote }, context, http)

private fun rateCommentWithIntent(comment: Comment, voteOf: (Comment) -> CommentVote, context: RequestContext,
    http: HttpClient = JvmHttpClient()): ReadResult<CommentActionReply> {
    if (!context.signedIn) return ReadResult(error = RequestError(RequestFailure.AUTH, "sign_in_required"))
    fun action(source: Comment) = when (voteOf(source)) {
        CommentVote.LIKE -> source.likeKey
        CommentVote.DISLIKE -> source.dislikeKey
        CommentVote.UNLIKE -> source.unlikeKey
        CommentVote.UNDISLIKE -> source.undislikeKey
    }
    var source = comment
    if (action(source).isNullOrBlank() && !source.detailKey.isNullOrBlank()) {
        val page = loadCommentReplies(source.detailKey!!, context, http)
        if (!page.ok) return ReadResult(error = page.error)
        // TV detail pages include the parent and its replies. Only the requested ID may be rated.
        val resolved = page.value!!.comments.firstOrNull { it.id == comment.id }
            ?: return ReadResult(error = RequestError(RequestFailure.PARSE, "missing_comment_action_target"))
        source = resolved.copy(
            likeKey = resolved.likeKey ?: comment.likeKey, unlikeKey = resolved.unlikeKey ?: comment.unlikeKey,
            dislikeKey = resolved.dislikeKey ?: comment.dislikeKey, undislikeKey = resolved.undislikeKey ?: comment.undislikeKey,
            deleteKey = resolved.deleteKey ?: comment.deleteKey, replyCreateKey = resolved.replyCreateKey ?: comment.replyCreateKey,
            replyKey = resolved.replyKey ?: comment.replyKey, detailKey = resolved.detailKey ?: comment.detailKey,
        )
    }
    val key = action(source)?.takeIf(String::isNotBlank)
        ?: return ReadResult(error = RequestError(RequestFailure.UNSUPPORTED, "missing_comment_rating_action"))
    val scope = source.id?.takeIf(String::isNotBlank)?.let { ActionEntityScope.Comment(it) } ?: ActionEntityScope.None
    val result = performComment(commentActionQuery(key, context.visitor), context, http, scope)
    val reply = result.value ?: return result
    if (!reply.action.confirmed) return result
    val vote = voteOf(source)
    val liked = vote == CommentVote.LIKE
    val disliked = vote == CommentVote.DISLIKE
    val delta = (if (liked) 1 else 0) - (if (source.liked) 1 else 0)
    val number = source.likeCount?.replace(",", "")?.toLongOrNull()
        ?: 0L.takeIf { source.likeCount.isNullOrBlank() }
    val count = if (delta == 0 || number == null) source.likeCount else
        java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format((number + delta).coerceAtLeast(0))
    // Preserve the resolved detail-page action tokens for the next toggle.
    val entity = reply.comment?.takeIf { it.id == source.id }
    val updated = source.copy(liked = liked, disliked = disliked,
        viewerRating = when { liked -> "LIKE"; disliked -> "DISLIKE"; else -> "INDIFFERENT" }, likeCount = entity?.likeCount ?: count,
        likeKey = entity?.likeKey ?: source.likeKey, unlikeKey = entity?.unlikeKey ?: source.unlikeKey,
        dislikeKey = entity?.dislikeKey ?: source.dislikeKey, undislikeKey = entity?.undislikeKey ?: source.undislikeKey)
    return ReadResult(reply.copy(comment = updated), error = result.error)
}
