package com.liskovsoft.youtubeapi.videoinfo.V2

data class ChannelPage(val id: String?, val title: String?, val description: String?, val thumbnail: String?, val subscriberCount: String?, val page: BrowsePage,
    val handle: String? = null, val banner: String? = null, val videoCount: String? = null)
fun channelPage(body: String, channelId: String? = null): ChannelPage {
    val root = jsonObject(body)
    val metadata = root?.objects("channelMetadataRenderer")?.firstOrNull()
    val header = root?.objects("c4TabbedHeaderRenderer")?.firstOrNull()
    val modern = root?.objects("pageHeaderViewModel")?.firstOrNull()
    val labels = modern?.path("metadata", "contentMetadataViewModel")?.let { content ->
        content.objects("text").mapNotNull { it.text("content") }
    }.orEmpty()
    return ChannelPage(metadata?.text("externalId") ?: header?.text("channelId") ?: channelId,
        metadata?.text("title") ?: header?.text("title") ?: modern?.objects("dynamicTextViewModel")?.firstOrNull()?.display("text"),
        metadata?.text("description") ?: modern?.objects("descriptionPreviewViewModel")?.firstOrNull()?.display("description"),
        metadata?.thumbnail("avatar") ?: header?.thumbnail("avatar") ?: modern?.objects("avatarViewModel")?.firstOrNull()?.thumbnail("image"),
        header?.display("subscriberCountText") ?: labels.firstOrNull { it.contains("subscriber", true) }, browsePage(body),
        header?.display("channelHandleText") ?: labels.firstOrNull { it.startsWith("@") },
        header?.thumbnail("banner") ?: modern?.objects("imageBannerViewModel")?.firstOrNull()?.thumbnail("image"),
        header?.display("videosCountText") ?: labels.firstOrNull { it.contains("video", true) })
}
fun channelQuery(channelId: String, context: RequestContext = RequestContext(), params: String? = null,
    continuation: String? = null, search: String? = null): PlayerQuery {
    if (!videoIdAccepted(channelId)) return PlayerQuery("", emptyMap(), "")
    if (search == null || continuation != null) return browseQuery(channelId, context, params, continuation)
    return youtubeQuery(YOUTUBE_API + "browse", payload("browseId" to channelId, "params" to (params ?: "EgZzZWFyY2jyBgQKAloA"), "query" to search), context)
}
fun loadChannel(channelId: String, context: RequestContext = RequestContext(), params: String? = null, continuation: String? = null,
    search: String? = null, http: HttpClient = JvmHttpClient()): ReadResult<ChannelPage> {
    val result = requestObject(channelQuery(channelId, context, params, continuation, search), http)
    return if (result.ok) ReadResult(channelPage(result.value!!.encode(), channelId)) else ReadResult(error = result.error)
}
