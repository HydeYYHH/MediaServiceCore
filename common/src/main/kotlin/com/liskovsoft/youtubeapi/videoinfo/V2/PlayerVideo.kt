package com.liskovsoft.youtubeapi.videoinfo.V2

enum class VideoRange { SDR, HDR, UNKNOWN }

internal fun formatCodecs(mime: String?): List<String> = mime?.substringAfter("codecs=", "")
    ?.substringBefore(';')?.trim()?.trim('"')?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

internal fun videoRange(format: Json.Obj): VideoRange {
    val transfer = format.obj("colorInfo")?.text("transferCharacteristics") ?: return VideoRange.UNKNOWN
    return when {
        transfer.contains("2084") || transfer.contains("B67") -> VideoRange.HDR
        transfer.contains("709") || transfer.contains("SRGB") -> VideoRange.SDR
        else -> VideoRange.UNKNOWN
    }
}
