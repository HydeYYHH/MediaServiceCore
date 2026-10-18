package com.liskovsoft.youtubeapi.videoinfo.V2

enum class AudioRole { ORIGINAL, DESCRIPTIVE, DUBBED, AUTO_DUBBED, UNKNOWN }
// Port of YouTubeHelper.getSabrLanguage, upstream e64b852f310c131600ebe46f86c0b19d459faebe.
fun audioRole(audio: PlayerAudio): AudioRole = when {
    audio.autoDubbed -> AudioRole.AUTO_DUBBED
    audio.id?.substringAfterLast('.', "") == "4" -> AudioRole.ORIGINAL
    audio.id?.substringAfterLast('.', "") == "2" -> AudioRole.DESCRIPTIVE
    audio.id?.contains('.') == true -> AudioRole.DUBBED
    audio.isDefault -> AudioRole.ORIGINAL
    else -> AudioRole.UNKNOWN
}
