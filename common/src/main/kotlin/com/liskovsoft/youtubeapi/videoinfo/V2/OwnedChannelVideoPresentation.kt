package com.liskovsoft.youtubeapi.videoinfo.V2

import java.util.Collections

/** Display provenance for one actual channel request, never a content-author or command claim. */
internal enum class ChannelVideoResponseRole { OPENING, ISSUED_TAB, ISSUED_CONTINUATION }

internal fun ownedChannelVideoItems(root: Json.Obj, channel: String, page: BrowsePage,
    role: ChannelVideoResponseRole): Set<MediaItem> = try {
    require(Regex("UC[A-Za-z0-9_-]{22}").matches(channel) && "error" !in root.fields)
    compatibleVideoOwner(root, channel)
    val primary = videoPrimary(root, channel, role)
    require(primary.items.size <= 2000 && page.items.size <= 2000 && page.groups.size <= 128)
    var groupItemCount = 0
    for (group in page.groups) {
        require(group.items.size <= 2000 - groupItemCount)
        groupItemCount += group.items.size
    }
    val cards = LinkedHashMap<String, Pair<MediaItem, Boolean>>()
    for (value in primary.items) {
        val wrapper = value as? Json.Obj ?: error("slot")
        videoBrowseRole(wrapper)
        if ("continuationItemRenderer" in wrapper.fields) {
            require(wrapper.fields.keys.all { it in setOf("continuationItemRenderer", "trackingParams", "clickTrackingParams") })
            require(wrapper.fields["continuationItemRenderer"] is Json.Obj)
            continue
        }
        val slot = if ("richItemRenderer" in wrapper.fields) {
            require(wrapper.fields.keys.all { it in setOf("richItemRenderer", "trackingParams", "clickTrackingParams") })
            val rich = wrapper.obj("richItemRenderer") ?: error("rich")
            videoBrowseRole(rich)
            require(rich.fields.keys.all { it in setOf("content", "trackingParams", "clickTrackingParams", "commandMetadata") })
            rich.obj("content") ?: error("content")
        } else wrapper
        val names = listOf("videoRenderer", "gridVideoRenderer", "lockupViewModel").filter { it in slot.fields }
        require(names.size == 1 && slot.fields.keys.all { it in names || it in setOf("trackingParams", "clickTrackingParams", "commandMetadata") })
        videoBrowseRole(slot)
        val renderer = slot.obj(names.single()) ?: error("renderer")
        val id = if (names.single() == "lockupViewModel") {
            require(renderer.text("contentType") == "LOCKUP_CONTENT_TYPE_VIDEO")
            renderer.text("contentId")
        } else renderer.text("videoId")
        require(id != null && Regex("[A-Za-z0-9_-]{11}").matches(id))
        val decoded = contentItems(Json.Obj(mapOf(names.single() to renderer))).singleOrNull() ?: error("card")
        require(decoded.kind == "video" && decoded.id == id)
        require(id !in cards)
        // Explicit author/collaboration evidence is never replaced by a collection label.
        cards[id] = decoded to (singleVideoAuthor(renderer) && (decoded.channelId == null || decoded.channelId == channel))
    }
    val publicCards = page.items.groupBy { it.kind to it.id }
    val groupedIds = page.groups.flatMap { it.items }.filter { it.kind == "video" }.mapTo(HashSet()) { it.id }
    val eligible = cards.values.filter { (card, singleAuthor) ->
        singleAuthor && publicCards["video" to card.id]?.singleOrNull() == card && card.id !in groupedIds
    }.map { it.first }.toSet()
    Collections.unmodifiableSet(eligible)
} catch (_: Exception) { emptySet() }

private fun compatibleVideoOwner(root: Json.Obj, channel: String) {
    for ((parent, renderer, field) in listOf(Triple("metadata", "channelMetadataRenderer", "externalId"),
        Triple("header", "c4TabbedHeaderRenderer", "channelId"))) {
        if (parent !in root.fields) continue
        val container = root.obj(parent) ?: error("owner")
        if (renderer !in container.fields) continue
        val value = container.obj(renderer) ?: error("owner")
        if (field in value.fields) require(value.text(field) == channel)
    }
}

