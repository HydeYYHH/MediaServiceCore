package com.liskovsoft.youtubeapi.videoinfo.V2

data class PostItem(val authorId: String?, val postId: String, val author: String?, val authorAvatar: String?,
    val published: String?, val text: String?, val images: List<String>, val likes: String?, val replies: String?)
data class PostPage(val posts: List<PostItem>, val nextKey: String?, val commentsKey: String? = null)

internal fun posts(root: Json, channelId: String?): List<PostItem> = root.objects("backstagePostRenderer").mapNotNull { post ->
    val id = post.text("postId") ?: return@mapNotNull null
    val authorId = post.path("authorEndpoint", "browseEndpoint")?.text("browseId") ?: channelId
    val images = post.obj("backstageAttachment")?.objects("backstageImageRenderer").orEmpty()
        .mapNotNull { it.thumbnail("image") }.filter { it.isNotBlank() }
    PostItem(authorId, id, post.display("authorText"), post.thumbnail("authorThumbnail"),
        post.display("publishedTimeText"), post.display("contentText"), images,
        post.display("voteCount"), post.display("replyCount"))
}.distinctBy { it.authorId to it.postId }

/** The posts list carries its own continuation item; a missing token means the list ended. */
internal fun postContinuation(root: Json): String? = root.objects("continuationItemRenderer").firstNotNullOfOrNull { renderer ->
    renderer.path("continuationEndpoint", "continuationCommand")?.text("token")
        ?: renderer.path("button", "buttonRenderer", "continuationCommand")?.text("token")
}

fun loadChannelPosts(channelId: String, context: RequestContext = RequestContext(), params: String? = null,
    continuation: String? = null, http: HttpClient = JvmHttpClient()): ReadResult<PostPage> {
    val result = requestObject(channelQuery(channelId, context, params, continuation), http)
    if (!result.ok) return ReadResult(error = result.error)
    return ReadResult(PostPage(posts(result.value!!, channelId), postContinuation(result.value!!)))
}

/** The /post page is a web document: the post renderer lives in its inline ytInitialData. */
fun loadPost(postId: String, http: HttpClient = JvmHttpClient()): ReadResult<PostPage> {
    if (!postId.matches(Regex("[A-Za-z0-9_-]{6,200}")))
        return ReadResult(error = RequestError(RequestFailure.INVALID, "invalid_post_id"))
    val reply = requestBody(HttpRequest("GET", "https://www.youtube.com/post/$postId?hl=en"), http)
    if (!reply.ok) return ReadResult(error = reply.error)
    val marker = Regex("(?:var\\s+)?ytInitialData\\s*=\\s*|window\\[\"ytInitialData\"\\]\\s*=\\s*").find(reply.value!!)
        ?: return ReadResult(error = RequestError(RequestFailure.PARSE, "missing_post_data"))
    val json = initialData(reply.value!!.substring(marker.range.last + 1))
        ?: return ReadResult(error = RequestError(RequestFailure.PARSE, "missing_post_data"))
    return ReadResult(PostPage(posts(json, null), postContinuation(json), commentKey(json.encode())))
}

/** The page text continues past the assignment (`;` plus the script tail): scan to the matching close brace. */
private fun initialData(text: String): Json.Obj? {
    val start = text.indexOf('{')
    if (start < 0) return null
    var depth = 0
    var inString = false
    var escaped = false
    for (i in start until text.length) {
        val c = text[i]
        if (inString) {
            when {
                escaped -> escaped = false
                c == '\\' -> escaped = true
                c == '"' -> inString = false
            }
        } else when (c) {
            '"' -> inString = true
            '{' -> depth++
            '}' -> if (--depth == 0) return jsonObject(text.substring(start, i + 1))
        }
    }
    return null
}
