package com.liskovsoft.youtubeapi.videoinfo.V2

import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.Base64

internal fun commentCreationParameters(videoId: String, parentId: String? = null): String {
    val bytes = ByteArrayOutputStream()
    fun field(number: Int, value: ByteArray) {
        bytes.write((number shl 3) or 2)
        var length = value.size
        while (length >= 128) {
            bytes.write((length and 127) or 128)
            length = length ushr 7
        }
        bytes.write(length)
        bytes.write(value)
    }
    field(2, videoId.toByteArray(Charsets.UTF_8))
    parentId?.let { field(4, it.toByteArray(Charsets.UTF_8)) }
    field(5, if (parentId == null) byteArrayOf() else byteArrayOf(8, 0))
    bytes.write(80)
    bytes.write(7)
    return URLEncoder.encode(Base64.getEncoder().encodeToString(bytes.toByteArray()), "UTF-8")
}
