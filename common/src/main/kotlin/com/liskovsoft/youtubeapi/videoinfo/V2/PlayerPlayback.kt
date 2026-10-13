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
    cache: PlaybackCache? = null,
    post: (PlayerQuery) -> Pair<Int, String>
): PlaybackLoad {
    val scriptUrl = if (page == null) null else playerScriptUrl(page)
    val load = loadPlayer(
        walk,
        signIn,
        videoId,
        visitor,
        cachedSolve(scriptUrl, cache, solve),
        timestampFrom(scriptUrl, get, cache),
        post
    )
    val result = PlaybackLoad(load.form, load.client, selectPlayback(load.answer))
    warmHost(result.playback.videoUrl ?: result.playback.hlsUrl)
    return result
}

private fun timestampFrom(scriptUrl: String?, get: (String) -> String, cache: PlaybackCache?): Int? {
    if (scriptUrl == null) return null
    val cached = cache?.script(scriptUrl)
    val script = if (cached != null) {
        cached
    } else {
        val loaded = try {
            get(scriptUrl)
        } catch (failure: Exception) {
            return null
        }
        cache?.saveScript(scriptUrl, loaded)
        loaded
    }
    return signatureTimestamp(script)
}

private fun cachedSolve(
    scriptUrl: String?,
    cache: PlaybackCache?,
    solve: ((Set<String>, Set<String>) -> PlayerChallenge)?
): ((Set<String>, Set<String>) -> PlayerChallenge)? {
    if (scriptUrl == null || cache == null) return solve
    return { signatures, throttles ->
        val knownSignatures = HashMap<String, String>()
        val knownThrottles = HashMap<String, String>()
        val missingSignatures = LinkedHashSet<String>()
        val missingThrottles = LinkedHashSet<String>()
        for (value in signatures) {
            val solved = cache.signature(scriptUrl, value)
            if (solved != null) knownSignatures[value] = solved else missingSignatures.add(value)
        }
        for (value in throttles) {
            val solved = cache.throttle(scriptUrl, value)
            if (solved != null) knownThrottles[value] = solved else missingThrottles.add(value)
        }
        val fresh = if ((missingSignatures.isEmpty() && missingThrottles.isEmpty()) || solve == null) {
            PlayerChallenge(emptyMap(), emptyMap())
        } else {
            solve(missingSignatures, missingThrottles)
        }
        for ((value, solved) in fresh.signatures) cache.saveSignature(scriptUrl, value, solved)
        for ((value, solved) in fresh.throttles) cache.saveThrottle(scriptUrl, value, solved)
        PlayerChallenge(knownSignatures + fresh.signatures, knownThrottles + fresh.throttles)
    }
}

private fun warmHost(url: String?) {
    if (url == null || url.isEmpty()) return
    try {
        val parsed = URL(url)
        val host = parsed.host
        if (host == null || host.isEmpty()) return
        val port = if (parsed.port > 0) ":" + parsed.port else ""
        val connection = URL(parsed.protocol + "://" + host + port + "/").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "HEAD"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = TEXT_TIMEOUT_MS
            connection.readTimeout = TEXT_TIMEOUT_MS
            try {
                connection.responseCode
            } catch (failure: Exception) {
            }
        } finally {
            connection.disconnect()
        }
    } catch (failure: Exception) {
    }
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
