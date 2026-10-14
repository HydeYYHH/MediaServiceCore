package com.liskovsoft.youtubeapi.videoinfo.V2

private const val NEXT_URL = "https://www.youtube.com/youtubei/v1/next?prettyPrint=false"

data class Comment(
    val id: String?,
    val author: String?,
    val text: String?,
    val published: String?,
    val likeCount: String?,
    val liked: Boolean,
    val replyKey: String?,
    val authorPhoto: String?
)

data class CommentPage(val comments: List<Comment>, val nextKey: String?)

private val emptyPage = CommentPage(emptyList(), null)

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
    val section = jsonObject(body)?.obj("continuationContents")?.obj("itemSectionContinuation") ?: return emptyPage
    val comments = ArrayList<Comment>()
    val contents = section.fields["contents"] as? Json.Arr
    if (contents != null) {
        for (item in contents.items) {
            val renderer = (item as? Json.Obj)
                ?.obj("commentThreadRenderer")
                ?.obj("comment")
                ?.obj("commentRenderer") ?: continue
            comments.add(Comment(
                renderer.text("commentId")?.takeIf { it.isNotEmpty() },
                displayText(renderer.obj("authorText")),
                displayText(renderer.obj("contentText")),
                displayText(renderer.obj("publishedTimeText")),
                displayText(renderer.obj("voteCount")),
                renderer.bool("isLiked") == true,
                renderer.obj("replies")?.let { firstToken(it) },
                firstThumbnail(renderer.obj("authorThumbnail"))
            ))
        }
    }
    return CommentPage(comments, sectionContinuation(section))
}

fun loadComments(
    videoId: String,
    visitor: String?,
    post: (PlayerQuery) -> Pair<Int, String>
): CommentPage {
    val opened = postPage(commentQuery(videoId, null, visitor), post) ?: return emptyPage
    val key = commentKey(opened) ?: return emptyPage
    val page = postPage(commentQuery(null, key, visitor), post) ?: return emptyPage
    return commentPage(page)
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
