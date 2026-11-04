package com.liskovsoft.youtubeapi.videoinfo.V2

// ReelResult / ReelContinuationResult, upstream 7c2451ea, browse/v2/gen/BrowseResult.kt.
fun shortsPage(body: String): BrowsePage {
    val root = jsonObject(body) ?: return BrowsePage(emptyList(), emptyList(), emptyList(), null)
    val header = root.objects("reelPlayerHeaderRenderer").firstOrNull()
    val first = root.path("replacementEndpoint", "reelWatchEndpoint")
    val currentId = root.path("overlay", "reelPlayerOverlayRenderer", "doubleTapLikeButton", "likeButtonRenderer", "target")?.text("videoId")
    val current = currentId?.let { Json.Obj(mapOf("videoId" to Json.Str(it))) }
    val title = root.objects("videoTitleHeaderViewModel").firstOrNull()?.display("videoTitle") ?: header?.display("reelTitleText")
    val channel = root.objects("reelChannelBarViewModel").firstOrNull()
    val description = root.objects("videoDescriptionHeaderRenderer").firstOrNull()
    val endpoints = listOfNotNull(current, first) + root.arr("entries").mapNotNull {
        (it as? Json.Obj)?.takeUnless { entry -> entry.isAdvertisement() }
            ?.path("command", "reelWatchEndpoint")?.takeUnless { endpoint -> endpoint.isAdvertisement() }
    }
    val items = endpoints.mapNotNull { endpoint ->
        if (endpoint.isAdvertisement()) return@mapNotNull null
        val id = endpoint.text("videoId") ?: return@mapNotNull null
        val primary = endpoint === (current ?: first)
        MediaItem("short", id, if (primary) title else null,
            if (primary) channel?.display("channelName") ?: header?.display("channelTitleText") else null,
            endpoint.thumbnail("thumbnail") ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg", null,
            if (primary) (description ?: header)?.path("channelNavigationEndpoint", "browseEndpoint")?.text("browseId") else null,
            params = endpoint.text("params"), endpoint = actionEndpoint(Json.Obj(mapOf("reelWatchEndpoint" to endpoint))),
            playerParams = endpoint.text("playerParams"))
    }.distinctBy { it.id }
    val next = root.text("sequenceContinuation") ?: root.text("continuation")
        ?: root.path("continuationEndpoint", "continuationCommand")?.text("token")
    return BrowsePage(items, emptyList(), emptyList(), next)
}

internal fun readShorts(root: Json.Obj): ReadResult<BrowsePage> {
    if (root.path("replacementEndpoint", "reelWatchEndpoint") == null && root.fields["entries"] !is Json.Arr)
        return ReadResult(error = RequestError(RequestFailure.PARSE, "unknown_shorts_page"))
    return ReadResult(shortsPage(root.encode()))
}
