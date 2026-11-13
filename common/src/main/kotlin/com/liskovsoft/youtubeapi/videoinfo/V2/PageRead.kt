package com.liskovsoft.youtubeapi.videoinfo.V2

import java.net.URLDecoder

/** Large page reads need the JSON data, without response formatting whitespace. */
internal fun PlayerQuery.withCompactResponse(): PlayerQuery {
    if (!url.startsWith(YOUTUBE_API)) return this
    val base = url.substringBefore('#')
    val parameters = base.substringAfter('?', "").split('&')
    if (parameters.any { parameter ->
        val name = parameter.substringBefore('=')
        name == "prettyPrint" || runCatching { URLDecoder.decode(name, "UTF-8") }.getOrNull() == "prettyPrint"
    }) return this
    val separator = when {
        '?' !in base -> "?"
        base.endsWith('?') || base.endsWith('&') -> ""
        else -> "&"
    }
    return copy(url = base + separator + "prettyPrint=false" + url.removePrefix(base))
}

internal fun pageMessages(root: Json): List<String> =
    listOf("feedNudgeRenderer", "messageRenderer", "backgroundPromoRenderer", "genericPromoRenderer", "alertRenderer").flatMap { name ->
        root.objects(name).mapNotNull { it.display("title") ?: it.display("text") ?: it.display("message") ?: it.display("bodyText") }
    }.distinct()

private val listRenderers = mapOf("gridRenderer" to "items", "richGridRenderer" to "contents",
    "sectionListRenderer" to "contents", "itemSectionRenderer" to "contents", "horizontalListRenderer" to "items",
    "playlistVideoListRenderer" to "contents")
private val browseContinuations = mapOf("gridContinuation" to "items", "richGridContinuation" to "contents",
    "sectionListContinuation" to "contents", "itemSectionContinuation" to "contents",
    "horizontalListContinuation" to "items", "playlistVideoListContinuation" to "contents",
    "tvSurfaceContentContinuation" to "content")
private val commentContinuations = mapOf("itemSectionContinuation" to "contents", "commentRepliesContinuation" to "contents")
private val watchContinuations = mapOf("sectionListContinuation" to "contents", "itemSectionContinuation" to "contents",
    "horizontalListContinuation" to "items", "tvSurfaceContentContinuation" to "content")

/** Empty arrays count only inside a renderer whose list semantics we understand. */
private fun knownEmptyList(root: Json): Boolean = listRenderers.any { (renderer, field) ->
    root.objects(renderer).any { (it.fields[field] as? Json.Arr)?.items?.isEmpty() == true }
}
private fun rootEmptyList(root: Json.Obj) = (root.fields["contents"] as? Json.Arr)?.items?.isEmpty() == true
private fun initialEnvelope(root: Json.Obj, primaryContents: Set<String> = emptySet()): Boolean {
    if ("continuationContents" in root.fields) return false
    if (listOf("onResponseReceivedActions", "onResponseReceivedEndpoints", "onResponseReceivedCommands").none { it in root.fields }) return true
    // Initial WEB pages can include auxiliary response actions. Their operation's
    // primary contents must establish the initial envelope independently of those actions.
    val contents = root.obj("contents") ?: return false
    return primaryContents.any { name ->
        val primary = contents.obj(name) ?: return@any false
        when (name) {
            "twoColumnWatchNextResults", "singleColumnWatchNextResults" ->
                primary.path("results", "results")?.fields?.get("contents") is Json.Arr ||
                    primary.path("secondaryResults", "secondaryResults")?.fields?.get("results") is Json.Arr ||
                    primary.path("pivot", "sectionListRenderer")?.fields?.get("contents") is Json.Arr
            "twoColumnBrowseResultsRenderer", "singleColumnBrowseResultsRenderer" -> primary.fields["tabs"] is Json.Arr
            "twoColumnSearchResultsRenderer", "singleColumnSearchResultsRenderer" ->
                primary.path("primaryContents", "sectionListRenderer")?.fields?.get("contents") is Json.Arr ||
                    primary.obj("sectionListRenderer")?.fields?.get("contents") is Json.Arr
            "tvBrowseRenderer" -> primary.path("content", "tvSurfaceContentRenderer")?.obj("content") != null ||
                primary.path("content", "tvSecondaryNavRenderer")?.fields?.get("sections") is Json.Arr
            "kidsHomeScreenRenderer", "playlistVideoListRenderer" -> primary.fields["contents"] is Json.Arr
            else -> false
        }
    }
}
private val browsePrimaryContents = setOf("twoColumnBrowseResultsRenderer", "singleColumnBrowseResultsRenderer",
    "tvBrowseRenderer", "kidsHomeScreenRenderer")

