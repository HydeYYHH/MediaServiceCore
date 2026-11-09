package com.liskovsoft.youtubeapi.videoinfo.V2

private const val NEXT_URL = "https://www.youtube.com/youtubei/v1/next?prettyPrint=false"

data class CommentRun @JvmOverloads constructor(
    val text: String,
    val url: String?,
    val videoId: String?,
    val seconds: Int?,
    val channelId: String?,
    val emoji: String?,
    val bold: Boolean = false, val italic: Boolean = false, val strikethrough: Boolean = false
)

data class Comment @JvmOverloads constructor(
    val id: String?,
    val author: String?,
    val text: String?,
    val published: String?,
    val likeCount: String?,
    val liked: Boolean,
    val replyKey: String?,
    val authorPhoto: String?,
    val likeKey: String?,
    val unlikeKey: String?,
    val deleteKey: String?,
    val replyCreateKey: String?,
    val runs: List<CommentRun>,
    val dislikeKey: String?,
    val undislikeKey: String?,
    val disliked: Boolean,
    val parentId: String? = null, val replyCount: String? = null, val actions: List<PageAction> = emptyList(),
    val authorId: String? = null, val isCreator: Boolean = false, val isVerified: Boolean = false,
    val isPinned: Boolean = false, val isHearted: Boolean? = null,
    /** TV lists expose rating controls only after opening this comment's detail continuation. */
    val detailKey: String? = null,
    /** Null when this response omitted the viewer's rating rather than reporting neutral. */
    val viewerRating: String? = null
)

data class CommentPage(val comments: List<Comment>, val nextKey: String?, val createKey: String?, val sortOptions: List<CommentSort> = emptyList(),
    val total: String? = null, val turnedOff: Boolean = false)

private val emptyPage = CommentPage(emptyList(), null, null)

