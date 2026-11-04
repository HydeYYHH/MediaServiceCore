package com.liskovsoft.youtubeapi.videoinfo.V2

import java.net.URLEncoder
import java.util.Base64

/** Request addressing, not a permission token. The service still enforces who may post.
 * LiveMessageParams: YouTube.js protos/misc/params.proto and LiveChat.sendMessage.
 * Channel is the broadcaster's canonical UC ID, never the viewer's active channel. */
fun chatMessageParameters(videoId: String, channelId: String): String? {
    if (!Regex("[A-Za-z0-9_-]{11}").matches(videoId) || !Regex("UC[A-Za-z0-9_-]{22}").matches(channelId)) return null
    val channel = channelId.toByteArray(Charsets.UTF_8)
    val video = videoId.toByteArray(Charsets.UTF_8)
    val ids = byteArrayOf(10, channel.size.toByte()) + channel + byteArrayOf(18, video.size.toByte()) + video
    val params = byteArrayOf(42, ids.size.toByte()) + ids
    val message = byteArrayOf(10, params.size.toByte()) + params + byteArrayOf(16, 1, 24, 4)
    val encoded = URLEncoder.encode(Base64.getEncoder().encodeToString(message), "UTF-8")
    return Base64.getEncoder().encodeToString(encoded.toByteArray(Charsets.UTF_8))
}