/** The server's metadata-only continuation end must be present and correctly typed. */
private fun knownMetadataEnd(root: Json.Obj): Boolean =
    root.fields["responseContext"] is Json.Obj &&
        root.fields.keys.all { it in setOf("responseContext", "trackingParams") } &&
        ("trackingParams" !in root.fields || root.fields["trackingParams"] is Json.Str)

/** A continuation must use the operation's known envelope. Arbitrary items[] is not an end marker. */
private fun knownContinuation(root: Json.Obj, types: Map<String, String>, hasContent: Boolean): Boolean {
    // This small envelope is an actual end response only after a continuation request.
    if (knownMetadataEnd(root)) return true
    val payloads = ArrayList<Json>()
    if ("continuationContents" in root.fields) {
        val contents = root.obj("continuationContents") ?: return false
        if (contents.fields.isEmpty()) return false
        for ((kind, value) in contents.fields) {
            val field = types[kind] ?: return false
            val node = value as? Json.Obj ?: return false
            val items = node.fields[field] ?: return false
            if (field == "content") {
                if (items !is Json.Obj) return false
            } else if (items !is Json.Arr) return false
            payloads += items
        }
    } else {
        for (name in listOf("onResponseReceivedActions", "onResponseReceivedEndpoints", "onResponseReceivedCommands")) {
            if (name !in root.fields) continue
            val actions = root.fields[name] as? Json.Arr ?: return false
            for (value in actions.items) {
                val action = value as? Json.Obj ?: return false
                for (kind in listOf("appendContinuationItemsAction", "reloadContinuationItemsCommand")) {
                    if (kind !in action.fields) continue
                    val items = action.obj(kind)?.fields?.get("continuationItems") as? Json.Arr ?: return false
                    payloads += items
                }
            }
        }
    }
    if (payloads.isEmpty()) return false
    return hasContent || payloads.all { (it is Json.Arr && it.items.isEmpty()) || knownEmptyList(it) }
}

private fun <T> pageRead(page: T, known: Boolean, code: String): ReadResult<T> =
    if (known) ReadResult(page) else ReadResult(error = RequestError(RequestFailure.PARSE, code))

internal fun readBrowsePage(root: Json.Obj, continuation: Boolean = false): ReadResult<BrowsePage> {
    val page = browsePage(root)
    val content = page.items.isNotEmpty() || page.tabs.isNotEmpty() || page.messages.isNotEmpty() ||
        page.nextKey != null || page.actions.isNotEmpty()
    val known = if (continuation) knownContinuation(root, browseContinuations, content)
        else initialEnvelope(root, browsePrimaryContents) && (content || rootEmptyList(root) || knownEmptyList(root))
    return pageRead(page, known, "unknown_browse_page")
}

internal fun readSearchPage(root: Json.Obj, continuation: Boolean = false): ReadResult<SearchPage> {
    val collection = ownedSearchCollection(root, continuation)
        ?: return ReadResult(error = RequestError(RequestFailure.PARSE, "unknown_search_page"))
    val actions = ownedSearchActions(root)
    if (actions?.valid == false)
        return ReadResult(error = RequestError(RequestFailure.PARSE, "unknown_search_action_source"))
    return ReadResult(searchPage(root, collection, actions))
}

