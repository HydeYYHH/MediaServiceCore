package com.liskovsoft.youtubeapi.videoinfo.V2

import java.net.URI
import java.util.LinkedHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Semaphore

interface PlayerScriptProvider {
    fun script(videoId: String, context: RequestContext, fresh: Boolean): ReadResult<PlayerScript>

    /** A revision known without I/O; null requires resolving the script before cache lookup. */
    fun cacheKey(videoId: String, context: RequestContext): String? = null
}

internal class PlayerScriptException(val error: RequestError) : Exception(error.code)

class JvmPlayerScripts(private val http: HttpClient = JvmHttpClient(), private val now: () -> Long = System::currentTimeMillis,
    maxEntries: Int = 8, private val ttlMs: Long = 30 * 60 * 1000) : PlayerScriptProvider {
    private data class CachedScript(val script: PlayerScript, val expiresAt: Long)
    private class Flight(val result: CompletableFuture<ReadResult<CachedScript>> = CompletableFuture())
    private data class Slot(val cached: CachedScript? = null, val flight: Flight? = null, val leader: Boolean = false)
    private val cache = ScriptCache(maxEntries, now)
    // This cache belongs to one provider/HttpClient. It never receives session headers or embed pages.
    private val sources = ScriptCache(maxEntries, now)

    override fun script(videoId: String, context: RequestContext, fresh: Boolean): ReadResult<PlayerScript> {
        if (!videoIdAccepted(videoId)) return ReadResult(error = RequestError(RequestFailure.INVALID, "invalid_video_id"))
        val key = videoId + "\u0000" + context.scope()
        val result = cache.read(key, fresh) { load(videoId, context, fresh) }
        return ReadResult(result.value?.script, result.error)
    }

    private fun load(videoId: String, context: RequestContext, fresh: Boolean): ReadResult<CachedScript> {
        val pageUrl = urlWithParameter("https://www.youtube.com/embed/$videoId", "hl", context.language)
        val page = requestBody(HttpRequest("GET", pageUrl), http)
        if (!page.ok) return ReadResult(error = page.error)
        val url = playerScriptUrl(page.value!!) ?: return ReadResult(error = RequestError(RequestFailure.PARSE, "missing_player_script"))
        val uri = try { URI(url) } catch (_: Exception) { null }
        if (uri?.scheme != "https" || uri.host != "www.youtube.com" || uri.port !in listOf(-1, 443) || uri.rawUserInfo != null)
            return ReadResult(error = RequestError(RequestFailure.PARSE, "invalid_player_script_url"))
        // Only immutable, public player assets share bytes across video/account scopes.
        val publicAsset = url.length <= MAX_KEY_CHARS && uri.rawQuery == null && uri.rawFragment == null &&
            uri.rawPath.startsWith("/s/player/") && uri.rawPath.endsWith(".js") &&
            uri.rawPath.split('/').none { it == "." || it == ".." } &&
            uri.rawPath.all { it.isLetterOrDigit() || it in "/_.-" }
        return if (publicAsset) sources.read(url, fresh) { download(url) } else download(url)
    }

    private fun download(url: String): ReadResult<CachedScript> {
        val loaded = requestBody(HttpRequest("GET", url), http)
        if (!loaded.ok) return ReadResult(error = loaded.error)
        if (loaded.value!!.isBlank()) return ReadResult(error = RequestError(RequestFailure.PARSE, "empty_player_script"))
        return ReadResult(CachedScript(PlayerScript(url, loaded.value), now() + ttlMs))
    }

    /** Both result and flight maps are bounded; waiting and HTTP always happen outside the monitor. */
    private class ScriptCache(private val capacity: Int, private val now: () -> Long) {
        init { require(capacity > 0) }
        private val monitor = Any()
        private val entries = LinkedHashMap<String, CachedScript>(16, .75f, true)
        private val flights = HashMap<String, Flight>()
        private val admission = Semaphore(minOf(capacity, 8))

        private fun slot(key: String): Slot? {
            // A running refresh takes precedence over the older, still-valid entry.
            flights[key]?.let { return Slot(flight = it) }
            val entry = entries[key] ?: return null
            if (entry.expiresAt <= now()) { entries.remove(key); return null }
            return Slot(cached = entry)
        }

        fun read(key: String, fresh: Boolean, load: () -> ReadResult<CachedScript>): ReadResult<CachedScript> {
            var retriedCancelledOwner = false
            while (true) {
                if (Thread.currentThread().isInterrupted) return cancelled()
                var selected = synchronized(monitor) { if (fresh) null else slot(key) }
                if (selected == null) {
                    try { admission.acquire() } catch (failure: InterruptedException) {
                        return ReadResult(error = transportError(failure))
                    }
                    selected = synchronized(monitor) {
                        (if (fresh) null else slot(key)) ?: Flight().let { flight ->
                            flights[key] = flight
                            Slot(flight = flight, leader = true)
                        }
                    }
                    if (!requireNotNull(selected).leader) admission.release()
                }
                val active = requireNotNull(selected)
                active.cached?.let { return ReadResult(it) }
                val flight = requireNotNull(active.flight)
                if (!active.leader) {
                    val result = try { flight.result.get() }
                    catch (failure: InterruptedException) { return ReadResult(error = transportError(failure)) }
                    catch (failure: ExecutionException) { throw requireNotNull(failure.cause) }
                    // A cancelled owner must not cancel an unrelated waiting caller.
                    if (result.error?.kind == RequestFailure.CANCELLED && !retriedCancelledOwner) {
                        retriedCancelledOwner = true
                        continue
                    }
                    return result
                }
                try {
                    val result = load()
                    synchronized(monitor) {
                        // A fresh read can replace this flight while its old HTTP request is running.
                        if (flights[key] === flight) {
                            flights.remove(key)
                            result.value?.takeIf { result.ok && it.expiresAt > now() &&
                                key.length <= MAX_KEY_CHARS && it.script.url.length <= MAX_KEY_CHARS &&
                                it.script.source.length <= MAX_SCRIPT_CHARS &&
                                it.script.source.firstOrNull { char -> !char.isWhitespace() } != '<' }?.let {
                                entries[key] = it
                                while (entries.size > capacity) entries.remove(entries.keys.first())
                            }
                        }
                    }
                    flight.result.complete(result)
                    return result
                } catch (failure: Throwable) {
                    synchronized(monitor) { if (flights[key] === flight) flights.remove(key) }
                    flight.result.completeExceptionally(failure)
                    throw failure
                } finally { admission.release() }
            }
        }
    }

    private companion object {
        const val MAX_KEY_CHARS = 4096
        const val MAX_SCRIPT_CHARS = 4 * 1024 * 1024
        fun cancelled(): ReadResult<CachedScript> =
            ReadResult(error = RequestError(RequestFailure.CANCELLED, "cancelled"))
    }
}
