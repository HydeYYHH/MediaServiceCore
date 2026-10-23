package com.liskovsoft.youtubeapi.videoinfo.V2

// CommonItems / CommonHelper, upstream 7c2451ea, common/models/gen.
internal val contentRenderers = mapOf("videoRenderer" to "video", "gridVideoRenderer" to "video",
    "compactVideoRenderer" to "video", "playlistVideoRenderer" to "video", "reelItemRenderer" to "short",
    "channelRenderer" to "channel", "gridChannelRenderer" to "channel", "compactChannelRenderer" to "channel",
    "playlistRenderer" to "playlist", "gridPlaylistRenderer" to "playlist", "compactPlaylistRenderer" to "playlist",
    "tileRenderer" to "tile", "lockupViewModel" to "lockup", "shortsLockupViewModel" to "short")

internal fun contentItems(root: Json): List<MediaItem> {
    val result = ArrayList<MediaItem>()
    fun visit(node: Json) {
        when (node) {
            is Json.Obj -> for ((name, value) in node.fields) {
                if (name in contentRenderers) (value as? Json.Obj)?.let { contentItem(name, it) }?.let { result.add(it) }
                else if (name !in setOf("menu", "header", "engagementPanels")) visit(value)
            }
            is Json.Arr -> node.items.forEach { visit(it) }
            else -> Unit
        }
    }
    visit(root)
    return result.distinctBy { it.kind to it.id }
}

private fun contentItem(type: String, item: Json.Obj): MediaItem? {
    val command = item.obj("navigationEndpoint") ?: item.obj("onSelectCommand") ?: item.path("onTap", "innertubeCommand")
        ?: item.path("rendererContext", "commandContext", "onTap", "innertubeCommand")
    val watch = command?.obj("watchEndpoint") ?: command?.obj("reelWatchEndpoint")
    val browse = command?.obj("browseEndpoint")
    val playlist = item.text("contentType")?.contains("PLAYLIST") == true
    val id = (if (playlist) item.text("contentId") ?: watch?.text("playlistId") else null)
        ?: item.text("videoId") ?: item.text("channelId") ?: item.text("playlistId")
        ?: watch?.text("videoId") ?: browse?.text("browseId") ?: item.text("contentId") ?: return null
    val kind = when (val expected = contentRenderers.getValue(type)) {
        "tile", "lockup" -> when {
            playlist -> "playlist"
            watch != null -> if (command?.obj("reelWatchEndpoint") != null) "short" else "video"
            id.startsWith("UC") -> "channel"
            browse != null || item.text("contentType")?.contains("PLAYLIST") == true -> "playlist"
            else -> "video"
        }
        else -> expected
    }
    val metadata = item.path("metadata", "lockupMetadataViewModel") ?: item.path("metadata", "tileMetadataRenderer")
    val rows = (metadata?.path("metadata", "contentMetadataViewModel")?.fields?.get("metadataRows") as? Json.Arr)?.items.orEmpty()
    val rowParts = rows.map { row ->
        ((row as? Json.Obj)?.fields?.get("metadataParts") as? Json.Arr)?.items.orEmpty().mapNotNull { it as? Json.Obj }
    }
    val hasAuthor = metadata?.objects("decoratedAvatarViewModel")?.isNotEmpty() == true ||
        rowParts.firstOrNull()?.any { part -> part.objects("browseEndpoint").any { it.text("browseId")?.startsWith("UC") == true } } == true
    val author = rowParts.firstOrNull()?.firstOrNull()?.display("text").takeIf { hasAuthor }
    val stats = if (hasAuthor) rowParts.drop(1).flatten() else rowParts.flatten()
    val views = stats.firstOrNull { part ->
        part.text("accessibilityLabel")?.contains("view", true) == true ||
            part.path("text")?.text("accessibilityLabel")?.contains("view", true) == true ||
            part.display("text")?.contains("view", true) == true ||
            part.objects("leadingIcon").any { it.text("iconType") == "PLAY_ARROW" || it.text("name")?.startsWith("PLAY_ARROW") == true }
    }?.display("text")
    val published = stats.firstOrNull { part ->
        part.display("text")?.let { it.contains("ago", true) || it.contains("Streamed", true) || it.contains("Premiered", true) } == true
    }?.display("text")
    val image = item.path("contentImage", "thumbnailViewModel")
        ?: item.path("contentImage", "collectionThumbnailViewModel", "primaryThumbnail", "thumbnailViewModel")
    val badge = item.objects("thumbnailBadgeViewModel").firstOrNull()
    val progress = item.objects("thumbnailOverlayResumePlaybackRenderer").firstOrNull()?.int("percentDurationWatched")
        ?: item.objects("thumbnailOverlayProgressBarViewModel").firstOrNull()?.int("startPercent")
    return MediaItem(kind, id, item.display("title") ?: item.display("headline") ?: metadata?.display("title")
        ?: item.obj("overlayMetadata")?.display("primaryText"),
        item.display("ownerText") ?: item.display("shortBylineText") ?: item.display("longBylineText")
            ?: item.obj("overlayMetadata")?.display("secondaryText") ?: author,
        item.thumbnail("thumbnail") ?: item.path("header", "tileHeaderRenderer")?.thumbnail("thumbnail") ?: image?.thumbnail("image"),
        item.display("lengthText") ?: badge?.text("text"),
        item.text("channelId") ?: item.objects("browseEndpoint").mapNotNull { it.text("browseId") }.firstOrNull { it.startsWith("UC") },
        item.text("playlistId") ?: watch?.text("playlistId"), browse?.text("params"),
        actionEndpoint(command), pageActions(item), watch?.long("startTimeSeconds"), progress?.coerceIn(0, 100),
        badge?.text("badgeStyle")?.contains("LIVE") == true || item.objects("thumbnailOverlayTimeStatusRenderer").any { it.text("style") == "LIVE" },
        watch?.text("playerParams"),
        item.thumbnail("channelThumbnail")
            ?: item.objects("channelThumbnailWithLinkRenderer").firstOrNull()?.thumbnail("thumbnail")
            ?: item.objects("decoratedAvatarViewModel").firstOrNull()?.path("avatar", "avatarViewModel")?.thumbnail("image"),
        item.display("viewCountText") ?: item.display("shortViewCountText") ?: views,
        item.display("publishedTimeText") ?: published,
        item.display("videoCountText") ?: item.display("videoCount")
            ?: item.objects("thumbnailOverlayBottomPanelRenderer").firstOrNull()?.display("text")
            ?: badge?.text("text")?.takeIf { kind == "playlist" })
}

internal fun pageGroups(root: Json): List<MediaGroup> {
    val result = ArrayList<MediaGroup>()
    val names = setOf("shelfRenderer", "richShelfRenderer", "itemSectionRenderer")
    fun visit(node: Json) {
        when (node) {
            is Json.Obj -> for ((name, value) in node.fields) {
                if (name in names && value is Json.Obj) {
                    val nested = names.any { value.objects(it).isNotEmpty() }
                    if (nested) visit(value) else {
                        val items = contentItems(value)
                        if (items.isNotEmpty()) result.add(MediaGroup(value.display("title"), items, pageKey(value), pageActions(value)))
                    }
                } else if (name !in contentRenderers && name !in setOf("menu", "header")) visit(value)
            }
            is Json.Arr -> node.items.forEach { visit(it) }
            else -> Unit
        }
    }
    visit(root)
    return result
}
