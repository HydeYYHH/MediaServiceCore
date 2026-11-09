package com.liskovsoft.youtubeapi.videoinfo.V2

data class BrowseTab(val title: String?, val browseId: String?, val params: String?, val selected: Boolean)
data class BrowsePage @JvmOverloads constructor(val items: List<MediaItem>, val groups: List<MediaGroup>, val tabs: List<BrowseTab>, val nextKey: String?,
    val messages: List<String> = emptyList(), val actions: List<PageAction> = emptyList(), val visitor: String? = null)
fun browsePage(body: String): BrowsePage = browsePage(jsonObject(body))

internal fun browsePage(root: Json.Obj?): BrowsePage {
    root ?: return BrowsePage(emptyList(), emptyList(), emptyList(), null)
    val tabs = root.objects("tabRenderer").map {
        val endpoint = (it.obj("endpoint") ?: it.obj("navigationEndpoint"))?.obj("browseEndpoint")
        BrowseTab(it.text("title") ?: it.display("title"), endpoint?.text("browseId"), endpoint?.text("params"), it.bool("selected") == true)
    }.ifEmpty {
        root.objects("tileRenderer").mapNotNull { tile ->
            if (tile.text("style") != "TILE_STYLE_YTLR_WORMHOLE_RECTANGULAR") return@mapNotNull null
            val endpoint = tile.path("onSelectCommand", "browseEndpoint") ?: return@mapNotNull null
            val title = tile.objects("tileMetadataRenderer").firstNotNullOfOrNull { it.display("title") }
            BrowseTab(title, endpoint.text("browseId"), endpoint.text("params"), false)
        }.distinctBy { it.browseId to it.params }
    }
    // Continuation responses wrap their content in continuationContents.<kind>Continuation.
    val continued = root.obj("continuationContents")?.fields?.values?.singleOrNull() as? Json.Obj
    val selected = continued
        ?: root.objects("tabRenderer").firstOrNull { it.bool("selected") == true }?.obj("content") ?: root
    val messages = pageMessages(selected)
    return BrowsePage(contentItems(selected), pageGroups(selected), tabs, pageKey(selected, forwardOnly = true), messages, pageActions(selected),
        root.obj("responseContext")?.text("visitorData"))
}
fun browseQuery(browseId: String?, context: RequestContext = RequestContext(), params: String? = null, continuation: String? = null): PlayerQuery {
    if (browseId.isNullOrBlank() && continuation.isNullOrBlank()) return PlayerQuery("", emptyMap(), "")
    return youtubeQuery(YOUTUBE_API + "browse", if (continuation != null) payload("continuation" to continuation)
        else payload("browseId" to browseId, "params" to params), context)
}
fun loadBrowse(browseId: String?, context: RequestContext = RequestContext(), params: String? = null, continuation: String? = null,
    http: HttpClient = JvmHttpClient()): ReadResult<BrowsePage> {
    val reply = requestObject(browseQuery(browseId, context, params, continuation).withCompactResponse(), http)
    return if (reply.ok) readBrowsePage(reply.value!!) else ReadResult(error = reply.error)
}
