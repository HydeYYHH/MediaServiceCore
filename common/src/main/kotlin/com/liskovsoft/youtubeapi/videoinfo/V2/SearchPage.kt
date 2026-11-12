package com.liskovsoft.youtubeapi.videoinfo.V2

data class MediaItem @JvmOverloads constructor(val kind: String, val id: String, val title: String?, val author: String?, val thumbnail: String?,
    val duration: String?, val channelId: String? = null, val playlistId: String? = null, val params: String? = null,
    val endpoint: ActionEndpoint? = null, val actions: List<PageAction> = emptyList(), val startSeconds: Long? = null,
    val progressPercent: Int? = null, val live: Boolean = false, val playerParams: String? = null,
    val authorThumbnail: String? = null, val viewCount: String? = null, val published: String? = null, val videoCountText: String? = null,
    val menuEndpoint: MenuEndpoint? = null, val hasNewContent: Boolean = false, val authorArtwork: Artwork? = null,
    /** Native community-post content for kind=post. Content images remain separate from the author avatar. */
    val post: PostItem? = null)
data class MediaGroup @JvmOverloads constructor(val title: String?, val items: List<MediaItem>, val nextKey: String?, val actions: List<PageAction> = emptyList())
data class SearchPage @JvmOverloads constructor(val items: List<MediaItem>, val groups: List<MediaGroup>, val nextKey: String?,
    val messages: List<String> = emptyList(), val actions: List<PageAction> = emptyList(),
    val filters: List<SearchFilter> = emptyList())
fun searchPage(body: String): SearchPage {
    val root = jsonObject(body) ?: return SearchPage(emptyList(), emptyList(), null)
    return searchPage(root)
}
internal fun searchPage(root: Json.Obj): SearchPage = searchPage(root, pureOwnedSearchCollection(root))

/** The network gate passes its exact projection; output never walks another response subtree. */
internal fun searchPage(root: Json.Obj, collection: SearchOwnedCollection?): SearchPage {
    if (collection == null) return SearchPage(emptyList(), emptyList(), null)
    val owned = ownedSearchActions(root)
    val validActions = owned?.valid != false
    val actions = owned?.actions ?: collection.fragmentActions
    val groups = if (validActions) collection.groups else collection.groups.map { it.copy(actions = emptyList()) }
    return SearchPage(collection.items, groups, collection.nextKey, collection.messages, actions,
        if (validActions || collection.legacy) ownedSearchFilters(root, collection.legacy) else emptyList())
}

fun searchQuery(text: String?, context: RequestContext = RequestContext(), params: String? = null, continuation: String? = null): PlayerQuery {
    if (continuation.isNullOrBlank() && text.isNullOrBlank()) return PlayerQuery("", emptyMap(), "")
    return youtubeQuery(YOUTUBE_API + "search", if (continuation != null) payload("continuation" to continuation)
        else payload("query" to text, "params" to params), context)
}
fun loadSearch(text: String?, context: RequestContext = RequestContext(), params: String? = null, continuation: String? = null,
    http: HttpClient = JvmHttpClient()): ReadResult<SearchPage> {
    val reply = requestObject(searchQuery(text, context, params, continuation).withCompactResponse(), http)
    if (!reply.ok) return ReadResult(error = reply.error)
    val parsed = readSearchPage(reply.value!!, continuation != null)
    if (!parsed.ok) return parsed
    if (ownedSearchActions(reply.value!!)?.valid == false)
        return ReadResult(error = RequestError(RequestFailure.PARSE, "unknown_search_action_source"))
    return parsed
}
