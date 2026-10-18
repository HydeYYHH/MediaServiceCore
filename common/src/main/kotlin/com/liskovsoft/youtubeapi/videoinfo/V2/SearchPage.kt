package com.liskovsoft.youtubeapi.videoinfo.V2

data class MediaItem @JvmOverloads constructor(val kind: String, val id: String, val title: String?, val author: String?, val thumbnail: String?,
    val duration: String?, val channelId: String? = null, val playlistId: String? = null, val params: String? = null,
    val endpoint: ActionEndpoint? = null, val actions: List<PageAction> = emptyList(), val startSeconds: Long? = null,
    val progressPercent: Int? = null, val live: Boolean = false, val playerParams: String? = null)
data class MediaGroup @JvmOverloads constructor(val title: String?, val items: List<MediaItem>, val nextKey: String?, val actions: List<PageAction> = emptyList())
data class SearchPage @JvmOverloads constructor(val items: List<MediaItem>, val groups: List<MediaGroup>, val nextKey: String?,
    val messages: List<String> = emptyList(), val actions: List<PageAction> = emptyList())
fun searchPage(body: String): SearchPage {
    val root = jsonObject(body) ?: return SearchPage(emptyList(), emptyList(), null)
    return SearchPage(contentItems(root), pageGroups(root), pageKey(root), pageMessages(root), pageActions(root))
}
fun searchQuery(text: String?, context: RequestContext = RequestContext(), params: String? = null, continuation: String? = null): PlayerQuery {
    if (continuation.isNullOrBlank() && text.isNullOrBlank()) return PlayerQuery("", emptyMap(), "")
    return youtubeQuery(YOUTUBE_API + "search", if (continuation != null) payload("continuation" to continuation)
        else payload("query" to text, "params" to params), context)
}
fun loadSearch(text: String?, context: RequestContext = RequestContext(), params: String? = null, continuation: String? = null,
    http: HttpClient = JvmHttpClient()): ReadResult<SearchPage> {
    val reply = requestObject(searchQuery(text, context, params, continuation), http)
    // SearchService2.getSearch, upstream 6c02bd1b: a retry owns a fresh execution.
    if (!reply.ok && reply.error?.kind == RequestFailure.AUTH && context.accessToken != null) {
        val retry = requestObject(searchQuery(text, context.anonymous(), params, continuation), http)
        return if (retry.ok) readSearchPage(retry.value!!) else ReadResult(error = retry.error)
    }
    return if (reply.ok) readSearchPage(reply.value!!) else ReadResult(error = reply.error)
}
