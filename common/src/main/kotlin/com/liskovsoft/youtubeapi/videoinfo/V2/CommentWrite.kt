package com.liskovsoft.youtubeapi.videoinfo.V2

private const val ACTION_URL =
    "https://www.youtube.com/youtubei/v1/comment/perform_comment_action?prettyPrint=false"
private const val CREATE_URL =
    "https://www.youtube.com/youtubei/v1/comment/create_comment?prettyPrint=false"
private const val REPLY_URL =
    "https://www.youtube.com/youtubei/v1/comment/create_comment_reply?prettyPrint=false"

fun commentActionQuery(action: String?, visitor: String?): PlayerQuery {
    if (action.isNullOrEmpty()) return PlayerQuery("", emptyMap(), "")
    return webQuery(ACTION_URL, """"actions":["${escapeJson(action)}"]""", visitor)
}

fun createCommentQuery(text: String?, createKey: String?, visitor: String?): PlayerQuery {
    return textQuery(CREATE_URL, "createCommentParams", text, createKey, visitor)
}

fun replyCommentQuery(text: String?, replyCreateKey: String?, visitor: String?): PlayerQuery {
    return textQuery(REPLY_URL, "createReplyParams", text, replyCreateKey, visitor)
}

fun sendComment(query: PlayerQuery, post: (PlayerQuery) -> Pair<Int, String>): Boolean {
    if (query.url.isEmpty()) return false
    val http = try {
        post(query)
    } catch (failure: Exception) {
        return false
    }
    return http.first == 200
}

fun createComment(
    text: String?,
    createKey: String?,
    visitor: String?,
    post: (PlayerQuery) -> Pair<Int, String>
): Boolean = sendComment(createCommentQuery(text, createKey, visitor), post)

fun replyComment(
    text: String?,
    replyCreateKey: String?,
    visitor: String?,
    post: (PlayerQuery) -> Pair<Int, String>
): Boolean = sendComment(replyCommentQuery(text, replyCreateKey, visitor), post)

fun deleteComment(
    comment: Comment,
    visitor: String?,
    post: (PlayerQuery) -> Pair<Int, String>
): Boolean = sendComment(commentActionQuery(comment.deleteKey, visitor), post)

fun likeComment(
    comment: Comment,
    visitor: String?,
    post: (PlayerQuery) -> Pair<Int, String>
): Boolean {
    val key = if (comment.liked) comment.unlikeKey else comment.likeKey
    return sendComment(commentActionQuery(key, visitor), post)
}

private fun textQuery(
    url: String,
    paramsField: String,
    text: String?,
    params: String?,
    visitor: String?
): PlayerQuery {
    if (text.isNullOrBlank() || params.isNullOrEmpty()) return PlayerQuery("", emptyMap(), "")
    val payload = """"commentText":"${escapeJson(text)}","$paramsField":"${escapeJson(params)}""""
    return webQuery(url, payload, visitor)
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