fun commentQuery(videoId: String?, continuation: String?, visitor: String?): PlayerQuery {
    if (videoId != null && videoId.isNotEmpty() && !videoIdAccepted(videoId)) {
        return PlayerQuery("", emptyMap(), "")
    }
    val parts = ArrayList<String>()
    if (videoId != null && videoId.isNotEmpty()) parts.add(""""videoId":"${escapeJson(videoId)}"""")
    if (continuation != null && continuation.isNotEmpty()) parts.add(""""continuation":"${escapeJson(continuation)}"""")
    if (parts.isEmpty()) return PlayerQuery("", emptyMap(), "")
    return webQuery(NEXT_URL, join(parts), visitor)
}

fun commentKey(body: String): String? {
    val root = jsonObject(body) ?: return null
    return commentKey(root)
}

internal fun commentKey(root: Json.Obj): String? {
    val panel = findPanel(root) ?: return null
    return pageKey(panel.obj("content") ?: panel)
}

fun commentPage(body: String): CommentPage {
    val root = jsonObject(body) ?: return emptyPage
    return commentPage(root)
}

internal fun commentPage(root: Json.Obj): CommentPage {
    val section = commentSection(root)
    val comments = ArrayList<Comment>()
    run {
        for (renderer in commentRenderers(root, section)) {
            val runs = contentRuns(renderer.obj("contentText"))
            val detailKey = renderer.obj("detailViewEndpoint")?.let(::pageKey)
            val replyCount = renderer.display("replyCount") ?: renderer.display("repliesCount")
                ?: renderer.path("replies", "commentRepliesRenderer", "viewReplies", "buttonRenderer")?.display("text")
            val replyKey = renderer.obj("replies")?.let(::firstToken)
                ?: detailKey?.takeIf { replyCount?.filter(Char::isDigit)?.toIntOrNull()?.let { it > 0 } == true }
            val viewerRating = commentViewerRating(renderer)
            comments.add(Comment(
                renderer.text("commentId")?.takeIf { it.isNotEmpty() },
                displayText(renderer.obj("authorText")),
                joinedText(runs),
                displayText(renderer.obj("publishedTimeText")),
                displayText(renderer.obj("voteCount")),
                viewerRating == "LIKE" || renderer.bool("isLiked") == true,
                replyKey,
                firstThumbnail(renderer.obj("authorThumbnail")),
                buttonAction(renderer, "likeButton", "defaultServiceEndpoint"),
                buttonAction(renderer, "likeButton", "toggledServiceEndpoint"),
                renderer.obj("actionMenu")?.let { deleteKey(it) },
                firstNamed(renderer, "createReplyParams"),
                runs,
                buttonAction(renderer, "dislikeButton", "defaultServiceEndpoint"),
                buttonAction(renderer, "dislikeButton", "toggledServiceEndpoint"),
                viewerRating == "DISLIKE" || disliked(renderer), renderer.text("parentCommentId"), replyCount, pageActions(renderer),
                renderer.path("authorEndpoint", "browseEndpoint")?.text("browseId"),
                renderer.bool("isCreator") == true || renderer.bool("authorIsChannelOwner") == true,
                renderer.bool("isVerified") == true,
                renderer.bool("isPinned") == true || renderer.obj("pinnedCommentBadge") != null,
                renderer.bool("isHearted") ?: if (renderer.path("actionButtons", "commentActionButtonsRenderer")?.obj("creatorHeart") != null) true else null,
                detailKey, viewerRating
            ))
        }
    }
    val createKey = commentCreateKey(root, section)
    return CommentPage(comments, sectionContinuation(section) ?: commentNextKey(section), createKey, commentSortOptions(root))
}

internal fun commentCreateKey(root: Json.Obj, section: Json.Obj = commentSection(root)): String? =
    headerCreateKey(section) ?: firstNamed(root, "createCommentParams")

fun loadComments(
    videoId: String,
    visitor: String?,
    post: (PlayerQuery) -> Pair<Int, String>
): CommentPage {
    val opened = postPage(commentQuery(videoId, null, visitor), post) ?: return emptyPage
    val key = commentKey(opened) ?: return emptyPage
    val page = postPage(commentQuery(null, key, visitor), post) ?: return emptyPage
    val parsed = commentPage(page)
    if (parsed.createKey != null) return parsed
    val fromWatch = jsonObject(opened)?.let { firstNamed(it, "createCommentParams") }
    return if (fromWatch == null) parsed else parsed.copy(createKey = fromWatch)
}

private fun postPage(query: PlayerQuery, post: (PlayerQuery) -> Pair<Int, String>): String? {
    if (query.url.isEmpty()) return null
    val http = try {
        post(query)
    } catch (failure: Exception) {
        return null
    }
    if (http.first != 200) return null
    return http.second
}

private fun findPanel(node: Json): Json.Obj? = when (node) {
    is Json.Obj -> {
        if (node.text("panelIdentifier") in setOf("engagement-panel-comments-section", "comment-item-section") &&
            pageKey(node.obj("content") ?: node) != null) node
        else node.fields.values.asSequence().mapNotNull { findPanel(it) }.firstOrNull()
    }
    is Json.Arr -> node.items.asSequence().mapNotNull { findPanel(it) }.firstOrNull()
    else -> null
}

private fun firstToken(node: Json): String? = when (node) {
    is Json.Obj -> {
        val token = node.obj("continuationCommand")?.text("token")
            ?: node.obj("nextContinuationData")?.text("continuation")
            ?: node.obj("reloadContinuationData")?.text("continuation")
        if (token != null && token.isNotEmpty()) token
        else node.fields.values.asSequence().mapNotNull { firstToken(it) }.firstOrNull()
    }
    is Json.Arr -> node.items.asSequence().mapNotNull { firstToken(it) }.firstOrNull()
    else -> null
}

private fun sectionContinuation(section: Json.Obj): String? {
    val continuations = section.fields["continuations"] as? Json.Arr ?: return null
    for (item in continuations.items) {
        val next = (item as? Json.Obj)?.obj("nextContinuationData")?.text("continuation")
        if (next != null && next.isNotEmpty()) return next
    }
    return firstToken(Json.Arr(continuations.items))
}

private fun contentRuns(node: Json.Obj?): List<CommentRun> {
    if (node == null) return emptyList()
    val simple = node.text("simpleText")
    if (simple != null && simple.isNotEmpty()) {
        return listOf(CommentRun(simple, null, null, null, null, null))
    }
    val items = node.fields["runs"] as? Json.Arr ?: return emptyList()
    val runs = ArrayList<CommentRun>()
    for (item in items.items) {
        val run = item as? Json.Obj ?: continue
        val built = commentRun(run)
        if (built != null) runs.add(built)
    }
    return runs
}

private fun commentRun(run: Json.Obj): CommentRun? {
    val emojiNode = run.obj("emoji")
    val text = run.text("text")?.takeIf { it.isNotEmpty() } ?: emojiLabel(emojiNode)
    val endpoint = run.obj("navigationEndpoint")
    val url = endpointUrl(endpoint)
    val watch = endpoint?.obj("watchEndpoint")
    val videoId = watch?.text("videoId")?.takeIf { it.isNotEmpty() }
    val seconds = if (watch == null) null else wholeNumber(watch, "startTimeSeconds")
    val channelId = endpoint?.obj("browseEndpoint")?.text("browseId")?.takeIf { it.isNotEmpty() }
    val emoji = emojiNode?.obj("image")?.let { firstThumbnail(it) }
    if (text == null && url == null && videoId == null && channelId == null && emoji == null) return null
    return CommentRun(text ?: "", url, videoId, seconds, channelId, emoji,
        run.bool("bold") == true, run.bool("italic") == true, run.bool("strikethrough") == true)
}

private fun emojiLabel(emoji: Json.Obj?): String? {
    if (emoji == null) return null
    if (emoji.bool("isCustomEmoji") == true) {
        val shortcuts = emoji.fields["shortcuts"] as? Json.Arr ?: return null
        for (item in shortcuts.items) {
            val name = (item as? Json.Str)?.value
            if (name != null && name.isNotEmpty()) return name
        }
        return null
    }
    return emoji.text("emojiId")?.takeIf { it.isNotEmpty() }
}

private fun endpointUrl(endpoint: Json.Obj?): String? {
    if (endpoint == null) return null
    val raw = endpoint.obj("urlEndpoint")?.text("url")?.takeIf { it.isNotEmpty() }
        ?: endpoint.obj("commandMetadata")?.obj("webCommandMetadata")?.text("url")?.takeIf { it.isNotEmpty() }
        ?: return null
    return if (raw.startsWith("/")) "https://www.youtube.com$raw" else raw
}

private fun wholeNumber(node: Json.Obj, name: String): Int? {
    val text = (node.fields[name] as? Json.Num)?.text ?: return null
    val digits = if (text.startsWith("-")) text.substring(1) else text
    if (digits.isEmpty() || digits.any { it !in '0'..'9' }) return null
    return text.toIntOrNull()
}

private fun joinedText(runs: List<CommentRun>): String? {
    if (runs.isEmpty()) return null
    val out = StringBuilder()
    for (run in runs) out.append(run.text)
    return if (out.isEmpty()) null else out.toString()
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

private fun headerCreateKey(section: Json.Obj): String? {
    return section.obj("header")
        ?.obj("commentsHeaderRenderer")
        ?.obj("createRenderer")
        ?.obj("commentSimpleboxRenderer")
        ?.obj("submitButton")
        ?.obj("buttonRenderer")
        ?.obj("serviceEndpoint")
        ?.obj("createCommentEndpoint")
        ?.text("createCommentParams")
        ?.takeIf { it.isNotEmpty() }
}

private fun buttonAction(renderer: Json.Obj, button: String, endpoint: String): String? {
    return renderer.obj("actionButtons")
        ?.obj("commentActionButtonsRenderer")
        ?.obj(button)
        ?.obj("toggleButtonRenderer")
        ?.obj(endpoint)
        ?.obj("performCommentActionEndpoint")
        ?.text("action")
        ?.takeIf { it.isNotEmpty() }
}

private fun disliked(renderer: Json.Obj): Boolean {
    return renderer.obj("actionButtons")
        ?.obj("commentActionButtonsRenderer")
        ?.obj("dislikeButton")
        ?.obj("toggleButtonRenderer")
        ?.bool("isToggled") == true
}

private fun commentViewerRating(renderer: Json.Obj): String? {
    renderer.text("voteStatus")?.takeIf { it in setOf("LIKE", "DISLIKE", "INDIFFERENT") }?.let { return it }
    val buttons = renderer.path("actionButtons", "commentActionButtonsRenderer")
    val liked = renderer.bool("isLiked") ?: buttons?.path("likeButton", "toggleButtonRenderer")?.bool("isToggled")
    val disliked = renderer.bool("isDisliked") ?: buttons?.path("dislikeButton", "toggleButtonRenderer")?.bool("isToggled")
    return when {
        liked == true && disliked == true -> null
        liked == true -> "LIKE"
        disliked == true -> "DISLIKE"
        liked == false && disliked == false -> "INDIFFERENT"
        else -> null
    }
}

private fun deleteKey(node: Json): String? = when (node) {
    is Json.Obj -> {
        if (!containsIcon(node, "DELETE") || firstAction(node) == null) null
        else node.fields.values.asSequence().mapNotNull { deleteKey(it) }.firstOrNull() ?: firstAction(node)
    }
    is Json.Arr -> node.items.asSequence().mapNotNull { deleteKey(it) }.firstOrNull()
    else -> null
}

private fun containsIcon(node: Json, icon: String): Boolean = when (node) {
    is Json.Obj -> node.text("iconType") == icon || node.fields.values.any { containsIcon(it, icon) }
    is Json.Arr -> node.items.any { containsIcon(it, icon) }
    else -> false
}

private fun firstAction(node: Json): String? = when (node) {
    is Json.Obj -> {
        val action = node.obj("performCommentActionEndpoint")?.text("action")
        if (action != null && action.isNotEmpty()) action
        else node.fields.values.asSequence().mapNotNull { firstAction(it) }.firstOrNull()
    }
    is Json.Arr -> node.items.asSequence().mapNotNull { firstAction(it) }.firstOrNull()
    else -> null
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

private fun join(parts: List<String>): String {
    val out = StringBuilder()
    for (i in parts.indices) {
        if (i > 0) out.append(',')
        out.append(parts[i])
    }
    return out.toString()
}

private fun escapeJson(value: String): String = jsonEscape(value)