private fun videoPrimary(root: Json.Obj, channel: String, role: ChannelVideoResponseRole): Json.Arr {
    videoBrowseRole(root)
    val envelopes = listOf("contents", "richGridRenderer", "sectionListRenderer", "continuationContents",
        "onResponseReceivedActions", "onResponseReceivedEndpoints", "onResponseReceivedCommands").filter { it in root.fields }
    require(envelopes.size == 1)
    if (role == ChannelVideoResponseRole.ISSUED_CONTINUATION) {
        if (envelopes.single() == "continuationContents") {
            val parent = root.obj("continuationContents") ?: error("continuation")
            require(parent.fields.size == 1)
            val name = parent.fields.keys.single()
            require(name in setOf("richGridContinuation", "gridContinuation"))
            val grid = parent.obj(name) ?: error("grid")
            videoBrowseRole(grid)
            return videoList(grid, if (name == "gridContinuation") "items" else "contents")
        }
        require(envelopes.single() in setOf("onResponseReceivedActions", "onResponseReceivedEndpoints", "onResponseReceivedCommands"))
        val actions = root.fields[envelopes.single()] as? Json.Arr ?: error("actions")
        require(actions.items.size == 1)
        val action = actions.items.single() as? Json.Obj ?: error("action")
        videoBrowseRole(action)
        val names = listOf("appendContinuationItemsAction", "reloadContinuationItemsCommand").filter { it in action.fields }
        require(names.size == 1 && action.fields.keys.all { it in names || it in setOf("trackingParams", "clickTrackingParams", "commandMetadata") })
        val payload = action.obj(names.single()) ?: error("payload")
        videoBrowseRole(payload)
        require(payload.fields.keys.all { it in setOf("continuationItems", "targetId", "slot", "trackingParams", "clickTrackingParams", "commandMetadata") })
        if ("slot" in payload.fields) require(payload.text("slot") == "RELOAD_CONTINUATION_SLOT_BODY")
        if ("targetId" in payload.fields) {
            val target = payload.text("targetId") ?: error("target")
            require(target.isNotBlank() && target.length <= 512 && target !in setOf("comments-section", "engagement-panel-comments-section", "shorts-engagement-panel-comments-section") && !target.startsWith("comment-replies-item-"))
        }
        return payload.fields["continuationItems"] as? Json.Arr ?: error("items")
    }
    require(envelopes.single() in setOf("contents", "richGridRenderer"))
    val content = if (envelopes.single() == "contents") root.obj("contents") ?: error("contents") else root
    videoBrowseRole(content)
    val layouts = listOf("twoColumnBrowseResultsRenderer", "singleColumnBrowseResultsRenderer").filter { it in content.fields }
    if (layouts.isNotEmpty()) {
        require(layouts.size == 1 && content.fields.keys.none { it in setOf("richGridRenderer", "sectionListRenderer", "itemSectionRenderer") })
        val tabs = ownedChannelTabs(root) ?: error("tabs")
        val selected = tabs.indices.filter { tabs[it].selected }
        require(selected.size == 1 && tabs[selected.single()].kind == ChannelTabKind.VIDEOS)
        require(tabs[selected.single()].browseId.let { it == null || it == channel })
        val layout = content.obj(layouts.single()) ?: error("layout")
        videoBrowseRole(layout)
        val rawTabs = layout.fields["tabs"] as? Json.Arr ?: error("tabs")
        require(rawTabs.items.size == tabs.size && rawTabs.items.size <= 128)
        val wrapper = rawTabs.items[selected.single()] as? Json.Obj ?: error("tab")
        videoBrowseRole(wrapper)
        val names = listOf("tabRenderer", "expandableTabRenderer").filter { it in wrapper.fields }
        require(names.size == 1)
        val tab = wrapper.obj(names.single()) ?: error("tab")
        videoBrowseRole(tab)
        return videoGrid(tab.obj("content") ?: error("selected"))
    }
    require(role != ChannelVideoResponseRole.OPENING)
    return videoGrid(content)
}

private fun videoGrid(container: Json.Obj): Json.Arr {
    videoBrowseRole(container)
    require(container.fields.keys.none { it in setOf("sectionListRenderer", "itemSectionRenderer", "contents", "continuationContents",
        "onResponseReceivedActions", "onResponseReceivedEndpoints", "onResponseReceivedCommands") })
    val grid = container.obj("richGridRenderer") ?: error("grid")
    videoBrowseRole(grid)
    return videoList(grid, "contents")
}

private fun videoList(grid: Json.Obj, field: String): Json.Arr {
    // Header/chips are presentation, never candidates for the primary card set.
    require(grid.fields.keys.none { it in setOf("items", "contents") && it != field })
    return grid.fields[field] as? Json.Arr ?: error("list")
}

private fun videoBrowseRole(node: Json.Obj) {
    for (field in listOf("trackingParams", "clickTrackingParams")) if (field in node.fields) require(node.fields[field] is Json.Str)
    if ("commandMetadata" !in node.fields) return
    val metadata = node.obj("commandMetadata") ?: error("metadata")
    if ("webCommandMetadata" !in metadata.fields) return
    val web = metadata.obj("webCommandMetadata") ?: error("web")
    if ("apiUrl" in web.fields) require(web.text("apiUrl") == "/youtubei/v1/browse")
}

private fun singleVideoAuthor(card: Json.Obj): Boolean {
    for (field in listOf("collaboration", "collaborators")) if (field in card.fields) return false
    for (field in listOf("isCollaboration", "isCollaborative")) if (field in card.fields && card.bool(field) != false) return false
    var remaining = 128
    fun avatar(node: Json.Obj, depth: Int = 0): Boolean {
        if (--remaining < 0 || depth > 8) return false
        if ("avatarStackViewModel" in node.fields) {
            val stack = node.obj("avatarStackViewModel") ?: return false
            val avatars = stack.fields["avatars"] as? Json.Arr ?: return false
            if (avatars.items.size != 1 || avatars.items.single() !is Json.Obj) return false
            if (!avatar(avatars.items.single() as Json.Obj, depth + 1)) return false
        }
        for (field in listOf("avatar", "image", "decoratedAvatarViewModel", "avatarViewModel")) if (field in node.fields) {
            val child = node.obj(field) ?: return false
            if (!avatar(child, depth + 1)) return false
        }
        return true
    }
    if ("channelId" in card.fields && card.text("channelId")?.let { Regex("UC[A-Za-z0-9_-]{22}").matches(it) } != true) return false
    if (!avatar(card)) return false
    if ("metadata" in card.fields && card.fields["metadata"] !is Json.Obj) return false
    val metadataRoot = card.obj("metadata")
    if (metadataRoot != null && "lockupMetadataViewModel" in metadataRoot.fields && metadataRoot.fields["lockupMetadataViewModel"] !is Json.Obj) return false
    val metadata = card.path("metadata", "lockupMetadataViewModel")
    for (field in listOf("avatar", "image")) if (metadata != null && field in metadata.fields) {
        if (!avatar(metadata.obj(field) ?: return false)) return false
    }
    return true
}
