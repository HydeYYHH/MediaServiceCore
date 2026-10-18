package com.liskovsoft.youtubeapi.videoinfo.V2

interface PlayerScriptProvider {
    fun script(videoId: String, context: RequestContext, fresh: Boolean): ReadResult<PlayerScript>
}

class JvmPlayerScripts(private val http: HttpClient = JvmHttpClient(), private val now: () -> Long = System::currentTimeMillis,
    maxEntries: Int = 8, private val ttlMs: Long = 30 * 60 * 1000) : PlayerScriptProvider {
    private val cache = TimedCache<String, PlayerScript>(maxEntries, now)

    @Synchronized override fun script(videoId: String, context: RequestContext, fresh: Boolean): ReadResult<PlayerScript> {
        if (!videoIdAccepted(videoId)) return ReadResult(error = RequestError(RequestFailure.INVALID, "invalid_video_id"))
        val key = videoId + "\u0000" + context.scope()
        if (!fresh) cache.get(key)?.let { return ReadResult(it) }
        val pageUrl = urlWithParameter("https://www.youtube.com/embed/$videoId", "hl", context.language)
        val page = requestBody(HttpRequest("GET", pageUrl), http)
        if (!page.ok) return ReadResult(error = page.error)
        val url = playerScriptUrl(page.value!!) ?: return ReadResult(error = RequestError(RequestFailure.PARSE, "missing_player_script"))
        val uri = try { java.net.URI(url) } catch (_: Exception) { null }
        if (uri?.scheme != "https" || uri.host != "www.youtube.com" || uri.port !in listOf(-1, 443))
            return ReadResult(error = RequestError(RequestFailure.PARSE, "invalid_player_script_url"))
        val loaded = requestBody(HttpRequest("GET", url), http)
        if (!loaded.ok) return ReadResult(error = loaded.error)
        if (loaded.value!!.isBlank()) return ReadResult(error = RequestError(RequestFailure.PARSE, "empty_player_script"))
        val script = PlayerScript(url, loaded.value)
        cache.put(key, script, ttlMs)
        return ReadResult(script)
    }
}
