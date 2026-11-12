package com.liskovsoft.youtubeapi.videoinfo.V2

/** One server-provided quiz choice. Reading a choice never creates an answer command. */
data class PostQuizChoice(val text: String, val isCorrect: Boolean)

/** A directly attached, read-only quiz. [totalVotes] is display text, not a parsed count. */
data class PostQuiz(val choices: List<PostQuizChoice>, val totalVotes: String?)

data class PostItem(val authorId: String?, val postId: String, val author: String?, val authorAvatar: String?,
    val published: String?, val text: String?, val images: List<String>, val likes: String?, val replies: String?,
    val voteStatus: String? = null, val like: PostCommand? = null, val unlike: PostCommand? = null,
    val dislike: PostCommand? = null, val undislike: PostCommand? = null, val poll: PostPoll? = null,
    val authorizationRequired: Boolean = false,
    /** A directly attached video. It carries display data only, without post or video commands. */
    val video: MediaItem? = null,
    /** A directly attached quiz, without answer or mutation capabilities. */
    val quiz: PostQuiz? = null) {
    /** Retains the pre-video Java constructor descriptor. */
    constructor(authorId: String?, postId: String, author: String?, authorAvatar: String?, published: String?, text: String?,
        images: List<String>, likes: String?, replies: String?, voteStatus: String?, like: PostCommand?, unlike: PostCommand?,
        dislike: PostCommand?, undislike: PostCommand?, poll: PostPoll?, authorizationRequired: Boolean) :
        this(authorId, postId, author, authorAvatar, published, text, images, likes, replies, voteStatus, like, unlike,
            dislike, undislike, poll, authorizationRequired, null, null)

    /** Retains the pre-quiz Java constructor descriptor, including its video argument. */
    constructor(authorId: String?, postId: String, author: String?, authorAvatar: String?, published: String?, text: String?,
        images: List<String>, likes: String?, replies: String?, voteStatus: String?, like: PostCommand?, unlike: PostCommand?,
        dislike: PostCommand?, undislike: PostCommand?, poll: PostPoll?, authorizationRequired: Boolean, video: MediaItem?) :
        this(authorId, postId, author, authorAvatar, published, text, images, likes, replies, voteStatus, like, unlike,
            dislike, undislike, poll, authorizationRequired, video, null)

    /** Public post destination; invalid caller-created identities never become URLs. No IO. */
    val permalink: String? get() = if (postIdAccepted(postId)) "https://www.youtube.com/post/$postId" else null
}
data class PostPage(val posts: List<PostItem>, val nextKey: String?, val commentsKey: String? = null)

private val postIdentity = Regex("[A-Za-z0-9_-]{6,200}")
internal fun postIdAccepted(value: String) = postIdentity.matches(value)

internal fun posts(root: Json, channelId: String?): List<PostItem> {
    val result = ArrayList<PostItem>()
    fun visit(node: Json) {
        if (node is Json.Obj && node.isAdvertisement()) return
        when (node) {
            is Json.Obj -> for ((kind, value) in node.fields) {
                if (kind in setOf("backstagePostRenderer", "postRenderer"))
                    (value as? Json.Obj)?.let { postItem(it, channelId) }?.let(result::add)
                else visit(value)
            }
            is Json.Arr -> node.items.forEach(::visit)
            else -> Unit
        }
    }
    visit(root)
    return result.distinctBy { it.authorId to it.postId }
}

/** Shared legacy/modern renderer fields; display eligibility is checked by each owning page. */
internal fun postItem(post: Json.Obj, channelId: String?): PostItem? {
    val id = post.text("postId") ?: return null
    val authorId = post.path("authorEndpoint", "browseEndpoint")?.text("browseId") ?: channelId
    val attachment = post.obj("backstageAttachment")
    val quizDeclared = attachment?.fields?.containsKey("quizRenderer") == true
    val images = (if (quizDeclared) emptyList() else attachment?.objects("backstageImageRenderer").orEmpty())
        .mapNotNull { it.thumbnail("image") }.filter { it.isNotBlank() }
    val avatar = post.thumbnail("authorThumbnail")?.let { if (it.startsWith("//")) "https:$it" else it }
    val buttons = post.obj("actionButtons")
    fun button(name: String, toggled: Boolean) = buttons?.objects(name)?.firstOrNull()?.let {
        val toggle = it.obj("toggleButtonRenderer") ?: it
        postCommand(toggle.obj(if (toggled) "toggledServiceEndpoint" else "defaultServiceEndpoint"))
    }
    return PostItem(authorId, id, post.display("authorText"), avatar,
        post.display("publishedTimeText"), post.display("contentText"), images,
        post.display("voteCount"), post.display("replyCount"), post.text("voteStatus"),
        button("likeButton", false), button("likeButton", true), button("dislikeButton", false), button("dislikeButton", true),
        if (quizDeclared) null else attachment?.objects("pollRenderer")?.firstOrNull()?.let(::postPoll),
        buttons?.objects("signInEndpoint")?.isNotEmpty() == true ||
            !quizDeclared && attachment?.objects("signInEndpoint")?.isNotEmpty() == true,
        if (quizDeclared) null else attachment?.obj("videoRenderer")?.let(::postVideoAttachment),
        if (quizDeclared && attachment!!.fields.keys.all { it in setOf("quizRenderer", "trackingParams", "clickTrackingParams",
                "responseContext", "loggingDirectives", "accessibility", "accessibilityData", "rendererContext", "commandMetadata") })
            attachment.obj("quizRenderer")?.let(::postQuizAttachment) else null)
}

