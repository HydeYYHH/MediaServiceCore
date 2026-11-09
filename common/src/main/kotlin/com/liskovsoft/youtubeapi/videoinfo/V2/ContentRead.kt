package com.liskovsoft.youtubeapi.videoinfo.V2

// CommonItems / CommonHelper, upstream 7c2451ea, common/models/gen.
internal val contentRenderers = mapOf("videoRenderer" to "video", "gridVideoRenderer" to "video",
    "compactVideoRenderer" to "video", "pivotVideoRenderer" to "video", "playlistVideoRenderer" to "video", "reelItemRenderer" to "short",
    "channelRenderer" to "channel", "gridChannelRenderer" to "channel", "compactChannelRenderer" to "channel",
    "playlistRenderer" to "playlist", "gridPlaylistRenderer" to "playlist", "compactPlaylistRenderer" to "playlist",
    "tileRenderer" to "tile", "lockupViewModel" to "lockup", "shortsLockupViewModel" to "short")

// Modern cards expose localized display strings instead of viewCountText/publishedTimeText.
// Compile these once per process, and keep the original server text when identifying a field.
private val contentViewWords = Regex("(?iu)(\\bviews?\\b|\\bwatching\\b|次观看|次觀看|观看次数|觀看次數|次播放|回視聴|回再生|조회수|visualizaciones|vistas|visualizações|vues|aufrufe|просмотр|مشاهدة)")
private val contentAgeWords = Regex("(?iu)(\\bago\\b|\\bstreamed\\b|\\bpremiered\\b|\\bpublished\\b|\\byesterday\\b|\\btoday\\b|(?:秒|分钟|分鐘|小时|小時|天|周|週|个月|個月|月|年|時間|日|週間|か月|분|시간|일|주|개월|년)\\s*(?:前|전)|\\bhace\\b|\\bvor\\b|\\bil y a\\b|\\bhá\\b|\\batrás\\b|\\bназад\\b|\\bfa\\b|قبل)")
private val contentKindLabel = Regex("(?i)^(song|album|episode)$")
private val contentDuration = Regex("[0-9]+(?::[0-5][0-9]){1,2}")

private fun String.isContentViewCount() =
    equals("No views", ignoreCase = true) || any(Char::isDigit) && contentViewWords.containsMatchIn(this)

private fun String.isContentAge() = contentAgeWords.containsMatchIn(this)
private fun Json.Obj.contentText(name: String) = display(name)?.takeIf(String::isNotBlank)

private fun Json.Obj.isContentViews(): Boolean =
    listOfNotNull(contentText("text"), text("accessibilityLabel"), obj("text")?.text("accessibilityLabel"))
        .any { it.isContentViewCount() } || objects("leadingIcon").any {
        it.text("iconType") == "PLAY_ARROW" || it.text("name")?.startsWith("PLAY_ARROW") == true
    }

private fun Json.Obj.isContentPublished(): Boolean =
    listOfNotNull(contentText("text"), text("accessibilityLabel"), obj("text")?.text("accessibilityLabel"))
        .any { it.isContentAge() }

