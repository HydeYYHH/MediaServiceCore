package com.liskovsoft.youtubeapi.videoinfo.V2

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

private const val TEXT_TIMEOUT_MS = 15000

data class Playback(
    val hlsUrl: String?,
    val videoUrl: String?,
    val audioUrl: String?,
    val dashUrl: String? = null,
    val sabrUrl: String? = null,
    val ustreamerConfig: String? = null
)

data class PlaybackLoad(
    val form: PlaybackForm,
    val client: PlayerClient?,
    val playback: Playback,
    val answer: PlayerAnswer? = null,
    val source: PlayerSource? = null,
    val error: RequestError? = null
)

private val emptyPlayback = Playback(null, null, null)

fun selectPlayback(answer: PlayerAnswer?, preference: PlaybackPreference = PlaybackPreference()): Playback = preferredPlayback(answer, preference)

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

