package com.liskovsoft.youtubeapi.videoinfo.V2

data class PostItem(val authorId: String?, val postId: String, val author: String?, val authorAvatar: String?,
    val published: String?, val text: String?, val images: List<String>, val likes: String?, val replies: String?,
    val voteStatus: String? = null, val like: PostCommand? = null, val unlike: PostCommand? = null,
    val dislike: PostCommand? = null, val undislike: PostCommand? = null, val poll: PostPoll? = null,
    val authorizationRequired: Boolean = false)
data class PostPage(val posts: List<PostItem>, val nextKey: String?, val commentsKey: String? = null)

internal fun posts(root: Json, channelId: String?): List<PostItem> = root.objects("backstagePostRenderer").mapNotNull { post ->
    val id = post.text("postId") ?: return@mapNotNull null
    val authorId = post.path("authorEndpoint", "browseEndpoint")?.text("browseId") ?: channelId
    val images = post.obj("backstageAttachment")?.objects("backstageImageRenderer").orEmpty()
        .mapNotNull { it.thumbnail("image") }.filter { it.isNotBlank() }
    val avatar = post.thumbnail("authorThumbnail")?.let { if (it.startsWith("//")) "https:$it" else it }
    val buttons = post.obj("actionButtons")
    fun button(name: String, toggled: Boolean) = buttons?.objects(name)?.firstOrNull()?.let {
        val toggle = it.obj("toggleButtonRenderer") ?: it
        postCommand(toggle.obj(if (toggled) "toggledServiceEndpoint" else "defaultServiceEndpoint"))
    }
    PostItem(authorId, id, post.display("authorText"), avatar,
        post.display("publishedTimeText"), post.display("contentText"), images,
        post.display("voteCount"), post.display("replyCount"), post.text("voteStatus"),
        button("likeButton", false), button("likeButton", true), button("dislikeButton", false), button("dislikeButton", true),
        post.obj("backstageAttachment")?.objects("pollRenderer")?.firstOrNull()?.let(::postPoll),
        buttons?.objects("signInEndpoint")?.isNotEmpty() == true ||
            post.obj("backstageAttachment")?.objects("signInEndpoint")?.isNotEmpty() == true)
}.distinctBy { it.authorId to it.postId }

/** The posts list carries its own continuation item; a missing token means the list ended. */
internal fun postContinuation(root: Json): String? = root.objects("continuationItemRenderer").firstNotNullOfOrNull { renderer ->
    (renderer.path("continuationEndpoint", "continuationCommand")?.text("token")
        ?: renderer.path("button", "buttonRenderer", "command", "continuationCommand")?.text("token")
        ?: renderer.path("button", "buttonRenderer", "continuationCommand")?.text("token"))?.takeIf(String::isNotBlank)
}

fun loadChannelPosts(channelId: String, context: RequestContext = RequestContext(), params: String? = null,
    continuation: String? = null, http: HttpClient = JvmHttpClient()): ReadResult<PostPage> {
    val result = requestObject(channelQuery(channelId, context, params, continuation).withCompactResponse(), http)
    if (!result.ok) return ReadResult(error = result.error)
    return ReadResult(PostPage(posts(result.value!!, channelId), postContinuation(result.value!!)))
}

/** The /post page is a web document: the post renderer lives in its inline ytInitialData. */
fun loadPost(postId: String, http: HttpClient = JvmHttpClient()): ReadResult<PostPage> = loadPost(postId, RequestContext(), http)

fun loadPost(postId: String, context: RequestContext, http: HttpClient = JvmHttpClient()): ReadResult<PostPage> {
    if (!postId.matches(Regex("[A-Za-z0-9_-]{6,200}")))
        return ReadResult(error = RequestError(RequestFailure.INVALID, "invalid_post_id"))
    val headers = context.webSession?.headers().orEmpty() + context.onBehalfOfUser?.let { mapOf("X-Goog-PageId" to it) }.orEmpty()
    val reply = requestBody(HttpRequest("GET", "https://www.youtube.com/post/$postId?hl=en", headers), http)
    if (!reply.ok) return ReadResult(error = reply.error)
    val json = initialDataDocument(reply.value!!)
        ?: return ReadResult(error = RequestError(RequestFailure.PARSE, "missing_post_data"))
    val comments = json.objects("itemSectionRenderer").firstOrNull {
        it.text("sectionIdentifier") == "comment-item-section" || it.text("targetId") == "comments-section"
    }?.let { pageKey(it) } ?: commentKey(json)
    return ReadResult(PostPage(posts(json, null), null, comments))
}

/** Post comments (including sorting and replies) carry WEB /browse continuations, not /next. */
fun loadPostComments(continuation: String, context: RequestContext = RequestContext(),
    http: HttpClient = JvmHttpClient()): ReadResult<CommentPage> {
    if (continuation.isBlank()) return ReadResult(error = RequestError(RequestFailure.INVALID, "missing_comment_key"))
    // WEB continuations must keep the authorized web identity so action tokens are returned.
    val web = if (context.webSession?.signedIn == true) context.copy(accessToken = null) else context.anonymous()
    val response = requestObject(browseQuery(null, web, continuation = continuation).withCompactResponse(), http)
    if (!response.ok) return ReadResult(error = response.error)
    val root = response.value!!
    val total = root.objects("commentsHeaderRenderer").firstNotNullOfOrNull { it.display("countText") }
    return ReadResult(commentPage(root).copy(total = total))
}