internal fun readChannelPage(root: Json.Obj, channelId: String, continuation: Boolean): ReadResult<ChannelPage> {
    val page = channelPage(root, channelId)
    val content = page.page.items.isNotEmpty() || page.page.tabs.isNotEmpty() || page.page.messages.isNotEmpty() ||
        page.page.nextKey != null || page.page.actions.isNotEmpty()
    val header = page.title != null || page.description != null || page.thumbnail != null ||
        root.objects("channelMetadataRenderer").any { !it.text("externalId").isNullOrBlank() }
    val known = if (continuation) knownContinuation(root, browseContinuations, content)
        else initialEnvelope(root, browsePrimaryContents) && (header || content || knownEmptyList(root))
    return pageRead(page, known, "unknown_channel_page")
}

internal fun readPlaylistPage(root: Json.Obj, continuation: Boolean, expectedPlaylistId: String? = null): ReadResult<PlaylistPage> {
    if (continuation && knownMetadataEnd(root)) return ReadResult(PlaylistPage(null, null, emptyList(), null))
    val owned = ownedPlaylistCollection(root, continuation, expectedPlaylistId)
    val page = owned?.let { playlistPage(root, it) }
    return pageRead(page ?: PlaylistPage(null, null, emptyList(), null), page != null, "unknown_playlist_page")
}

internal fun readWatchPage(root: Json.Obj, videoId: String?, continuation: Boolean): ReadResult<WatchPage> {
    val parsed = watchPage(root, videoId)
    val comments = ownedCommentOpening(root, videoId)?.let { watchPage(it, videoId) }
    val page = parsed.copy(commentsKey = comments?.commentsKey, commentCreateKey = comments?.commentCreateKey,
        commentSortOptions = comments?.commentSortOptions.orEmpty(), commentCount = comments?.commentCount,
        commentsTurnedOff = comments?.commentsTurnedOff ?: false)
    val content = page.related.isNotEmpty() || page.nextKey != null || page.commentsKey != null || page.chatKey != null ||
        page.queue != null || page.chapters.isNotEmpty() || page.title != null || page.commentCreateKey != null ||
        page.commentSortOptions.isNotEmpty() || page.commentsTurnedOff || pageMessages(root).isNotEmpty()
    val details = root.obj("videoDetails")?.text("videoId")?.isNotBlank() == true
    val results = root.path("contents", "singleColumnWatchNextResults", "results", "results")
        ?: root.path("contents", "twoColumnWatchNextResults", "results", "results")
    val empty = (results?.fields?.get("contents") as? Json.Arr)?.items?.isEmpty() == true
    val known = if (continuation) knownContinuation(root, watchContinuations, content)
        else initialEnvelope(root, setOf("twoColumnWatchNextResults", "singleColumnWatchNextResults")) && (content || details || empty)
    return pageRead(page, known, "unknown_watch_page")
}

internal fun readCommentPage(root: Json.Obj, allowEmptyReplies: Boolean = false): ReadResult<CommentPage> {
    val scoped = ownedCommentResponse(root, allowEmptyReplies)
        ?: return pageRead(CommentPage(emptyList(), null, null), false, "unknown_comment_page")
    return ReadResult(commentPage(scoped))
}

/** After deleting the last reply WEB omits the empty protobuf list, retaining only the reply target. */
private fun omittedEmptyReplyItems(root: Json.Obj): Boolean {
    if (root.fields.keys.any { it !in setOf("responseContext", "trackingParams", "onResponseReceivedEndpoints") }) return false
    if (root.fields["responseContext"]?.let { it !is Json.Obj } == true || root.fields["trackingParams"]?.let { it !is Json.Str } == true) return false
    val actions = root.fields["onResponseReceivedEndpoints"] as? Json.Arr ?: return false
    val action = actions.items.singleOrNull() as? Json.Obj ?: return false
    if (action.fields.keys.any { it !in setOf("clickTrackingParams", "appendContinuationItemsAction") }) return false
    val append = action.obj("appendContinuationItemsAction") ?: return false
    if (append.fields.keys != setOf("targetId")) return false
    val target = append.text("targetId") ?: return false
    return target.matches(Regex("comment-replies-item-[A-Za-z0-9_.-]{1,1024}"))
}