/** Reads only direct quiz data. Unknown presentation subtrees never supply identity, text or commands. */
internal fun postQuizAttachment(quiz: Json.Obj): PostQuiz? {
    fun label(value: Json?): String? {
        val node = value as? Json.Obj ?: return null
        val roles = listOf("simpleText", "content", "runs").filter(node.fields::containsKey)
        if (roles.size != 1) return null
        val result = when (roles.single()) {
            "simpleText", "content" -> (node.fields[roles.single()] as? Json.Str)?.value
            else -> {
                val runs = (node.fields["runs"] as? Json.Arr)?.items ?: return null
                if (runs.size !in 1..100) return null
                val parts = runs.map { (it as? Json.Obj)?.fields?.get("text") as? Json.Str }
                if (parts.any { it == null }) return null
                parts.joinToString("") { it!!.value }
            }
        }
        return result?.takeIf { it.isNotBlank() && it.length <= 16_384 }
    }
    val raw = (quiz.fields["choices"] as? Json.Arr)?.items ?: return null
    if (raw.size !in 1..100) return null
    val choices = ArrayList<PostQuizChoice>()
    for (value in raw) {
        val choice = value as? Json.Obj ?: return null
        val text = label(choice.fields["text"]) ?: return null
        val correct = (choice.fields["isCorrect"] as? Json.Bool)?.value ?: return null
        choices += PostQuizChoice(text, correct)
    }
    val votes = if (quiz.fields.containsKey("totalVotes")) label(quiz.fields["totalVotes"]) ?: return null else null
    return PostQuiz(choices.toList(), votes)
}

/** Direct video presentation only. Its menus and opaque service commands are never projected. */
internal fun postVideoAttachment(video: Json.Obj): MediaItem? {
    val id = video.text("videoId")?.takeIf { Regex("[A-Za-z0-9_-]{11}").matches(it) } ?: return null
    val navigation = video.obj("navigationEndpoint")?.obj("watchEndpoint")?.text("videoId")
    if (navigation != null && navigation != id) return null
    val bylines = listOf("longBylineText", "shortBylineText", "ownerText")
    val channels = bylines.flatMap { name ->
        (video.obj(name)?.fields?.get("runs") as? Json.Arr)?.items.orEmpty().mapNotNull { run ->
            (run as? Json.Obj)?.path("navigationEndpoint", "browseEndpoint")?.text("browseId")
                ?.takeIf { Regex("UC[A-Za-z0-9_-]{22}").matches(it) }
        }
    }.distinct()
    if (channels.size > 1) return null
    return MediaItem("video", id, video.display("title"), bylines.firstNotNullOfOrNull { video.display(it) },
        video.thumbnail("thumbnail")?.let { if (it.startsWith("//")) "https:$it" else it }, video.display("lengthText"),
        channelId = channels.singleOrNull(), viewCount = video.display("viewCountText") ?: video.display("shortViewCountText"),
        published = video.display("publishedTimeText"))
}

/** The posts list carries its own continuation item; a missing token means the list ended. */
internal fun postContinuation(root: Json): String? = root.objects("continuationItemRenderer").firstNotNullOfOrNull { renderer ->
    (renderer.path("continuationEndpoint", "continuationCommand")?.text("token")
        ?: renderer.path("button", "buttonRenderer", "command", "continuationCommand")?.text("token")
        ?: renderer.path("button", "buttonRenderer", "continuationCommand")?.text("token"))?.takeIf(String::isNotBlank)
}

/** Without an issued route, discover the channel's unique POSTS tab from its actual opening. */
fun loadChannelPosts(channelId: String, context: RequestContext = RequestContext(), params: String? = null,
    continuation: String? = null, http: HttpClient = JvmHttpClient()): ReadResult<PostPage> {
    if (params != null || continuation != null) return loadChannelPostsDirect(channelId, context, params, continuation, http)
    val response = requestObject(channelQuery(channelId, context).withCompactResponse(), http)
    if (!response.ok) return ReadResult(error = response.error)
    val root = response.value!!
    val tabs = ownedChannelTabs(root)
        ?: return ReadResult(error = RequestError(RequestFailure.PARSE, "unknown_channel_tabs"))
    val webTabs = root.obj("contents")?.fields?.keys?.any {
        it in setOf("twoColumnBrowseResultsRenderer", "singleColumnBrowseResultsRenderer")
    } == true
    // Explicit legacy post collections remain valid single-response openings.
    if (!webTabs) return ownedPostsResult(root, channelId, false)
    if (!channelPostsOpeningIdentity(root, channelId))
        return ReadResult(error = RequestError(RequestFailure.PARSE, "missing_or_conflicting_channel_identity"))
    val channel = readChannelPage(root, channelId, false)
    if (!channel.ok) return ReadResult(error = channel.error)
    if (channel.value!!.id != channelId)
        return ReadResult(error = RequestError(RequestFailure.PARSE, "channel_identity_mismatch"))
    val postTabs = tabs.filter { it.kind == ChannelTabKind.POSTS }
    if (postTabs.size != 1)
        return ReadResult(error = RequestError(RequestFailure.UNSUPPORTED, "channel_posts_unavailable_or_ambiguous"))
    val tab = postTabs.single()
    // A selected but unknown POSTS response cannot be rescued by navigating another surface.
    if (tab.selected) return ownedPostsResult(root, channelId, false)
    if (!channelTabHasNavigation(tab, channelId))
        return ReadResult(error = RequestError(RequestFailure.UNSUPPORTED, "channel_tab_has_no_navigation_capability"))
    return loadChannelPostsDirect(tab.browseId ?: channelId, context, tab.params, null, http)
}

