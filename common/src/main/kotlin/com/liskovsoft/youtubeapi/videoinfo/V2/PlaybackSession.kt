package com.liskovsoft.youtubeapi.videoinfo.V2

import java.net.URI
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

data class PlayerSource @JvmOverloads constructor(val videoId: String, val client: PlayerClient, val context: RequestContext, val urls: Set<String>, val sequence: Long,
    val preference: PlaybackPreference = PlaybackPreference(), val sessionId: String? = null)
class PlaybackSession @JvmOverloads constructor(private val http: HttpClient = JvmHttpClient(), private val script: PlayerScript? = null,
    private val solver: PlayerSolver? = null, private val tokens: PlayerTokenProvider? = null,
    private val now: () -> Long = System::currentTimeMillis, private val scripts: PlayerScriptProvider? = null,
    private val onAttempt: ((PlayerLoadAttempt) -> Unit)? = null) {
    private val lock = PlaybackLock()
    private val queues = PlayerClientQueues()
    private val sequence = AtomicLong()
    private val sessionId = UUID.randomUUID().toString()
    private val results = TimedCache<String, PlaybackLoad>(64, now)
    private val sources = LinkedHashMap<Long, PlayerSource>(16, 0.75f, true)
    private val sourceRoots = HashMap<Long, Set<String>>()
    private val scopedSolver = solver?.let { CachedPlayerSolver(it, MemoryPlaybackCache(now = now)) }
    private val scopedTokens = tokens?.let { CachedPlayerTokens(it, now = now) }
    fun invalidate() = results.clear()

/**
 * Trusted roots cover the whole answer catalog, not only the selected playback:
 * quality switching and downloads consume sibling formats of one response, and
 * derived (range/rn) child requests extend these roots via [registerRequest].
 */
private fun sourceUrls(answer: PlayerAnswer?, playback: Playback): Set<String> {
    if (answer == null) return listOfNotNull(playback.hlsUrl, playback.dashUrl, playback.sabrUrl, playback.videoUrl, playback.audioUrl).toSet()
    return (answer.muxedUrls.asSequence() + answer.adaptiveUrls.asSequence() +
        answer.muxedFormats.asSequence().mapNotNull { it.url } + answer.adaptiveFormats.asSequence().mapNotNull { it.url } +
        sequenceOf(playback.hlsUrl, playback.dashUrl, playback.sabrUrl, playback.videoUrl, playback.audioUrl))
        .filterNotNull().filter { it.isNotEmpty() }.toSet()
}
    @Synchronized internal fun source(load: PlaybackLoad): PlayerSource? {
        val supplied = load.source ?: return null
        val known = sources[supplied.sequence] ?: return null
        return known.takeIf { it.sessionId == supplied.sessionId && it.videoId == supplied.videoId &&
            it.client == supplied.client && it.context.scope() == supplied.context.scope() }
    }
    @Synchronized private fun remember(source: PlayerSource): PlayerSource {
        sources[source.sequence] = source
        sourceRoots.putIfAbsent(source.sequence, source.urls.toSet())
        while (sources.size > 64) {
            val oldest = sources.keys.first()
            sources.remove(oldest)
            sourceRoots.remove(oldest)
        }
        return source
    }
    @Synchronized fun registerRequest(load: PlaybackLoad, parentUrl: String, url: String): ReadResult<PlaybackLoad> {
        val known = source(load) ?: return ReadResult(error = RequestError(RequestFailure.INVALID, "unknown_source"))
        if (parentUrl !in known.urls) return ReadResult(error = RequestError(RequestFailure.INVALID, "unknown_parent_request"))
        val uri = try { URI(url) } catch (_: Exception) { null }
        if (uri?.scheme !in listOf("http", "https") || uri?.host.isNullOrBlank())
            return ReadResult(error = RequestError(RequestFailure.INVALID, "invalid_media_url"))
        val selected = sourceRoots.getValue(known.sequence)
        val children = (known.urls - selected - url).toList().takeLast(511) + url
        val updated = remember(known.copy(urls = selected + children))
        return ReadResult(load.copy(source = updated))
    }
    fun load(videoId: String, context: RequestContext = RequestContext(), preference: PlaybackPreference = PlaybackPreference(),
        fresh: Boolean = false, excluded: Set<PlayerClient> = emptySet(),
        includeMuxedFloor: Boolean = true, deferred: Set<PlayerClient> = emptySet()): ReadResult<PlaybackLoad> {
        if (!videoIdAccepted(videoId)) return ReadResult(error = RequestError(RequestFailure.INVALID, "invalid_video_id"))
        val revision = scripts?.cacheKey(videoId, context)
        val resolved by lazy {
            try { scripts?.script(videoId, context, fresh) } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw error
            } catch (error: Exception) {
                val transport = transportError(error)
                ReadResult<PlayerScript>(error = if (transport.code == "transport_failed")
                    RequestError(RequestFailure.TRANSPORT, "script_load_failed", cause = transport.cause) else transport)
            }
        }
        fun activeScript(): PlayerScript? {
            val result = resolved
            if (result != null && !result.ok) throw PlayerScriptException(requireNotNull(result.error))
            return result?.value ?: script
        }
        val eagerScript = try { if (revision == null) activeScript() else script }
        catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return ReadResult(error = RequestError(RequestFailure.CANCELLED, "cancelled"))
        } catch (failure: PlayerScriptException) {
            return ReadResult(error = failure.error)
        }
        if (fresh) scopedTokens?.clear()
        val generation = results.generation()
        val key = listOf(videoId, context.scope(), preference.copy(mimeTypes = preference.mimeTypes.toSortedSet(),
            videoCodecs = preference.videoCodecs.toSortedSet(), audioCodecs = preference.audioCodecs.toSortedSet()).toString(),
            revision, eagerScript?.url, eagerScript?.source?.let(::textDigest),
            fresh.toString(), excluded.sortedBy { it.name }.toString(), includeMuxedFloor.toString(),
            deferred.sortedBy { it.name }.toString(), generation.toString()).joinToString("\u0001")
        if (!fresh) results.get(key)?.let { return ReadResult(it) }
        val loaded = try { lock.load(key) compute@{
            if (!fresh) results.get(key)?.let { return@compute it }
            val result = loadPlayer(videoId, context, http, eagerScript, scopedSolver, scopedTokens, queues,
                excluded, now, preference, onAttempt, deferred, ::activeScript)
            val loaded = if (!result.ok) PlaybackLoad(PlaybackForm.VOD, null, Playback(null, null, null), error = result.error)
            else {
                val player = result.value!!
                val floor = if (!includeMuxedFloor || PlayerClient.ANDROID_VR in excluded) player.answer to null
                    else mergeFloor(player, videoId, context, preference, eagerScript, ::activeScript)
                val answer = floor?.first
                val playback = selectPlayback(answer, preference)
                val urls = sourceUrls(answer, playback)
                if (urls.isEmpty()) PlaybackLoad(player.form, player.client, playback, error = RequestError(RequestFailure.UNSUPPORTED, "no_matching_format"))
                else PlaybackLoad(player.form, player.client, playback, answer,
                    remember(PlayerSource(videoId, player.client!!, context, urls, sequence.incrementAndGet(), preference, sessionId)),
                    floorClient = floor?.second)
            }
            if (loaded.error == null) {
                val lifetime = loaded.answer?.expiresAtMs?.minus(now()) ?: 45000L
                results.put(key, loaded, minOf(45000L, lifetime), generation)
            }
            loaded
        }} catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return ReadResult(error = RequestError(RequestFailure.CANCELLED, "cancelled"))
        }
        return if (loaded.error != null) ReadResult(error = loaded.error) else ReadResult(loaded)
    }

    /**
     * The direct-URL queue leaders answer VOD without progressive formats; the ANDROID
     * reel_item_watch response carries a window-exempt muxed floor that VOD answers merge
     * so playback and downloads share one catalog. A failed floor fetch never demotes the
     * primary answer.
     */
    private fun mergeFloor(player: PlayerLoad, videoId: String, context: RequestContext,
        preference: PlaybackPreference, activeScript: PlayerScript?,
        scriptLoader: () -> PlayerScript?): Pair<PlayerAnswer?, PlayerClient?>? {
        val answer = player.answer ?: return null
        if (player.form != PlaybackForm.VOD || answer.live) return answer to null
        if (answer.muxedFormats.any { !it.url.isNullOrBlank() }) return answer to null
        val query = reelFloorQuery(videoId, context.visitor, nonce(16), nonce(12))
        val response = try { requestObject(query, http) } catch (failure: InterruptedException) {
            throw failure
        } catch (_: Exception) { null } ?: return answer to null
        if (!response.ok) return answer to null
        val reelRoot = jsonObject(response.value?.encode() ?: return answer to null)?.obj("playerResponse")
            ?: return answer to null
        var floor = try { playerAnswer(reelRoot.encode()) } catch (_: Exception) { return answer to null }
        if (floor.details?.videoId != videoId) return answer to null
        floor = try { openBoundFormats(floor, activeScript, scopedSolver, scriptLoader) }
        catch (failure: InterruptedException) { throw failure }
        catch (_: PlayerScriptException) { return answer to null }
        val receivedAt = now()
        val deadlines = listOfNotNull(floor.expiresInSeconds?.let { receivedAt + it.coerceIn(0, 31536000) * 1000 })
        floor = floor.copy(expiresAtMs = deadlines.minOrNull())
        if (floor.muxedFormats.none { !it.url.isNullOrBlank() }) return answer to null
        return answer.copy(
            muxedFormats = floor.muxedFormats,
            muxedUrls = floor.muxedUrls,
            hasMuxed = answer.hasMuxed || floor.hasMuxed,
            hasDirectUrl = answer.hasDirectUrl || floor.hasDirectUrl,
            expiresAtMs = listOfNotNull(answer.expiresAtMs, floor.expiresAtMs).minOrNull(),
        ) to PlayerClient.ANDROID_VR
    }
}
