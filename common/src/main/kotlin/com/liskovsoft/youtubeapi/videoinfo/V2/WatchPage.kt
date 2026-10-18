package com.liskovsoft.youtubeapi.videoinfo.V2

data class Chapter(val title: String?, val startMs: Long)
data class StoryboardTile(val url: String, val column: Int, val row: Int, val width: Int, val height: Int)
data class StoryboardLevel(val width: Int, val height: Int, val count: Int, val columns: Int, val rows: Int,
    val intervalMs: Long, val name: String, val signature: String)
data class Storyboard(val template: String, val levels: List<StoryboardLevel>) {
    fun tile(timeMs: Long, durationMs: Long, level: Int = levels.lastIndex): StoryboardTile? {
        val grid = levels.getOrNull(level) ?: return null
        val interval = grid.intervalMs.takeIf { it > 0 } ?: (durationMs / grid.count).takeIf { it > 0 } ?: return null
        val frame = (timeMs.coerceAtLeast(0) / interval).coerceAtMost(grid.count.toLong() - 1).toInt()
        val perSheet = grid.columns * grid.rows
        val name = grid.name.replace("\$M", (frame / perSheet).toString())
        val url = template.replace("\$L", level.toString()).replace("\$N", name).replace("\$M", (frame / perSheet).toString())
        return StoryboardTile(urlWithParameter(url, "sigh", grid.signature), frame % grid.columns, (frame % perSheet) / grid.columns, grid.width, grid.height)
    }
}
fun readStoryboard(spec: String?): Storyboard? {
    val parts = spec?.split('|') ?: return null
    val levels = parts.drop(1).mapNotNull { part ->
        val f = part.split('#'); if (f.size < 8) return@mapNotNull null
        val values = f.take(6).map { it.toLongOrNull() ?: return@mapNotNull null }
        if (values.take(5).any { it <= 0 || it > 100000 } || values[5] < 0 || values[3] * values[4] > Int.MAX_VALUE) return@mapNotNull null
        StoryboardLevel(values[0].toInt(), values[1].toInt(), values[2].toInt(), values[3].toInt(), values[4].toInt(), values[5], f[6], f[7])
    }
    return if (levels.isEmpty()) null else Storyboard(parts.first(), levels)
}
data class WatchPage @JvmOverloads constructor(val videoId: String?, val title: String?, val description: String?, val author: String?, val channelId: String?,
    val chapters: List<Chapter>, val related: List<MediaItem>, val nextKey: String?, val commentsKey: String?, val chatKey: String?, val storyboard: Storyboard?,
    val queue: WatchQueue? = null, val resumeSeconds: Long? = null)
fun watchPage(body: String, videoId: String? = null): WatchPage {
    val root = jsonObject(body)
    val primary = root?.objects("videoPrimaryInfoRenderer")?.firstOrNull()
    val secondary = root?.objects("videoSecondaryInfoRenderer")?.firstOrNull()
    val owner = secondary?.objects("videoOwnerRenderer")?.firstOrNull()
    val chapters = (root?.objects("chapterRenderer").orEmpty().mapNotNull { it.long("timeRangeStartMillis")?.let { ms -> Chapter(it.display("title"), ms) } } +
        root?.objects("macroMarkersListItemRenderer").orEmpty().mapNotNull {
            it.path("onTap", "watchEndpoint")?.long("startTimeSeconds")?.let { sec -> Chapter(it.display("title"), sec * 1000) }
        }).filter { it.startMs >= 0 }.distinctBy { it.startMs }.sortedBy { it.startMs }
    val recommendations = root?.path("contents", "twoColumnWatchNextResults", "secondaryResults", "secondaryResults") ?: root
    val liveChat = root?.objects("liveChatRenderer")?.firstOrNull()
    val queue = root?.let(::readWatchQueue)
    return WatchPage(root?.obj("videoDetails")?.text("videoId") ?: videoId, primary?.display("title") ?: root?.obj("videoDetails")?.text("title"),
        secondary?.display("description") ?: secondary?.obj("attributedDescription")?.text("content"), owner?.display("title"),
        owner?.path("navigationEndpoint", "browseEndpoint")?.text("browseId"), chapters,
        recommendations?.let(::contentItems).orEmpty(), recommendations?.let(::pageKey), commentKey(body), liveChat?.let(::pageKey),
        readStoryboard(root?.objects("playerStoryboardSpecRenderer")?.firstOrNull()?.text("spec")), queue, root?.let { resumeSeconds(it, queue) })
}
@JvmOverloads
fun loadWatch(videoId: String?, context: RequestContext = RequestContext(), continuation: String? = null,
    http: HttpClient = JvmHttpClient(), playlistId: String? = null, index: Int? = null, params: String? = null): ReadResult<WatchPage> {
    if (continuation == null && (videoId == null || !videoIdAccepted(videoId))) return ReadResult(error = RequestError(RequestFailure.INVALID, "invalid_video_id"))
    if (index != null && index < 0) return ReadResult(error = RequestError(RequestFailure.INVALID, "invalid_queue_index"))
    val fields = if (continuation != null) payload("continuation" to continuation) else
        payload("videoId" to videoId, "playlistId" to playlistId, "params" to params) + if (index == null) "" else ",\"index\":$index"
    val reply = requestObject(youtubeQuery(YOUTUBE_API + "next", fields, context), http)
    return if (reply.ok) ReadResult(watchPage(reply.value!!.encode(), videoId)) else ReadResult(error = reply.error)
}
