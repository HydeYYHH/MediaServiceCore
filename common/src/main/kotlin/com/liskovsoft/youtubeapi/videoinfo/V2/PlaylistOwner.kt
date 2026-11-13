package com.liskovsoft.youtubeapi.videoinfo.V2

/** Playlist ownership comes from the page's owner roles, never from one of its videos. */
internal fun playlistOwner(root: Json.Obj): AuthorInfo {
    val unavailable = AuthorInfo(null, null, null, "playlist_owner_unavailable")
    return runCatching {
        val owners = ArrayList<AuthorInfo>()
        fun owner(endpoint: Json.Obj?, text: Json.Obj?, image: Json.Obj? = null) {
            val ids = ArrayList<String>()
            endpoint?.let { ids += playlistOwnerId(it) }
            text?.obj("navigationEndpoint")?.let { ids += playlistOwnerId(it) }
            text?.arr("runs").orEmpty().forEach { run ->
                (run as? Json.Obj)?.obj("navigationEndpoint")?.let { ids += playlistOwnerId(it) }
            }
            var name = text?.displayValue()
            text?.arr("commandRuns").orEmpty().forEach { run ->
                val command = run as? Json.Obj ?: error("owner_text")
                val tap = command.obj("onTap") ?: return@forEach
                ids += playlistOwnerId(tap)
                val content = text?.text("content") ?: error("owner_text")
                val start = command.int("startIndex") ?: 0
                val length = command.int("length") ?: error("owner_text")
                require(start >= 0 && length > 0 && start.toLong() + length <= content.length)
                name = content.substring(start, start + length)
            }
            if (ids.isNotEmpty()) {
                require(ids.distinct().size == 1)
                val avatar = readArtwork(image)
                owners += AuthorInfo(ids.first(), name, avatar, if (avatar == null) "artwork_unavailable" else null)
            }
        }

        root.path("header", "playlistHeaderRenderer")?.let { header ->
            owner(header.obj("ownerEndpoint"), header.obj("ownerText"))
        }
        root.path("sidebar", "playlistSidebarRenderer")?.arr("items").orEmpty().forEach { item ->
            (item as? Json.Obj)?.path("playlistSidebarSecondaryInfoRenderer", "videoOwner", "videoOwnerRenderer")?.let {
                owner(it.obj("navigationEndpoint"), it.obj("title"), it.obj("thumbnail"))
            }
        }
        // The stack's own onTap can open the collaborator panel. Only the attributed
        // owner text supplies a channel identity, and a single avatar can belong to it.
        root.path("header", "pageHeaderRenderer")?.objects("avatarStackViewModel")?.singleOrNull()?.let { stack ->
            val image = (stack.arr("avatars").singleOrNull() as? Json.Obj)?.path("avatarViewModel", "image")
            owner(null, stack.obj("text"), image)
        }
        root.path("contents", "tvBrowseRenderer", "content", "tvSurfaceContentRenderer", "content",
            "twoColumnRenderer", "leftColumn", "entityMetadataRenderer")?.arr("bylines")?.firstOrNull()?.let { byline ->
            (byline as? Json.Obj)?.arr("lineItems").orEmpty().forEach { item ->
                (item as? Json.Obj)?.obj("lineItemRenderer")?.let { owner(null, it.obj("text")) }
            }
        }
        val id = owners.mapNotNull { it.channelId }.distinct().singleOrNull() ?: return@runCatching unavailable
        val avatar = owners.firstNotNullOfOrNull { it.avatar }
        AuthorInfo(id, owners.firstNotNullOfOrNull { it.name }, avatar, if (avatar == null) "artwork_unavailable" else null)
    }.getOrDefault(unavailable)
}

private fun playlistOwnerId(endpoint: Json.Obj): String {
    val inner = endpoint.obj("innertubeCommand")
    val command = inner ?: endpoint
    fun commands(node: Json.Obj) = node.fields.keys.filter { it.endsWith("Endpoint") || it.endsWith("Command") }
    require(commands(command) == listOf("browseEndpoint"))
    if (inner != null) require(commands(endpoint) == listOf("innertubeCommand"))
    for (node in listOfNotNull(endpoint, inner)) {
        val metadata = node.path("commandMetadata", "webCommandMetadata") ?: continue
        metadata.text("webPageType")?.let { require(it == "WEB_PAGE_TYPE_CHANNEL") }
        metadata.text("apiUrl")?.let { require(it == "/youtubei/v1/browse") }
    }
    return requireNotNull(command.obj("browseEndpoint")?.text("browseId"))
        .also { require(it.matches(Regex("UC[A-Za-z0-9_-]{22}"))) }
}

private fun Json.Obj.displayValue(): String? = text("content") ?: display("text") ?: text("simpleText")
    ?: arr("runs").joinToString("") { (it as? Json.Obj)?.text("text").orEmpty() }.takeIf(String::isNotBlank)
