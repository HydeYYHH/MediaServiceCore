package com.liskovsoft.youtubeapi.videoinfo.V2

import java.net.URI

internal data class DiscussionRoute(val endpoint: String, val key: String) {
    override fun toString() = "DiscussionRoute"
}
internal data class DiscussionDocument(val root: Json.Obj, val page: CommentPage, val endpoint: String)
internal data class DiscussionOpening(val root: Json.Obj, val watch: WatchPage, val route: DiscussionRoute?)
internal class WatchDiscussionOpening(val identity: String, val language: String, val region: String,
    val result: ReadResult<DiscussionOpening>) {
    fun matches(context: RequestContext) = identity == context.ledgerScope() && language == context.language && region == context.region
}
private const val DISCUSSION_NEXT = YOUTUBE_API + "next"

/** Commands may switch between /next and /browse; no other destination can receive discussion credentials. */
private fun discussionEndpoint(raw: String): String? = runCatching {
    val uri = URI(raw)
    if (uri.rawUserInfo != null || uri.rawFragment != null || uri.port !in listOf(-1, 443)) return@runCatching null
    if (uri.isAbsolute && (uri.scheme != "https" || uri.host != "www.youtube.com")) return@runCatching null
    if (!uri.isAbsolute && uri.rawAuthority != null) return@runCatching null
    when (uri.path) {
        "/youtubei/v1/next" -> DISCUSSION_NEXT
        "/youtubei/v1/browse" -> YOUTUBE_API + "browse"
        else -> null
    }
}.getOrNull()

/** Route lookup is restricted to the key already selected by the operation's parser. */
internal fun discussionRoute(node: Json, key: String?, inherited: String): DiscussionRoute? {
    if (key.isNullOrBlank()) return null
    fun visit(value: Json, endpoint: String?): DiscussionRoute? = when (value) {
        is Json.Obj -> {
            val declared = value.path("commandMetadata", "webCommandMetadata")?.text("apiUrl")
            val current = if (declared == null) endpoint else discussionEndpoint(declared)
            val matching = value.obj("continuationCommand")?.text("token") == key ||
                listOf("nextContinuationData", "reloadContinuationData").any { value.obj(it)?.text("continuation") == key }
            if (matching) current?.let { DiscussionRoute(it, key) }
            else value.fields.values.firstNotNullOfOrNull { visit(it, current) }
        }
        is Json.Arr -> value.items.firstNotNullOfOrNull { visit(it, endpoint) }
        else -> null
    }
    return visit(node, inherited)
}

internal fun readDiscussionOpening(videoId: String, context: RequestContext, http: HttpClient): ReadResult<DiscussionOpening> {
    val reply = requestObject(youtubeQuery(DISCUSSION_NEXT, payload("videoId" to videoId), context).withCompactResponse(), http)
    if (!reply.ok) return ReadResult(error = reply.error)
    val root = reply.value!!
    val parsed = readWatchPage(root, videoId, false)
    if (!parsed.ok) return ReadResult(error = parsed.error)
    return parseDiscussionOpening(root, videoId, parsed.value!!)
}

internal fun parseDiscussionOpening(root: Json.Obj, videoId: String, watch: WatchPage): ReadResult<DiscussionOpening> {
    val scoped = ownedCommentOpening(root, videoId)
        ?: return ReadResult(error = RequestError(RequestFailure.PARSE, "unknown_comment_opening"))
    val key = watch.commentsKey
    val panel = scoped.objects("engagementPanelSectionListRenderer").firstOrNull {
        it.text("panelIdentifier") in setOf("engagement-panel-comments-section", "comment-item-section")
    } ?: scoped.objects("itemSectionRenderer").firstOrNull {
        it.text("targetId") == "comments-section" || it.text("sectionIdentifier") == "comment-item-section"
    }
    val route = key?.let { discussionRoute(panel ?: scoped, it, DISCUSSION_NEXT) }
    if (key != null && route == null) return ReadResult(error = RequestError(RequestFailure.UNSUPPORTED, "unsupported_comment_root_route"))
    return ReadResult(DiscussionOpening(scoped, watch, route))
}

internal fun readDiscussion(route: DiscussionRoute, context: RequestContext, http: HttpClient,
    replies: Boolean = false): ReadResult<DiscussionDocument> {
    val reply = requestObject(youtubeQuery(route.endpoint, payload("continuation" to route.key), context).withCompactResponse(), http)
    if (!reply.ok) return ReadResult(error = reply.error)
    val scoped = ownedCommentResponse(reply.value!!, replies)
        ?: return ReadResult(error = RequestError(RequestFailure.PARSE, "unknown_comment_page"))
    val parsed = readCommentPage(scoped, allowEmptyReplies = replies)
    return if (parsed.ok) ReadResult(DiscussionDocument(scoped, parsed.value!!, route.endpoint)) else ReadResult(error = parsed.error)
}
