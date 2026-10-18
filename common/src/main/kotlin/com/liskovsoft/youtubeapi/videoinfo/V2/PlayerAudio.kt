package com.liskovsoft.youtubeapi.videoinfo.V2

data class PlayerAudio(val id: String?, val language: String?, val name: String?, val isDefault: Boolean, val autoDubbed: Boolean)
internal fun readPlayerAudio(format: Json.Obj): PlayerAudio? {
    val audio = format.obj("audioTrack") ?: return null
    val id = audio.text("id")
    return PlayerAudio(id, id?.substringBefore('.')?.takeIf { it.isNotEmpty() }, audio.text("displayName"),
        audio.bool("audioIsDefault") == true, audio.bool("isAutoDubbed") == true)
}
fun playerAudioTracks(body: String): List<PlayerAudio> {
    val streaming = jsonObject(body)?.obj("streamingData") ?: return emptyList()
    return (streaming.arr("formats") + streaming.arr("adaptiveFormats"))
        .mapNotNull { (it as? Json.Obj)?.let(::readPlayerAudio) }.distinctBy { it.id to it.name }
}
