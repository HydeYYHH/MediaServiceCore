package com.liskovsoft.youtubeapi.videoinfo.V2

enum class LibrarySection(val browseId: String) {
    SUBSCRIPTIONS("FEsubscriptions"), HISTORY("FEhistory"), LIBRARY("FEmy_youtube"), MY_VIDEOS("FEmy_videos"),
    PLAYLISTS("FEplaylist_aggregation"), WATCH_LATER("VLWL"), LIKED("VLLL")
}
fun loadLibrary(section: LibrarySection, context: RequestContext, continuation: String? = null,
    http: HttpClient = JvmHttpClient()): ReadResult<BrowsePage> {
    if (!context.signedIn) return ReadResult(error = RequestError(RequestFailure.AUTH, "sign_in_required"))
    return loadBrowse(section.browseId, context, continuation = continuation, http = http)
}
/** One page of the subscribed-channel directory; [nextKey] is null when the source ended. */
data class ChannelDirectoryPage(val channels: List<MediaItem>, val nextKey: String?)
fun loadSubscribedChannelsPage(context: RequestContext, continuation: String? = null,
    http: HttpClient = JvmHttpClient()): ReadResult<ChannelDirectoryPage> {
    if (!context.signedIn) return ReadResult(error = RequestError(RequestFailure.AUTH, "sign_in_required"))
    if (continuation == null) {
        val result = requestObject(youtubeQuery(YOUTUBE_API + "guide", "", context), http)
        if (!result.ok) return ReadResult(error = result.error)
        val items = result.value!!.objects("guideEntryRenderer").mapNotNull { entry ->
            val endpoint = entry.path("navigationEndpoint", "browseEndpoint") ?: return@mapNotNull null
            val id = endpoint.text("browseId")?.takeIf { it.startsWith("UC") } ?: return@mapNotNull null
            MediaItem("channel", id, entry.display("title") ?: entry.display("formattedTitle"), null, entry.thumbnail("thumbnail"), null, id,
                params = endpoint.text("params"), hasNewContent = (entry.fields["isNewContent"] as? Json.Bool)?.value == true)
        }
        // The guide returns the whole directory in one response; only its absence
        // falls back to the paginated channels browse.
        if (items.isNotEmpty()) return ReadResult(ChannelDirectoryPage(items.distinctBy { it.id }, null))
    }
    val channels = loadBrowse("FEchannels", context, continuation = continuation, http = http)
    if (!channels.ok) return ReadResult(error = channels.error)
    return ReadResult(ChannelDirectoryPage(channels.value!!.items.filter { it.kind == "channel" }.distinctBy { it.id },
        channels.value!!.nextKey))
}
fun loadSubscribedChannels(context: RequestContext, http: HttpClient = JvmHttpClient()): ReadResult<List<MediaItem>> {
    val result = loadSubscribedChannelsPage(context, http = http)
    return if (result.ok) ReadResult(result.value!!.channels) else ReadResult(error = result.error)
}
