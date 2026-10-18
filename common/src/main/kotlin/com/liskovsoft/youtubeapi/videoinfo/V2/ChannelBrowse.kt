package com.liskovsoft.youtubeapi.videoinfo.V2

data class ChannelPage(val id: String?, val title: String?, val description: String?, val thumbnail: String?, val subscriberCount: String?, val page: BrowsePage)
fun channelPage(body: String, channelId: String? = null): ChannelPage {
    val root = jsonObject(body)
    val metadata = root?.objects("channelMetadataRenderer")?.firstOrNull()
    val header = root?.objects("c4TabbedHeaderRenderer")?.firstOrNull()
    return ChannelPage(metadata?.text("externalId") ?: header?.text("channelId") ?: channelId,
        metadata?.text("title") ?: header?.text("title"), metadata?.text("description"),
        metadata?.thumbnail("avatar") ?: header?.thumbnail("avatar"), header?.display("subscriberCountText"), browsePage(body))
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
