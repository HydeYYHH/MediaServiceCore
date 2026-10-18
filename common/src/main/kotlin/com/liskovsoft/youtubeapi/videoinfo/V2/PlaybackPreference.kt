package com.liskovsoft.youtubeapi.videoinfo.V2

data class PlaybackPreference @JvmOverloads constructor(val maxHeight: Int = Int.MAX_VALUE, val mimeTypes: Set<String> = emptySet(),
    val audioId: String? = null, val audioLanguage: String? = null, val audioOnly: Boolean = false,
    val videoCodecs: Set<String> = emptySet(), val audioCodecs: Set<String> = emptySet(),
    val maxFps: Int = Int.MAX_VALUE, val dynamicRange: VideoRange? = null)

internal fun preferredPlayback(answer: PlayerAnswer?, preference: PlaybackPreference): Playback {
    if (answer == null) return Playback(null, null, null)
    fun codecMatches(format: PlayerFormat, codecs: Set<String>) = codecs.isEmpty() ||
        format.codecs.any { actual -> codecs.any { actual.startsWith(it, true) } }
    fun allowed(format: PlayerFormat, muxed: Boolean = false): Boolean {
        val audio = format.mimeType?.startsWith("audio/") == true
        return !format.url.isNullOrEmpty() &&
        (preference.mimeTypes.isEmpty() || preference.mimeTypes.any { format.mimeType?.startsWith(it) == true }) &&
        (format.height ?: 0) <= preference.maxHeight && (format.fps ?: 0) <= preference.maxFps &&
        (audio || preference.dynamicRange == null || format.dynamicRange == preference.dynamicRange) &&
        (audio || codecMatches(format, preference.videoCodecs)) &&
        ((!audio && !muxed) || codecMatches(format, preference.audioCodecs))
    }
    fun best(formats: List<PlayerFormat>, muxed: Boolean = false) = formats.filter { allowed(it, muxed) }.sortedWith(
        compareByDescending<PlayerFormat> { it.height ?: 0 }.thenByDescending { it.bitrate ?: 0 }).firstOrNull()
    val audioFormats = answer.adaptiveFormats.filter { it.mimeType?.startsWith("audio/") == true && allowed(it) }
    val exact = preference.audioId?.let { id -> audioFormats.filter { it.audioTrack?.id == id }.takeIf { it.isNotEmpty() } }
        ?: preference.audioLanguage?.let { lang -> audioFormats.filter { it.audioTrack?.language.equals(lang, true) } }
    val preferred = exact?.takeIf { it.isNotEmpty() }
        ?: audioFormats.filter { it.audioTrack?.let(::audioRole) == AudioRole.ORIGINAL }.takeIf { it.isNotEmpty() }
        ?: audioFormats.filter { it.audioTrack?.isDefault == true }.takeIf { it.isNotEmpty() } ?: audioFormats
    val audio = best(preferred)
    val video = best(answer.adaptiveFormats.filter { it.mimeType?.startsWith("video/") == true })
    val muxed = best(answer.muxedFormats, true)
    val manifest = preference.maxHeight == Int.MAX_VALUE && preference.maxFps == Int.MAX_VALUE &&
        preference.mimeTypes.isEmpty() && preference.videoCodecs.isEmpty() && preference.audioCodecs.isEmpty() &&
        preference.dynamicRange == null && preference.audioId == null && preference.audioLanguage == null && !preference.audioOnly
    if (preference.audioOnly && audio != null) return Playback(null, null, audio.url)
    if (manifest && answer.live && answer.hlsUrl != null) return Playback(answer.hlsUrl, null, null)
    if (!preference.audioOnly && video != null && audio != null) return Playback(null, video.url, audio.url)
    if (muxed != null) return if (preference.audioOnly) Playback(null, null, muxed.url) else Playback(null, muxed.url, null)
    if (manifest && answer.dashUrl != null) return Playback(null, null, null, dashUrl = answer.dashUrl)
    if (manifest && answer.hlsUrl != null) return Playback(answer.hlsUrl, null, null)
    if (manifest && answer.sabrUrl != null && answer.ustreamerConfig != null)
        return Playback(null, null, null, sabrUrl = answer.sabrUrl, ustreamerConfig = answer.ustreamerConfig)
    return Playback(null, null, null)
}
internal fun Playback.hasSource() = listOf(hlsUrl, dashUrl, sabrUrl, videoUrl, audioUrl).any { !it.isNullOrBlank() }