internal fun contentItems(root: Json): List<MediaItem> {
    val result = ArrayList<MediaItem>()
    fun visit(node: Json) {
        if (node is Json.Obj && node.isAdvertisement()) return
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
    val unique = linkedMapOf<Pair<String, String>, MediaItem>()
    result.forEach { item ->
        val key = item.kind to item.id
        val first = unique[key]
        // A later card may carry display fields absent from the first server shelf.
        // Keep ordering and all commands/capabilities from the original card.
        unique[key] = if (first == null) item else first.copy(
            title = first.title?.takeIf(String::isNotBlank) ?: item.title,
            author = first.author?.takeIf(String::isNotBlank) ?: item.author,
            thumbnail = first.thumbnail?.takeIf(String::isNotBlank) ?: item.thumbnail,
            duration = first.duration?.takeIf(String::isNotBlank) ?: item.duration,
            channelId = first.channelId?.takeIf(String::isNotBlank) ?: item.channelId,
            authorThumbnail = first.authorThumbnail?.takeIf(String::isNotBlank) ?: item.authorThumbnail,
            viewCount = first.viewCount?.takeIf(String::isNotBlank) ?: item.viewCount,
            published = first.published?.takeIf(String::isNotBlank) ?: item.published,
        )
    }
    return unique.values.toList()
}

private fun contentItem(type: String, item: Json.Obj): MediaItem? {
    if (item.isAdvertisement()) return null
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
        ?: item.path("header", "tileHeaderRenderer")?.objects("tileMetadataRenderer")?.firstOrNull()
    val carousel = item.path("onFocusCommand", "commandExecutorCommand")?.arr("commands").orEmpty()
        .mapNotNull { (it as? Json.Obj)?.path("updateCarouselHeaderCommand", "spotlight", "entityMetadataRenderer") }
        .firstOrNull()
    val bylines = carousel?.arr("bylines").orEmpty().map { line ->
        (line as? Json.Obj)?.obj("lineRenderer")?.arr("items").orEmpty()
            .mapNotNull { (it as? Json.Obj)?.obj("lineItemRenderer") }
    }
    val carouselAuthor = bylines.firstOrNull()?.firstNotNullOfOrNull { it.display("text") }
    val carouselStats = bylines.drop(1).flatten()
    val tileLines = metadata?.arr("lines").orEmpty().map { line ->
        (line as? Json.Obj)?.obj("lineRenderer")?.objects("lineItemRenderer").orEmpty()
            .mapNotNull { it.display("text") }.filter { it.isNotBlank() && it != "•" }
    }
    val rows = (metadata?.path("metadata", "contentMetadataViewModel")?.fields?.get("metadataRows") as? Json.Arr)?.items.orEmpty()
    // Entity-based lockups publish the same metadataRows under entityMetadataRenderer.
    val entityRows = ((item.fields["entityMetadataRenderer"] as? Json.Obj)?.fields?.get("metadataRows") as? Json.Arr)?.items.orEmpty()
    val rowParts = (rows + entityRows).map { row ->
        ((row as? Json.Obj)?.fields?.get("metadataParts") as? Json.Arr)?.items.orEmpty().mapNotNull { it as? Json.Obj }
    }
    // TV music/video lockups also carry an unlinked author row, without an avatar.
    // Keep stats-only lockups from turning a view count or publication age into an author.
    val firstRow = rowParts.firstOrNull().orEmpty()
    val firstText = firstRow.singleOrNull()?.display("text")
    val plainAuthor = type == "lockupViewModel" && kind == "video" && !firstText.isNullOrBlank() &&
        !firstText.isContentViewCount() && !firstText.isContentAge() && !contentKindLabel.matches(firstText) &&
        firstRow.single().objects("leadingIcon").isEmpty()
    val linkedAuthor = firstRow.firstOrNull { part ->
        part.objects("browseEndpoint").any { it.text("browseId")?.startsWith("UC") == true }
    }
    val authorPart = linkedAuthor ?: firstRow.firstOrNull()?.takeIf {
        (metadata?.objects("avatarViewModel")?.isNotEmpty() == true || plainAuthor) &&
            !it.isContentViews() && !it.isContentPublished()
    }
    val author = authorPart?.contentText("text")
    // Author and stats can share a row; dropping the entire row silently loses fields.
    val stats = rowParts.flatten().filterNot { it === authorPart }
    val views = stats.firstOrNull { it.isContentViews() }?.contentText("text")
    val published = stats.firstOrNull { it.isContentPublished() }?.contentText("text")
    val image = item.obj("thumbnail")?.obj("thumbnailViewModel")
        ?: item.path("contentImage", "thumbnailViewModel")
        ?: item.path("contentImage", "collectionThumbnailViewModel", "primaryThumbnail", "thumbnailViewModel")
    val badges = image?.objects("thumbnailBadgeViewModel").orEmpty().ifEmpty { item.objects("thumbnailBadgeViewModel") }
    val timeOverlays = item.objects("thumbnailOverlayTimeStatusRenderer")
    val duration = badges.firstNotNullOfOrNull { it.text("text")?.takeIf(contentDuration::matches) }
        ?: timeOverlays.firstNotNullOfOrNull { overlay ->
            overlay.contentText("text")?.takeIf { contentDuration.matches(it) || overlay.text("style") == "LIVE" }
        }
    val secondaryText = item.obj("overlayMetadata")?.contentText("secondaryText")
    val progress = item.objects("thumbnailOverlayResumePlaybackRenderer").firstOrNull()?.int("percentDurationWatched")
        ?: item.objects("thumbnailOverlayProgressBarViewModel").firstOrNull()?.int("startPercent")
    return MediaItem(kind, id, item.contentText("title") ?: item.contentText("headline") ?: metadata?.contentText("title")
        ?: item.obj("overlayMetadata")?.contentText("primaryText") ?: carousel?.contentText("title"),
        item.contentText("ownerText") ?: item.contentText("shortBylineText") ?: item.contentText("longBylineText")
            ?: secondaryText?.takeUnless { it.isContentViewCount() || it.isContentAge() } ?: author
            ?: tileLines.firstOrNull()?.firstOrNull()?.takeUnless { it.isContentAge() || it.isContentViewCount() } ?: carouselAuthor,
        item.thumbnail("thumbnail") ?: item.path("header", "tileHeaderRenderer")?.thumbnail("thumbnail") ?: image?.thumbnail("image")
            ?: if (kind == "short") "https://i.ytimg.com/vi/$id/hqdefault.jpg" else null,
        item.contentText("lengthText") ?: duration,
        item.text("channelId") ?: item.objects("browseEndpoint").mapNotNull { it.text("browseId") }.firstOrNull { it.startsWith("UC") },
        item.text("playlistId") ?: watch?.text("playlistId"), browse?.text("params"),
        actionEndpoint(command), pageActions(item), watch?.long("startTimeSeconds"), progress?.coerceIn(0, 100),
        badges.any { it.text("badgeStyle")?.contains("LIVE") == true } || timeOverlays.any { it.text("style") == "LIVE" },
        watch?.text("playerParams"),
        item.thumbnail("channelThumbnail")
            ?: item.objects("channelThumbnailWithLinkRenderer").firstNotNullOfOrNull { it.thumbnail("thumbnail") }
            ?: metadata?.objects("avatarViewModel")?.firstNotNullOfOrNull { it.thumbnail("image") }
            ?: item.objects("decoratedAvatarViewModel").firstNotNullOfOrNull { it.path("avatar", "avatarViewModel")?.thumbnail("image") }
            ?: bylines.firstOrNull()?.firstNotNullOfOrNull { it.thumbnail("thumbnail") },
        item.contentText("viewCountText") ?: item.contentText("shortViewCountText") ?: views
            ?: secondaryText?.takeIf { it.isContentViewCount() } ?: tileLines.flatten().firstOrNull { it.isContentViewCount() }
            ?: carouselStats.firstNotNullOfOrNull { it.contentText("text")?.takeIf { text -> text.isContentViewCount() } },
        item.contentText("publishedTimeText") ?: published ?: secondaryText?.takeIf { it.isContentAge() }
            ?: tileLines.flatten().firstOrNull { it.isContentAge() }
            ?: carouselStats.firstNotNullOfOrNull { it.contentText("text")?.takeIf { text -> text.isContentAge() } },
        item.display("videoCountText") ?: item.display("videoCount")
            ?: item.objects("thumbnailOverlayBottomPanelRenderer").firstOrNull()?.display("text")
            ?: badges.firstNotNullOfOrNull { it.text("text") }?.takeIf { kind == "playlist" },
        if (kind in setOf("video", "short")) item.path("onLongPressCommand", "showEngagementPanelEndpoint")?.let { panel ->
            val tag = panel.path("identifier")?.text("tag")
            val params = panel.path("globalConfiguration")?.text("params")
            if (tag.isNullOrBlank() || params.isNullOrBlank()) null else MenuEndpoint(tag, params)
        } else null)
}

internal fun pageGroups(root: Json): List<MediaGroup> {
    val result = ArrayList<MediaGroup>()
    val names = setOf("shelfRenderer", "richShelfRenderer", "itemSectionRenderer")
    fun visit(node: Json) {
        if (node is Json.Obj && node.isAdvertisement()) return
        when (node) {
            is Json.Obj -> for ((name, value) in node.fields) {
                if (name in names && value is Json.Obj) {
                    val nested = names.any { value.objects(it).isNotEmpty() }
                    if (nested) visit(value) else {
                        val items = contentItems(value)
                        // TV shelves carry their title under headerRenderer.shelfHeaderRenderer.
                        val shelfTitle = value.display("title")
                            ?: value.path("headerRenderer", "shelfHeaderRenderer")?.let { shelf ->
                                shelf.display("title") ?: shelf.path("avatarLockup", "avatarLockupRenderer")?.display("title")
                            }
                        if (items.isNotEmpty()) result.add(MediaGroup(shelfTitle, items, pageKey(value), pageActions(value)))
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
