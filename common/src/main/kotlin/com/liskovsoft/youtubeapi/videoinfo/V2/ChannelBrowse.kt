package com.liskovsoft.youtubeapi.videoinfo.V2

data class ChannelPage(val id: String?, val title: String?, val description: String?, val thumbnail: String?, val subscriberCount: String?, val page: BrowsePage,
    val handle: String? = null, val banner: String? = null, val videoCount: String? = null, val subscribed: Boolean? = null)
fun channelPage(body: String, channelId: String? = null): ChannelPage {
    val root = jsonObject(body)
    val metadata = root?.objects("channelMetadataRenderer")?.firstOrNull()
    val header = root?.objects("c4TabbedHeaderRenderer")?.firstOrNull()
    val channelHeader = root?.objects("channelHeaderRenderer")?.firstOrNull()
    val wrapped = root?.objects("pageHeaderRenderer")?.firstOrNull()
    val modern = root?.objects("pageHeaderViewModel")?.firstOrNull()
    val labels = modern?.path("metadata", "contentMetadataViewModel")?.let { content ->
        content.objects("text").mapNotNull { it.text("content") }
    }.orEmpty() + channelHeader?.obj("subtitle")?.objects("lineItemRenderer")
        .orEmpty().mapNotNull { it.display("text") }
    val subscribed = channelHeader?.objects("subscribeButtonRenderer")?.firstOrNull()?.bool("subscribed")
        ?: root?.objects("subscribeButtonRenderer")?.firstOrNull()?.bool("subscribed")
        ?: root?.objects("toggleButtonRenderer")?.firstOrNull { toggle ->
            toggle.objects("subscribeEndpoint").isNotEmpty() || toggle.objects("unsubscribeEndpoint").isNotEmpty()
        }?.bool("isToggled")
    return ChannelPage(metadata?.text("externalId") ?: header?.text("channelId") ?: channelId,
        metadata?.text("title") ?: header?.text("title") ?: channelHeader?.display("title") ?: modern?.objects("dynamicTextViewModel")?.firstOrNull()?.display("text")
            ?: wrapped?.text("pageTitle"),
        metadata?.text("description") ?: channelHeader?.objects("aboutChannelViewModel")?.firstOrNull()?.text("description")
            ?: channelHeader?.objects("selectableTextRenderer")?.firstOrNull()?.display("compactDescription")
            ?: modern?.objects("descriptionPreviewViewModel")?.firstOrNull()?.display("description"),
        metadata?.thumbnail("avatar") ?: header?.thumbnail("avatar") ?: channelHeader?.thumbnail("avatar")
            ?: modern?.objects("avatarViewModel")?.firstOrNull()?.thumbnail("image"),
        header?.display("subscriberCountText") ?: labels.firstOrNull { it.contains("subscriber", true) }, browsePage(body),
        header?.display("channelHandleText") ?: labels.firstOrNull { it.startsWith("@") },
        header?.thumbnail("banner") ?: channelHeader?.thumbnail("backgroundImage")
            ?: modern?.objects("imageBannerViewModel")?.firstOrNull()?.thumbnail("image"),
        header?.display("videosCountText") ?: labels.firstOrNull { it.contains("video", true) }, subscribed)
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

/** `@handle` names resolve through the navigation endpoint; a plain channel id passes through. */
fun resolveChannelId(id: String, context: RequestContext = RequestContext(), http: HttpClient = JvmHttpClient()): ReadResult<String> {
    if (!id.startsWith("@")) return ReadResult(id)
    val template = channelQuery("UC", context)
    val root = jsonObject(template.body) ?: return ReadResult(error = RequestError(RequestFailure.PARSE, "channel_not_found"))
    val fields = LinkedHashMap(root.fields)
    fields.remove("browseId")
    fields["url"] = Json.Str("https://www.youtube.com/" + id)
    val reply = requestObject(template.copy(url = YOUTUBE_API + "navigation/resolve_url", body = Json.Obj(fields).encode()), http)
    if (!reply.ok) return ReadResult(error = reply.error)
    val resolved = reply.value!!.objects("browseEndpoint").firstNotNullOfOrNull { it.text("browseId") }
        ?: return ReadResult(error = RequestError(RequestFailure.PARSE, "channel_not_found"))
    return ReadResult(resolved)
}
