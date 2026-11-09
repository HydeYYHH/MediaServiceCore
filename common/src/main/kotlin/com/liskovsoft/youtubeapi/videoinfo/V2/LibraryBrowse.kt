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

/** Both consumers of an initial subscriptions browse, parsed from the same response. */
class SubscriptionsBrowse internal constructor(val feed: ReadResult<BrowsePage>, val channels: List<MediaItem>,
    internal val scope: String)

fun loadSubscriptionsBrowse(context: RequestContext, http: HttpClient = JvmHttpClient()): ReadResult<SubscriptionsBrowse> {
    if (!context.signedIn) return ReadResult(error = RequestError(RequestFailure.AUTH, "sign_in_required"))
    val result = requestObject(browseQuery(LibrarySection.SUBSCRIPTIONS.browseId, context).withCompactResponse(), http)
    if (!result.ok) return ReadResult(error = result.error)
    val root = result.value!!
    return ReadResult(SubscriptionsBrowse(readBrowsePage(root), subscriptionChannelTabs(root), context.scope()))
}

fun loadSubscribedChannelsPage(context: RequestContext, continuation: String? = null,
    http: HttpClient = JvmHttpClient()): ReadResult<ChannelDirectoryPage> = subscribedChannelsPage(context, continuation, http, null)

/** An opened initial browse is reusable only for the exact session that requested it. */
fun loadSubscribedChannelsPage(context: RequestContext, opened: SubscriptionsBrowse,
    http: HttpClient = JvmHttpClient()): ReadResult<ChannelDirectoryPage> = subscribedChannelsPage(context, null, http, opened)

private fun subscribedChannelsPage(context: RequestContext, continuation: String?, http: HttpClient,
    opened: SubscriptionsBrowse?): ReadResult<ChannelDirectoryPage> {
    if (!context.signedIn) return ReadResult(error = RequestError(RequestFailure.AUTH, "sign_in_required"))
    if (continuation == null) {
        // OAuth uses TVHTML5: its guide has no subscriptions. The subscriptions
        // browse carries the complete channel tabs and their NEW_CONTENT markers.
        if (context.accessToken != null) {
            val subscriptions = opened?.takeIf { it.scope == context.scope() }?.let { ReadResult(it) }
                ?: loadSubscriptionsBrowse(context, http)
            if (!subscriptions.ok) return ReadResult(error = subscriptions.error)
            val tabs = subscriptions.value!!.channels
            if (tabs.isNotEmpty()) return ReadResult(ChannelDirectoryPage(tabs, null))
        }
        val result = requestObject(youtubeQuery(YOUTUBE_API + "guide", "", context), http)
        if (!result.ok) return ReadResult(error = result.error)
        val items = result.value!!.objects("guideEntryRenderer").mapNotNull { entry ->
            val endpoint = entry.path("navigationEndpoint", "browseEndpoint") ?: return@mapNotNull null
            val id = endpoint.text("browseId")?.takeIf { it.startsWith("UC") } ?: return@mapNotNull null
            MediaItem("channel", id, entry.display("title") ?: entry.display("formattedTitle"), null, entry.thumbnail("thumbnail"), null, id,
                // Guide params target the channel homepage, not its FEsubscriptions filter.
                hasNewContent = entry.bool("isNewContent") == true ||
                    entry.text("presentationStyle") == "GUIDE_ENTRY_PRESENTATION_STYLE_NEW_CONTENT")
        }
        // The guide returns the whole directory in one response; only its absence
        // falls back to the paginated channels browse.
        if (items.isNotEmpty()) return ReadResult(ChannelDirectoryPage(mergeSubscriptionChannels(items), null))
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
