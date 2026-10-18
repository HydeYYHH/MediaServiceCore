package com.liskovsoft.youtubeapi.videoinfo.V2

data class PlaybackPreference(val maxHeight: Int = Int.MAX_VALUE, val mimeTypes: Set<String> = emptySet(),
    val audioId: String? = null, val audioLanguage: String? = null, val audioOnly: Boolean = false)

internal fun preferredPlayback(answer: PlayerAnswer?, preference: PlaybackPreference): Playback {
    if (answer == null) return Playback(null, null, null)
    fun allowed(format: PlayerFormat) = !format.url.isNullOrEmpty() &&
        (preference.mimeTypes.isEmpty() || preference.mimeTypes.any { format.mimeType?.startsWith(it) == true }) &&
        (format.height ?: 0) <= preference.maxHeight
    fun best(formats: List<PlayerFormat>) = formats.filter(::allowed).sortedWith(
        compareByDescending<PlayerFormat> { it.height ?: 0 }.thenByDescending { it.bitrate ?: 0 }).firstOrNull()
    val audioFormats = answer.adaptiveFormats.filter { it.mimeType?.startsWith("audio/") == true && allowed(it) }
    val exact = preference.audioId?.let { id -> audioFormats.filter { it.audioTrack?.id == id } }
        ?: preference.audioLanguage?.let { lang -> audioFormats.filter { it.audioTrack?.language.equals(lang, true) } }
    val preferred = exact?.takeIf { it.isNotEmpty() }
        ?: audioFormats.filter { it.audioTrack?.let(::audioRole) == AudioRole.ORIGINAL }.takeIf { it.isNotEmpty() }
        ?: audioFormats.filter { it.audioTrack?.isDefault == true }.takeIf { it.isNotEmpty() } ?: audioFormats
    val audio = best(preferred)
    val video = best(answer.adaptiveFormats.filter { it.mimeType?.startsWith("video/") == true })
    val muxed = best(answer.muxedFormats)
    if (preference.audioOnly && audio != null) return Playback(null, null, audio.url)
    if (answer.live && answer.hlsUrl != null) return Playback(answer.hlsUrl, null, null)
    if (!preference.audioOnly && video != null && audio != null) return Playback(null, video.url, audio.url)
    if (muxed != null) return Playback(null, muxed.url, null)
    if (answer.dashUrl != null) return Playback(null, null, null, dashUrl = answer.dashUrl)
    if (answer.hlsUrl != null) return Playback(answer.hlsUrl, null, null)
    if (answer.sabrUrl != null && answer.ustreamerConfig != null)
        return Playback(null, null, null, sabrUrl = answer.sabrUrl, ustreamerConfig = answer.ustreamerConfig)
    return Playback(null, if (preference.audioOnly) null else video?.url, audio?.url)
}
