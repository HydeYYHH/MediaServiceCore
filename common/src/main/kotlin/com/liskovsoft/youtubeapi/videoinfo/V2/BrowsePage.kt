package com.liskovsoft.youtubeapi.videoinfo.V2

data class BrowseTab(val title: String?, val browseId: String?, val params: String?, val selected: Boolean)
data class BrowsePage(val items: List<MediaItem>, val groups: List<MediaGroup>, val tabs: List<BrowseTab>, val nextKey: String?,
    val messages: List<String> = emptyList(), val actions: List<PageAction> = emptyList())
fun browsePage(body: String): BrowsePage {
    val root = jsonObject(body) ?: return BrowsePage(emptyList(), emptyList(), emptyList(), null)
    val tabs = root.objects("tabRenderer").map {
        val endpoint = (it.obj("endpoint") ?: it.obj("navigationEndpoint"))?.obj("browseEndpoint")
        BrowseTab(it.text("title") ?: it.display("title"), endpoint?.text("browseId"), endpoint?.text("params"), it.bool("selected") == true)
    }
    val selected = root.objects("tabRenderer").firstOrNull { it.bool("selected") == true }?.obj("content") ?: root
    val messages = listOf("feedNudgeRenderer","messageRenderer","backgroundPromoRenderer","alertRenderer").flatMap { name ->
        selected.objects(name).mapNotNull { it.display("title") ?: it.display("text") ?: it.display("message") }
    }.distinct()
    return BrowsePage(contentItems(selected), pageGroups(selected), tabs, pageKey(selected), messages, pageActions(selected))
}
fun browseQuery(browseId: String?, context: RequestContext = RequestContext(), params: String? = null, continuation: String? = null): PlayerQuery {
    if (browseId.isNullOrBlank() && continuation.isNullOrBlank()) return PlayerQuery("", emptyMap(), "")
    return youtubeQuery(YOUTUBE_API + "browse", if (continuation != null) payload("continuation" to continuation)
        else payload("browseId" to browseId, "params" to params), context)
}
fun loadBrowse(browseId: String?, context: RequestContext = RequestContext(), params: String? = null, continuation: String? = null,
    http: HttpClient = JvmHttpClient()): ReadResult<BrowsePage> {
    val reply = requestObject(browseQuery(browseId, context, params, continuation), http)
    return if (reply.ok) ReadResult(browsePage(reply.value!!.encode())) else ReadResult(error = reply.error)
}
