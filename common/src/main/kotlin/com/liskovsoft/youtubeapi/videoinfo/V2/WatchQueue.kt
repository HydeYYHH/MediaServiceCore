package com.liskovsoft.youtubeapi.videoinfo.V2

data class QueueVideo(val videoId: String, val title: String?, val index: Int, val selected: Boolean,
    val thumbnail: String?, val endpoint: ActionEndpoint?, val startSeconds: Long? = null)
data class WatchQueue(val playlistId: String?, val title: String?, val index: Int?, val videos: List<QueueVideo>,
    val previous: ActionEndpoint?, val next: ActionEndpoint?, val nextKey: String?, val totalVideos: Long? = null,
    val infinite: Boolean = false)

internal fun readWatchQueue(root: Json.Obj): WatchQueue? {
    val container = root.path("contents", "twoColumnWatchNextResults", "playlist")
        ?: root.path("contents", "singleColumnWatchNextResults", "playlist")
        ?: root.objects("playlistPanelRenderer").firstOrNull() ?: return null
    val metadata = container.obj("playlist") ?: container
    val renderers = container.objects("playlistPanelVideoRenderer") + container.objects("playlistVideoRenderer")
    val videos = renderers.mapIndexedNotNull { position, item ->
        val command = item.obj("navigationEndpoint") ?: item.obj("endpoint")
        val watch = command?.obj("watchEndpoint")
        val id = item.text("videoId") ?: watch?.text("videoId") ?: return@mapIndexedNotNull null
        QueueVideo(id, item.display("title"), watch?.int("index") ?: position, item.bool("selected") == true,
            item.thumbnail("thumbnail"), actionEndpoint(command), watch?.long("startTimeSeconds"))
    }
    val index = videos.firstOrNull { it.selected }?.index ?: metadata.int("currentIndex")?.takeIf { it >= 0 }
    val position = videos.indexOfFirst { it.index == index }
    fun navigation(name: String) = root.objects(name).firstOrNull()?.objects("watchEndpoint")?.firstOrNull()
        ?.let { ActionEndpoint("watchEndpoint", it.encode()) }
    return WatchQueue(metadata.text("playlistId"), metadata.display("title") ?: metadata.display("titleText"), index, videos,
        navigation("previousVideoRenderer") ?: videos.getOrNull(position - 1)?.endpoint,
        navigation("nextVideoRenderer") ?: if (position >= 0) videos.getOrNull(position + 1)?.endpoint else null,
        pageKey(container), metadata.long("totalVideos"), metadata.bool("isInfinite") == true)
}

internal fun resumeSeconds(root: Json.Obj, queue: WatchQueue?): Long? {
    val current = root.path("currentVideoEndpoint", "watchEndpoint")?.long("startTimeSeconds")
    val resume = root.objects("resumePlaybackEndpoint").firstOrNull()
    return (current ?: resume?.long("startTimeSeconds") ?: resume?.long("resumePositionMs")?.div(1000)
        ?: queue?.videos?.firstOrNull { it.index == queue.index }?.startSeconds)?.takeIf { it >= 0 }
}
