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
fun loadSubscribedChannels(context: RequestContext, http: HttpClient = JvmHttpClient()): ReadResult<List<MediaItem>> {
    if (!context.signedIn) return ReadResult(error = RequestError(RequestFailure.AUTH, "sign_in_required"))
    val result = requestObject(youtubeQuery(YOUTUBE_API + "guide", "", context), http)
    if (!result.ok) return ReadResult(error = result.error)
    val items = result.value!!.objects("guideEntryRenderer").mapNotNull { entry ->
        val endpoint = entry.path("navigationEndpoint", "browseEndpoint") ?: return@mapNotNull null
        val id = endpoint.text("browseId")?.takeIf { it.startsWith("UC") } ?: return@mapNotNull null
        MediaItem("channel", id, entry.display("title") ?: entry.display("formattedTitle"), null, entry.thumbnail("thumbnail"), null, id, params = endpoint.text("params"))
    }
    if (items.isNotEmpty()) return ReadResult(items.distinctBy { it.id })
    val channels = loadBrowse("FEchannels", context, http = http)
    return if (channels.ok) ReadResult(channels.value!!.items.filter { it.kind == "channel" }.distinctBy { it.id })
        else ReadResult(error = channels.error)
}
