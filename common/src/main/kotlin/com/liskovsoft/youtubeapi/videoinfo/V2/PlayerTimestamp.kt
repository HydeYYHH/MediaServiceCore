package com.liskovsoft.youtubeapi.videoinfo.V2

private val TIMESTAMP = Regex("signatureTimestamp[:=](\\d+)(?!\\d)")

fun signatureTimestamp(script: String): Int? {
    val match = TIMESTAMP.find(script) ?: return null
    return match.groupValues[1].toIntOrNull()
}
