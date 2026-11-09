package com.liskovsoft.youtubeapi.videoinfo.V2

// WEB puts channel metadata at the root; TV puts its header inside the surface.
// Do not download the channel's videos, shelves and action panels for an avatar.
private const val AVATAR_FIELDS = "metadata%2Cheader%2Ccontents%2FtvBrowseRenderer%2Fcontent%2FtvSurfaceContentRenderer%2Fheader"

private fun PlayerQuery.withAvatarFields(): PlayerQuery {
    val compact = withCompactResponse()
    return if (compact.url.isEmpty()) compact else compact.copy(url = compact.url + "&fields=" + AVATAR_FIELDS)
}

/**
 * Public author artwork never needs account credentials. YouTube Music's header
 * is small even for channels with a large video catalog. Fall back to WEB channel
 * metadata once when Music has no supported avatar (e.g. an unavailable Music page).
 * HTTP/transport failures retain their classification for the host retry policy.
 */
fun loadChannelAvatar(channelId: String, context: RequestContext = RequestContext(),
    http: HttpClient = JvmHttpClient()): ReadResult<String> {
    val publicContext = context.anonymous()
    if (!videoIdAccepted(channelId)) return ReadResult(error = RequestError(RequestFailure.INVALID, "invalid_channel_id"))
    val first = requestObject(channelAvatarQuery(channelId, publicContext), http)
    if (!first.ok) return ReadResult(error = first.error)
    musicChannelAvatar(first.value!!)?.takeIf(String::isNotBlank)?.let { return ReadResult(it) }

    val fallback = channelQuery(channelId, publicContext).withAvatarFields()
    val second = requestObject(fallback, http)
    if (!second.ok) return ReadResult(error = second.error)
    val avatar = channelPage(second.value!!, channelId).thumbnail?.takeIf(String::isNotBlank)
    return if (avatar != null) ReadResult(avatar) else
        ReadResult(error = RequestError(RequestFailure.PARSE, "channel_avatar_missing"))
}

/** WEB_REMIX is used only for public channel artwork, never for playback or account reads. */
fun channelAvatarQuery(channelId: String, context: RequestContext = RequestContext()): PlayerQuery {
    if (!videoIdAccepted(channelId)) return PlayerQuery("", emptyMap(), "")
    val publicContext = context.anonymous()
    val template = webQuery(YOUTUBE_API + "browse", payload("browseId" to channelId), publicContext.visitor)
    val root = jsonObject(template.body)!!
    val originalContext = root.obj("context")!!
    val client = originalContext.obj("client")!!
    val musicClient = Json.Obj(client.fields + mapOf("clientName" to Json.Str("WEB_REMIX"),
        "clientVersion" to Json.Str("1.20250219.01.00")))
    val query = contextualQuery(template.copy(body = Json.Obj(root.fields + ("context" to
        Json.Obj(originalContext.fields + ("client" to musicClient)))).encode(),
        headers = template.headers + mapOf("Referer" to "https://music.youtube.com/",
            "X-Youtube-Client-Name" to "67", "X-Youtube-Client-Version" to "1.20250219.01.00")), publicContext)
    return query.copy(url = query.url + "?prettyPrint=false&fields=header")
}

internal fun musicChannelAvatar(root: Json.Obj): String? =
    root.path("header", "musicVisualHeaderRenderer", "foregroundThumbnail", "musicThumbnailRenderer")?.thumbnail("thumbnail")
        ?: root.path("header", "musicImmersiveHeaderRenderer", "thumbnail", "musicThumbnailRenderer")?.thumbnail("thumbnail")

/** A TV home card can name its author only in its small, read-only context menu. */
fun loadMenuChannelId(endpoint: MenuEndpoint, context: RequestContext = RequestContext(),
    http: HttpClient = JvmHttpClient()): ReadResult<String> {
    val menu = loadMenu(endpoint, context.anonymous(), http)
    if (!menu.ok) return ReadResult(error = menu.error)
    val channel = menu.value.orEmpty().firstNotNullOfOrNull { action ->
        val root = jsonObject(action.endpoint.payload)
        (if (action.endpoint.name == "browseEndpoint") root?.text("browseId") else null)
            ?.takeIf { it.startsWith("UC") }
            ?: root?.objects("browseEndpoint")?.firstNotNullOfOrNull {
                it.text("browseId")?.takeIf { id -> id.startsWith("UC") }
            }
    }
    return if (channel != null) ReadResult(channel) else
        ReadResult(error = RequestError(RequestFailure.PARSE, "menu_channel_missing"))
}
