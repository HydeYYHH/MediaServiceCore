package com.liskovsoft.youtubeapi.videoinfo.V2

import java.net.URI
import java.net.URLDecoder

/** The notification's declared content destination; no request or interaction credentials. */
sealed interface NoticeDestination {
    data class Video @JvmOverloads constructor(val videoId: String, val startSeconds: Long? = null) : NoticeDestination
    data class Short(val videoId: String) : NoticeDestination
    data class Channel(val channelId: String) : NoticeDestination
    data class Playlist(val playlistId: String) : NoticeDestination
    data class Post(val postId: String, val channelId: String?) : NoticeDestination
}

/** Navigation is read only from the owning endpoint, never from notification text or artwork. */
internal fun noticeDestination(endpoint: Json.Obj?): NoticeDestination? {
    endpoint ?: return null
    val wrapper = endpoint.obj("innertubeCommand")
    val command = wrapper ?: endpoint
    val kinds = setOf("watchEndpoint", "reelWatchEndpoint", "watchPlaylistEndpoint", "browseEndpoint", "getCommentsFromInboxCommand")
    val commands = command.fields.keys.filter { it.endsWith("Endpoint") || it.endsWith("Command") }
    val kind = commands.singleOrNull()?.takeIf { it in kinds } ?: return null
    if (wrapper != null && endpoint.fields.keys.any { it != "innertubeCommand" && (it.endsWith("Endpoint") || it.endsWith("Command")) }) return null
    val metadata = listOfNotNull(endpoint.path("commandMetadata", "webCommandMetadata"),
        wrapper?.path("commandMetadata", "webCommandMetadata"))
    val types = metadata.mapNotNull { it.text("webPageType") }.distinct()
    val urls = metadata.mapNotNull { it.text("url") }.distinct()
    if (types.size > 1 || urls.size > 1) return null
    val type = types.singleOrNull()
    val uri = urls.singleOrNull()?.let { noticeNavigationUri(it) ?: return null }
    val target = command.obj(kind) ?: return null
    fun videoId() = target.text("videoId")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
    fun compatible(vararg allowed: String) = type == null || type in allowed
    return when (kind) {
        "watchEndpoint" -> if (compatible("WEB_PAGE_TYPE_WATCH")) videoId()?.let {
            if (uri != null && (uri.rawPath != "/watch" || noticeQueryValue(uri, "v") != it)) return null
            val start = if (target.fields.containsKey("startTimeSeconds")) target.long("startTimeSeconds")?.takeIf { it >= 0 } ?: return null else null
            NoticeDestination.Video(it, start)
        } else null
        "getCommentsFromInboxCommand" -> if (compatible("WEB_PAGE_TYPE_WATCH")) videoId()?.let {
            val declared = metadata.filter { it.fields.containsKey("apiUrl") }
            if (declared.isEmpty() || declared.any { it.text("apiUrl") != "/youtubei/v1/notification/get_notification_menu" }) return null
            if (metadata.none { it.bool("sendPost") == true } ||
                metadata.any { it.fields.containsKey("sendPost") && it.bool("sendPost") != true }) return null
            if (target.fields.containsKey("linkedCommentId") && target.text("linkedCommentId").isNullOrBlank()) return null
            if (uri != null && (uri.rawPath != "/watch" || noticeQueryValue(uri, "v") != it)) return null
            NoticeDestination.Video(it)
        } else null
        "reelWatchEndpoint" -> if (compatible("WEB_PAGE_TYPE_SHORTS", "WEB_PAGE_TYPE_WATCH")) videoId()?.let {
            if (uri != null && uri.rawPath != "/shorts/$it") return null
            NoticeDestination.Short(it)
        } else null
        "watchPlaylistEndpoint" -> if (compatible("WEB_PAGE_TYPE_PLAYLIST", "WEB_PAGE_TYPE_WATCH"))
            target.text("playlistId")?.takeIf(::noticePlaylistId)?.let {
                if (uri != null && (uri.rawPath != "/playlist" || noticeQueryValue(uri, "list") != it)) return null
                NoticeDestination.Playlist(it)
            } else null
        "browseEndpoint" -> {
            val browseId = target.text("browseId") ?: return null
            when {
                type == "WEB_PAGE_TYPE_POST_DETAIL" -> {
                    val postId = Regex("/post/([A-Za-z0-9_-]{6,200})/?").matchEntire(uri?.rawPath.orEmpty())?.groupValues?.get(1) ?: return null
                    NoticeDestination.Post(postId, browseId.takeIf(::noticeChannelId))
                }
                compatible("WEB_PAGE_TYPE_CHANNEL") && noticeChannelId(browseId) -> {
                    // A post or unrelated route must not be silently reduced to its channel.
                    if (uri != null && !(uri.rawPath == "/channel/$browseId" || uri.rawPath.startsWith("/@") ||
                        uri.rawPath.startsWith("/user/") || uri.rawPath.startsWith("/c/"))) return null
                    NoticeDestination.Channel(browseId)
                }
                compatible("WEB_PAGE_TYPE_PLAYLIST") && browseId.startsWith("VL") && noticePlaylistId(browseId.removePrefix("VL")) -> {
                    val id = browseId.removePrefix("VL")
                    if (uri != null && (uri.rawPath != "/playlist" || noticeQueryValue(uri, "list") != id)) return null
                    NoticeDestination.Playlist(id)
                }
                else -> null
            }
        }
        else -> null
    }
}

private fun noticeChannelId(value: String) = value.matches(Regex("UC[A-Za-z0-9_-]{22}"))
private fun noticePlaylistId(value: String) = value.matches(Regex("[A-Za-z0-9_-]{2,200}"))
private fun noticeNavigationUri(value: String): URI? = runCatching {
    val uri = URI(if (value.startsWith("/") && !value.startsWith("//")) "https://www.youtube.com$value" else value)
    uri.takeIf { it.scheme == "https" && it.host in setOf("www.youtube.com", "youtube.com") &&
        it.port in setOf(-1, 443) && it.rawUserInfo == null && it.rawFragment == null }
}.getOrNull()

private fun noticeQueryValue(uri: URI, name: String): String? = runCatching {
    uri.rawQuery.orEmpty().split('&').map { it.substringBefore('=') to it.substringAfter('=', "") }
        .filter { it.first == name }.singleOrNull()?.second?.let { URLDecoder.decode(it, "UTF-8") }
}.getOrNull()
