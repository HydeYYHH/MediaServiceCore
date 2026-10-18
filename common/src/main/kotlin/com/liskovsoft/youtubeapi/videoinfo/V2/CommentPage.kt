package com.liskovsoft.youtubeapi.videoinfo.V2

private const val NEXT_URL = "https://www.youtube.com/youtubei/v1/next?prettyPrint=false"

data class CommentRun(
    val text: String,
    val url: String?,
    val videoId: String?,
    val seconds: Int?,
    val channelId: String?,
    val emoji: String?
)

data class Comment(
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
    val disliked: Boolean
)

data class CommentPage(val comments: List<Comment>, val nextKey: String?, val createKey: String?, val sortOptions: List<CommentSort> = emptyList())

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
    val panel = findPanel(root) ?: return null
    return firstToken(panel)
}

fun commentPage(body: String): CommentPage {
    val root = jsonObject(body) ?: return emptyPage
    val section = commentSection(root)
    val comments = ArrayList<Comment>()
    run {
        for (renderer in commentRenderers(root, section)) {
            val runs = contentRuns(renderer.obj("contentText"))
            comments.add(Comment(
                renderer.text("commentId")?.takeIf { it.isNotEmpty() },
                displayText(renderer.obj("authorText")),
                joinedText(runs),
                displayText(renderer.obj("publishedTimeText")),
                displayText(renderer.obj("voteCount")),
                renderer.bool("isLiked") == true,
                renderer.obj("replies")?.let { firstToken(it) },
                firstThumbnail(renderer.obj("authorThumbnail")),
                buttonAction(renderer, "likeButton", "defaultServiceEndpoint"),
                buttonAction(renderer, "likeButton", "toggledServiceEndpoint"),
                renderer.obj("actionMenu")?.let { deleteKey(it) },
                firstNamed(renderer, "createReplyParams"),
                runs,
                buttonAction(renderer, "dislikeButton", "defaultServiceEndpoint"),
                buttonAction(renderer, "dislikeButton", "toggledServiceEndpoint"),
                disliked(renderer)
            ))
        }
    }
    val createKey = headerCreateKey(section) ?: firstNamed(root, "createCommentParams")
    return CommentPage(comments, sectionContinuation(section) ?: commentNextKey(section), createKey, commentSortOptions(root))
}

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
        if (node.text("panelIdentifier") == "engagement-panel-comments-section") node
        else node.fields.values.asSequence().mapNotNull { findPanel(it) }.firstOrNull()
    }
    is Json.Arr -> node.items.asSequence().mapNotNull { findPanel(it) }.firstOrNull()
    else -> null
}

private fun firstToken(node: Json): String? = when (node) {
    is Json.Obj -> {
        val token = node.obj("continuationCommand")?.text("token")
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
    return CommentRun(text ?: "", url, videoId, seconds, channelId, emoji)
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

private fun escapeJson(value: String): String {
    val out = StringBuilder()
    for (c in value) {
        when (c) {
            '\\' -> out.append("\\\\")
            '"' -> out.append("\\\"")
            else -> out.append(c)
        }
    }
    return out.toString()
}
