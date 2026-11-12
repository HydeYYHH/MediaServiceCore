package com.liskovsoft.youtubeapi.videoinfo.V2

/** Display data from fixed channel-owner roles, never a route, command or permission source. */
internal fun ownedChannelAuthor(root: Json.Obj, expectedId: String): AuthorInfo {
    fun unavailable(reason: String) = AuthorInfo(null, null, null, reason)
    if (!Regex("UC[A-Za-z0-9_-]{22}").matches(expectedId) || root.fields.containsKey("error"))
        return unavailable("channel_identity_unavailable")
    val owners = ArrayList<Pair<Json.Obj, String>>()
    for ((parent, renderer, identity) in listOf(
        Triple("metadata", "channelMetadataRenderer", "externalId"),
        Triple("header", "c4TabbedHeaderRenderer", "channelId"))) {
        val value = root.fields[parent] ?: continue
        val container = value as? Json.Obj ?: return unavailable("channel_identity_unavailable")
        val declared = container.fields[renderer] ?: continue
        owners += (declared as? Json.Obj ?: return unavailable("channel_identity_unavailable")) to identity
    }
    val ids = ArrayList<String>()
    for ((owner, field) in owners) if (owner.fields.containsKey(field)) {
        val id = (owner.fields[field] as? Json.Str)?.value
            ?: return unavailable("channel_identity_unavailable")
        if (!Regex("UC[A-Za-z0-9_-]{22}").matches(id)) return unavailable("channel_identity_unavailable")
        ids += id
    }
    if (ids.isEmpty()) return unavailable("channel_identity_unavailable")
    if (ids.any { it != expectedId }) return unavailable("channel_identity_mismatch")
    val name = owners.firstNotNullOfOrNull { (owner, _) ->
        (owner.fields["title"] as? Json.Str)?.value?.takeIf(String::isNotBlank)
    }
    val variants = ArrayList<ArtworkVariant>()
    for ((owner, _) in owners) {
        val declared = owner.fields["avatar"] ?: continue
        val artwork = ownedAvatar(declared)
            ?: return AuthorInfo(expectedId, name, null, "artwork_unavailable")
        variants += artwork.variants
    }
    val artwork = variants.takeIf { it.isNotEmpty() }?.let(::Artwork)
    return AuthorInfo(expectedId, name, artwork, if (artwork == null) "artwork_unavailable" else null)
}

private fun ownedAvatar(value: Json): Artwork? {
    val image = value as? Json.Obj ?: return null
    if (image.fields.keys.any { it !in setOf("thumbnails", "sources") }) return null
    val variants = ArrayList<ArtworkVariant>()
    for (field in listOf("thumbnails", "sources")) {
        val declared = image.fields[field] ?: continue
        val list = declared as? Json.Arr ?: return null
        if (list.items.size > 64 || variants.size + list.items.size > 64) return null
        for (entry in list.items) {
            val source = entry as? Json.Obj ?: return null
            if (source.fields.keys.any { it !in setOf("url", "width", "height") }) return null
            val url = artworkUrl((source.fields["url"] as? Json.Str)?.value) ?: return null
            fun dimension(name: String): Int? {
                val number = source.fields[name] ?: return null
                return (number as? Json.Num)?.text?.toIntOrNull()?.takeIf { it > 0 }
            }
            val width = dimension("width"); val height = dimension("height")
            if (source.fields.containsKey("width") && width == null || source.fields.containsKey("height") && height == null) return null
            variants += ArtworkVariant(url, width, height)
        }
    }
    return variants.takeIf { it.isNotEmpty() }?.let(::Artwork)
}
