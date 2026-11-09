package com.liskovsoft.youtubeapi.videoinfo.V2

import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64

/** FEsubscriptions channel filter for guide entries that do not carry their own params. */
fun subscriptionChannelParams(channelId: String): String {
    require(channelId.matches(Regex("UC[A-Za-z0-9_-]{22}"))) { "Invalid subscription channel id" }
    val bytes = byteArrayOf(0x8a.toByte(), 3, 26, 10, 24) + channelId.toByteArray(Charsets.UTF_8)
    return URLEncoder.encode(Base64.getEncoder().encodeToString(bytes), "UTF-8")
}

/** TV channel tabs belong to FEsubscriptions, not to the videos inside the selected tab. */
internal fun subscriptionChannelTabs(root: Json.Obj): List<MediaItem> {
    val contents = root.obj("contents") ?: return emptyList()
    val tabs = contents.path("tvBrowseRenderer", "content", "tvSecondaryNavRenderer")?.arr("sections").orEmpty()
        .flatMap { (it as? Json.Obj)?.obj("tvSecondaryNavSectionRenderer")?.arr("tabs").orEmpty() }
        .ifEmpty {
            (contents.obj("singleColumnBrowseResultsRenderer") ?: contents.obj("twoColumnBrowseResultsRenderer"))
                ?.arr("tabs").orEmpty()
        }
    return mergeSubscriptionChannels(tabs.mapNotNull { wrapper ->
        val tab = (wrapper as? Json.Obj)?.let { it.obj("tabRenderer") ?: it.obj("expandableTabRenderer") }
            ?: return@mapNotNull null
        val endpoint = tab.path("endpoint", "browseEndpoint") ?: return@mapNotNull null
        val browseId = endpoint.text("browseId") ?: return@mapNotNull null
        val id = when {
            browseId.startsWith("UC") -> browseId
            browseId == LibrarySection.SUBSCRIPTIONS.browseId -> subscriptionChannelId(endpoint.text("params"))
            else -> null
        } ?: return@mapNotNull null
        MediaItem("channel", id, tab.display("title"), null, tab.thumbnail("thumbnail"), null, id,
            params = endpoint.text("params"), hasNewContent = tab.path("presentationStyle")?.text("style") == "NEW_CONTENT")
    })
}

/** A repeated entry must not discard an update marker supplied by another occurrence. */
internal fun mergeSubscriptionChannels(items: List<MediaItem>): List<MediaItem> {
    val channels = LinkedHashMap<String, MediaItem>()
    items.forEach { item ->
        val previous = channels[item.id]
        channels[item.id] = if (previous == null) item else previous.copy(hasNewContent = previous.hasNewContent || item.hasNewContent)
    }
    return channels.values.toList()
}

// FEsubscriptions params encode the channel id as protobuf field 49 -> field 1.
// Read that identity, never the display name: different channels can share a name.
private fun subscriptionChannelId(params: String?): String? = try {
    val decoded = URLDecoder.decode(params ?: "", "UTF-8").replace(' ', '+')
    val bytes = Base64.getUrlDecoder().decode(decoded.replace('+', '-').replace('/', '_'))
    val channel = bytes.protobufField(49)?.protobufField(1)?.toString(Charsets.UTF_8)
    channel?.takeIf { it.matches(Regex("UC[A-Za-z0-9_-]{22}")) }
} catch (_: IllegalArgumentException) {
    null
}

/** Bounded protobuf wire read; unknown fields are skipped and malformed params are ignored. */
internal fun ByteArray.protobufField(number: Int, wireType: Int = 2): ByteArray? {
    var offset = 0
    fun varint(): Long? {
        var value = 0L
        for (shift in 0..63 step 7) {
            if (offset >= size) return null
            val byte = this[offset++].toInt() and 255
            if (shift == 63 && byte > 1) return null
            value = value or ((byte and 127).toLong() shl shift)
            if (byte and 128 == 0) return value.takeIf { it >= 0 }
        }
        return null
    }
    while (offset < size) {
        val tag = varint() ?: return null
        if (tag ushr 3 == 0L) return null
        val valueStart = offset
        val length = when ((tag and 7).toInt()) {
            0 -> { varint() ?: return null; 0L }
            1 -> 8L
            2 -> varint() ?: return null
            5 -> 4L
            else -> return null
        }
        if (length > size - offset) return null
        val end = offset + length.toInt()
        if (tag ushr 3 == number.toLong() && tag and 7 == wireType.toLong())
            return copyOfRange(if (wireType == 2) offset else valueStart, end)
        offset = end
    }
    return null
}
