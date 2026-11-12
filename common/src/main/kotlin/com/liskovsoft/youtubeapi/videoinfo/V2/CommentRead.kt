package com.liskovsoft.youtubeapi.videoinfo.V2

data class CommentSort(val title: String?, val key: String, val selected: Boolean)
internal fun commentSection(root: Json.Obj): Json.Obj {
    val old = root.obj("continuationContents")
    old?.obj("itemSectionContinuation")?.let { return it }
    old?.obj("commentRepliesContinuation")?.let { return it }
    val actions = root.objects("appendContinuationItemsAction") + root.objects("reloadContinuationItemsCommand")
    if (actions.isEmpty()) return root
    return Json.Obj(mapOf("contents" to Json.Arr(actions.flatMap { it.arr("continuationItems") })))
}
internal fun commentRenderers(root: Json.Obj, section: Json.Obj): List<Json.Obj> {
    val threads = section.objects("commentThreadRenderer")
    val legacy = section.objects("commentRenderer").map { renderer ->
        val thread = threads.firstOrNull { it.path("comment", "commentRenderer") === renderer }
        val replies = thread?.obj("replies")
        if (replies != null && renderer.obj("replies") == null) Json.Obj(renderer.fields + ("replies" to replies)) else renderer
    }
    val modern = commentEntityRenderers(root, section)
    val order = commentOrder(section)
    return (legacy + modern).distinctBy { it.text("commentId") ?: it.encode() }.sortedBy {
        order.indexOf(it.text("entityKey") ?: it.text("commentId")).takeIf { position -> position >= 0 } ?: Int.MAX_VALUE
    }
}
internal fun commentSortOptions(root: Json.Obj): List<CommentSort> =
    root.objects("sortFilterSubMenuRenderer").flatMap { it.arr("subMenuItems") }.mapNotNull { it as? Json.Obj }.mapNotNull {
        val endpoint = it.obj("serviceEndpoint") ?: it.obj("navigationEndpoint")
        val key = endpoint?.objects("continuationCommand")?.firstOrNull()?.text("token")
            ?: it.path("continuation", "reloadContinuationData")?.text("continuation") ?: return@mapNotNull null
        CommentSort(it.text("title"), key, it.bool("selected") == true)
    }
internal fun commentNextKey(section: Json.Obj): String? =
    section.arr("contents").mapNotNull { value ->
        val item = (value as? Json.Obj)?.obj("continuationItemRenderer") ?: return@mapNotNull null
        pageKey(item) ?: item.path("button", "buttonRenderer", "command", "continuationCommand")
            ?.text("token")?.takeIf(String::isNotBlank)
    }.firstOrNull()
/** [opened] may reuse the initial /next response read for the same video and request context. */
@JvmOverloads
fun loadComments(videoId: String, context: RequestContext, continuation: String? = null, http: HttpClient = JvmHttpClient(),
    opened: WatchPage? = null): ReadResult<CommentPage> {
    if (!videoIdAccepted(videoId)) return ReadResult(error = RequestError(RequestFailure.INVALID, "invalid_video_id"))
    if (opened != null && opened.videoId != videoId)
        return ReadResult(error = RequestError(RequestFailure.INVALID, "comment_video_mismatch"))
    var key = continuation
    var opening: WatchPage? = null
    if (key == null) {
        opening = opened ?: run {
            val first = loadWatch(videoId, context, http = http)
            if (!first.ok) return ReadResult(error = first.error)
            first.value!!
        }
        key = opening.commentsKey
        if (key == null) return ReadResult(CommentPage(emptyList(), null, null, opening.commentSortOptions,
            total = opening.commentCount, turnedOff = opening.commentsTurnedOff))
    }
    val response = requestObject(youtubeQuery(YOUTUBE_API + "next", payload("continuation" to key), context).withCompactResponse(), http)
    if (!response.ok) return ReadResult(error = response.error)
    val parsed = readCommentPage(response.value!!)
    if (!parsed.ok) return parsed
    var page = parsed.value!!
    if (opening != null) page = page.copy(
        createKey = page.createKey ?: opening.commentCreateKey,
        sortOptions = page.sortOptions.ifEmpty { opening.commentSortOptions },
        total = opening.commentCount)
    return ReadResult(page)
}
fun loadCommentReplies(replyKey: String, context: RequestContext = RequestContext(), http: HttpClient = JvmHttpClient()): ReadResult<CommentPage> {
    if (replyKey.isBlank()) return ReadResult(error = RequestError(RequestFailure.INVALID, "missing_reply_key"))
    val response = requestObject(youtubeQuery(YOUTUBE_API + "next", payload("continuation" to replyKey), context).withCompactResponse(), http)
    return if (response.ok) readCommentPage(response.value!!, allowEmptyReplies = true) else ReadResult(error = response.error)
}