private fun channelPostsOpeningIdentity(root: Json.Obj, channelId: String): Boolean {
    val identities = ArrayList<String>()
    fun declared(parent: String, renderer: String, field: String): Boolean {
        val container = root.fields[parent] ?: return true
        if (container !is Json.Obj) return false
        val source = container.fields[renderer] ?: return true
        if (source !is Json.Obj) return false
        val value = source.fields[field] ?: return true
        if (value !is Json.Str || !Regex("UC[A-Za-z0-9_-]{22}").matches(value.value)) return false
        identities += value.value
        return true
    }
    return declared("metadata", "channelMetadataRenderer", "externalId") &&
        declared("header", "c4TabbedHeaderRenderer", "channelId") &&
        identities.isNotEmpty() && identities.all { it == channelId }
}

/** An owner already chose this route or issued this continuation; never discover another tab. */
internal fun loadChannelPostsDirect(channelId: String, context: RequestContext, params: String? = null,
    continuation: String? = null, http: HttpClient): ReadResult<PostPage> {
    val result = requestObject(channelQuery(channelId, context, params, continuation).withCompactResponse(), http)
    if (!result.ok) return ReadResult(error = result.error)
    return ownedPostsResult(result.value!!, channelId, continuation != null)
}

private fun ownedPostsResult(root: Json.Obj, channelId: String, continuation: Boolean): ReadResult<PostPage> {
    val page = ownedChannelPosts(root, channelId, continuation)
        ?: return ReadResult(error = RequestError(RequestFailure.PARSE, "unknown_post_page"))
    return ReadResult(page)
}

/** The /post page is a web document: the post renderer lives in its inline ytInitialData. */
fun loadPost(postId: String, http: HttpClient = JvmHttpClient()): ReadResult<PostPage> = loadPost(postId, RequestContext(), http)

fun loadPost(postId: String, context: RequestContext, http: HttpClient = JvmHttpClient()): ReadResult<PostPage> {
    if (!postIdAccepted(postId))
        return ReadResult(error = RequestError(RequestFailure.INVALID, "invalid_post_id"))
    val headers = context.sessionHeaders()
    val reply = requestBody(HttpRequest("GET", "https://www.youtube.com/post/$postId?hl=en", headers), http)
    if (!reply.ok) return ReadResult(error = reply.error)
    val json = initialDataDocument(reply.value!!)
        ?: return ReadResult(error = RequestError(RequestFailure.PARSE, "missing_post_data"))
    val page = ownedPostDocument(json, postId)
        ?: return ReadResult(error = RequestError(RequestFailure.PARSE, "unknown_post_page"))
    val opening = ownedCommentOpening(json)
    val comments = opening?.let(::commentKey)
    if (opening != null && comments != null && discussionRoute(opening.obj("engagementPanelSectionListRenderer") ?: opening,
            comments, YOUTUBE_API + "browse")?.endpoint != YOUTUBE_API + "browse")
        return ReadResult(error = RequestError(RequestFailure.UNSUPPORTED, "unsupported_post_comment_route"))
    return ReadResult(page.copy(commentsKey = comments))
}

/** Post comments (including sorting and replies) carry WEB /browse continuations, not /next. */
fun loadPostComments(continuation: String, context: RequestContext = RequestContext(),
    http: HttpClient = JvmHttpClient()): ReadResult<CommentPage> {
    if (continuation.isBlank()) return ReadResult(error = RequestError(RequestFailure.INVALID, "missing_comment_key"))
    // WEB continuations must keep the authorized web identity so action tokens are returned.
    val web = if (context.signedIn) context else context.anonymous()
    val response = requestObject(browseQuery(null, web, continuation = continuation).withCompactResponse(), http)
    if (!response.ok) return ReadResult(error = response.error)
    val root = ownedCommentResponse(response.value!!)
        ?: return ReadResult(error = RequestError(RequestFailure.PARSE, "unknown_comment_page"))
    val parsed = readCommentPage(root)
    if (!parsed.ok) return parsed
    val total = root.objects("commentsHeaderRenderer").firstNotNullOfOrNull { it.display("countText") }
    return ReadResult(parsed.value!!.copy(total = total))
}
