package com.liskovsoft.youtubeapi.videoinfo.V2

data class PlayerDetails(val videoId: String?, val title: String?, val author: String?, val channelId: String?,
    val description: String?, val durationSeconds: Long?, val viewCount: Long?, val thumbnail: String?, val liveContent: Boolean,
    val tracking: PlayerTracking? = null)

fun loadPlayerDetails(videoId: String, context: RequestContext = RequestContext(), http: HttpClient = JvmHttpClient()): ReadResult<PlayerDetails> {
    if (!videoIdAccepted(videoId)) return ReadResult(error = RequestError(RequestFailure.INVALID, "invalid_video_id"))
    val response = requestObject(youtubeQuery(YOUTUBE_API + "player", payload("videoId" to videoId), context), http)
    if (!response.ok) return ReadResult(error = response.error)
    val details = readPlayerDetails(response.value!!)
    return if (details != null) ReadResult(details) else ReadResult(error = RequestError(RequestFailure.PARSE, "missing_video_details"))
}
data class PlayerRange(val start: Long, val end: Long)
internal fun playerRange(node: Json.Obj?): PlayerRange? {
    val start = node?.long("start") ?: return null
    val end = node.long("end") ?: return null
    return if (start >= 0 && end >= start) PlayerRange(start, end) else null
}
internal fun readPlayerDetails(root: Json.Obj): PlayerDetails? {
    val d = root.obj("videoDetails") ?: return null
    return PlayerDetails(d.text("videoId"), d.text("title"), d.text("author"), d.text("channelId"),
        d.text("shortDescription"), d.long("lengthSeconds"), d.long("viewCount"), d.thumbnail("thumbnail"), d.bool("isLiveContent") == true,
        playerTracking(root))
}
