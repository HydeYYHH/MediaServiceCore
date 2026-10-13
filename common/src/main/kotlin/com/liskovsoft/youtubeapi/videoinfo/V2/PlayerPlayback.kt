package com.liskovsoft.youtubeapi.videoinfo.V2

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

private const val TEXT_TIMEOUT_MS = 15000

data class Playback(
    val hlsUrl: String?,
    val videoUrl: String?,
    val audioUrl: String?
)

data class PlaybackLoad(
    val form: PlaybackForm,
    val client: PlayerClient?,
    val playback: Playback
)

private val emptyPlayback = Playback(null, null, null)

fun selectPlayback(answer: PlayerAnswer?): Playback {
    if (answer == null) return emptyPlayback
    val hls = answer.hlsUrl
    val muxed = best(answer.muxedFormats, null)
    val video = best(answer.adaptiveFormats, "video/")
    val audio = best(answer.adaptiveFormats, "audio/")
    if (muxed == null && video == null && audio == null && hls == null) return emptyPlayback
    if (answer.live && hls != null) return Playback(hls, null, null)
    if (muxed != null) return Playback(null, muxed.url, null)
    if (video != null || audio != null) return Playback(null, video?.url, audio?.url)
    return Playback(hls, null, null)
}

fun getText(url: String): String {
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
        connection.requestMethod = "GET"
        connection.instanceFollowRedirects = false
        connection.connectTimeout = TEXT_TIMEOUT_MS
        connection.readTimeout = TEXT_TIMEOUT_MS
        val code = connection.responseCode
        if (code != 200) throw IOException("status $code")
        return connection.inputStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
    } finally {
        connection.disconnect()
    }
}

fun loadPlayback(
    walk: PlayerAttempt,
    signIn: SignIn,
    videoId: String,
    visitor: String?,
    page: String?,
    solve: ((Set<String>, Set<String>) -> PlayerChallenge)? = null,
    get: (String) -> String = ::getText,
    post: (PlayerQuery) -> Pair<Int, String>
): PlaybackLoad {
    val load = loadPlayer(walk, signIn, videoId, visitor, solve, timestampFrom(page, get), post)
    return PlaybackLoad(load.form, load.client, selectPlayback(load.answer))
}

private fun timestampFrom(page: String?, get: (String) -> String): Int? {
    if (page == null) return null
    val url = playerScriptUrl(page) ?: return null
    val script = try {
        get(url)
    } catch (failure: Exception) {
        return null
    }
    return signatureTimestamp(script)
}

private fun best(formats: List<PlayerFormat>, mimePrefix: String?): PlayerFormat? {
    var chosen: PlayerFormat? = null
    for (format in formats) {
        val url = format.url
        if (url == null || url.isEmpty()) continue
        if (mimePrefix != null) {
            val mime = format.mimeType
            if (mime == null || !mime.startsWith(mimePrefix)) continue
        }
        if (chosen == null || prefer(format, chosen)) chosen = format
    }
    return chosen
}

private fun prefer(candidate: PlayerFormat, current: PlayerFormat): Boolean {
    val height = (candidate.height ?: -1).compareTo(current.height ?: -1)
    if (height != 0) return height > 0
    return (candidate.bitrate ?: -1) > (current.bitrate ?: -1)
}
