package com.liskovsoft.youtubeapi.videoinfo.V2

// ReelResult / ReelContinuationResult, upstream 7c2451ea, browse/v2/gen/BrowseResult.kt.
fun shortsPage(body: String): BrowsePage {
    val root = jsonObject(body) ?: return BrowsePage(emptyList(), emptyList(), emptyList(), null)
    val header = root.objects("reelPlayerHeaderRenderer").firstOrNull()
    val first = root.path("replacementEndpoint", "reelWatchEndpoint")
    val endpoints = listOfNotNull(first) + root.arr("entries").mapNotNull {
        (it as? Json.Obj)?.path("command", "reelWatchEndpoint")
    }
    val items = endpoints.mapNotNull { endpoint ->
        val id = endpoint.text("videoId") ?: return@mapNotNull null
        MediaItem("short", id, if (endpoint === first) header?.display("reelTitleText") else null,
            if (endpoint === first) header?.display("channelTitleText") else null,
            endpoint.thumbnail("thumbnail"), null,
            if (endpoint === first) header?.path("channelNavigationEndpoint", "browseEndpoint")?.text("browseId") else null,
            params = endpoint.text("params"))
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
