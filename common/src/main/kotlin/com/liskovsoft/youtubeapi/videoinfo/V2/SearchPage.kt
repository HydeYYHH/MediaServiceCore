package com.liskovsoft.youtubeapi.videoinfo.V2

data class MediaItem(val kind: String, val id: String, val title: String?, val author: String?, val thumbnail: String?,
    val duration: String?, val channelId: String? = null, val playlistId: String? = null, val params: String? = null)
data class MediaGroup(val title: String?, val items: List<MediaItem>, val nextKey: String?)
data class SearchPage(val items: List<MediaItem>, val groups: List<MediaGroup>, val nextKey: String?)
internal fun contentItems(root: Json): List<MediaItem> {
    val items = ArrayList<MediaItem>()
    val types = mapOf("videoRenderer" to "video", "gridVideoRenderer" to "video", "compactVideoRenderer" to "video",
        "playlistVideoRenderer" to "video", "reelItemRenderer" to "short", "channelRenderer" to "channel",
        "gridChannelRenderer" to "channel", "compactChannelRenderer" to "channel", "playlistRenderer" to "playlist",
        "gridPlaylistRenderer" to "playlist", "compactPlaylistRenderer" to "playlist", "tileRenderer" to "tile")
    fun visit(node: Json) {
        when (node) {
            is Json.Obj -> {
                val type = node.fields.keys.firstOrNull { it in types }
                if (type != null) {
                    val renderer = node.obj(type)!!
                    val endpoint = renderer.obj("navigationEndpoint") ?: renderer.path("onSelectCommand", "watchEndpoint")?.let { Json.Obj(mapOf("watchEndpoint" to it)) }
                    val watch = endpoint?.obj("watchEndpoint")
                    val browse = endpoint?.obj("browseEndpoint")
                    val id = renderer.text("videoId") ?: renderer.text("channelId") ?: renderer.text("playlistId")
                        ?: watch?.text("videoId") ?: browse?.text("browseId")
                    if (id != null) {
                        val metadata = renderer.path("metadata", "tileMetadataRenderer")
                        val kind = if (types[type] != "tile") types.getValue(type) else if (watch != null) "video" else if (id.startsWith("UC")) "channel" else "playlist"
                        items.add(MediaItem(kind, id, renderer.display("title") ?: renderer.display("headline") ?: metadata?.display("title"),
                            renderer.display("ownerText") ?: renderer.display("shortBylineText") ?: renderer.display("longBylineText"),
                            renderer.thumbnail("thumbnail") ?: renderer.path("header", "tileHeaderRenderer")?.thumbnail("thumbnail"),
                            renderer.display("lengthText"), renderer.text("channelId") ?: renderer.objects("browseEndpoint").firstOrNull()?.text("browseId"),
                            renderer.text("playlistId") ?: watch?.text("playlistId"), browse?.text("params")))
                    }
                    return
                }
                node.fields.values.forEach { visit(it) }
            }
            is Json.Arr -> node.items.forEach { visit(it) }
            else -> Unit
        }
    }
    visit(root)
    return items.distinctBy { it.kind to it.id }
}
internal fun pageGroups(root: Json): List<MediaGroup> =
    (root.objects("shelfRenderer") + root.objects("richShelfRenderer") + root.objects("itemSectionRenderer")).map {
        MediaGroup(it.display("title"), contentItems(it), pageKey(it))
    }.filter { it.items.isNotEmpty() }
fun searchPage(body: String): SearchPage {
    val root = jsonObject(body) ?: return SearchPage(emptyList(), emptyList(), null)
    return SearchPage(contentItems(root), pageGroups(root), pageKey(root))
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
        return if (retry.ok) ReadResult(searchPage(retry.value!!.encode())) else ReadResult(error = retry.error)
    }
    return if (reply.ok) ReadResult(searchPage(reply.value!!.encode())) else ReadResult(error = reply.error)
}
