package com.liskovsoft.youtubeapi.videoinfo.V2

import java.net.URLDecoder
import java.net.URLEncoder

internal fun openBoundFormats(answer: PlayerAnswer, script: PlayerScript?, solver: PlayerSolver?,
    scriptLoader: (() -> PlayerScript?)? = null): PlayerAnswer {
    val manifest = answer.hlsUrl?.takeUnless { answer.live }?.let(::manifestThrottle)
    var challenge: PlayerChallenge? = null
    var attempted = false
    fun solve(signatures: Set<String>, throttles: Set<String>): PlayerChallenge {
        attempted = true
        val pending = if (manifest == null) throttles else throttles + manifest.value
        val activeScript = if (solver == null) null else scriptLoader?.invoke() ?: script
        return (if (activeScript == null || solver == null) PlayerChallenge(emptyMap(), emptyMap())
        else solver.solve(PlayerChallengeRequest(activeScript, signatures, pending))).also { challenge = it }
    }
    val opened = openFormats(answer, ::solve)
    if (manifest != null && !attempted) {
        try {
            solve(emptySet(), emptySet())
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        } catch (_: Exception) {
            // An unresolved manifest cannot be used as a playable fallback.
        }
    }
    fun usable(original: List<PlayerFormat>, formats: List<PlayerFormat>) = original.zip(formats).mapNotNull { (before, after) ->
        val cipher = before.cipher?.let { "x?$it" }
        val signature = cipher?.let { urlParameter(it, "s") }
        val base = before.url ?: cipher?.let { urlParameter(it, "url") }
        val throttle = base?.let { urlParameter(it, "n") }
        if (after.url.isNullOrBlank() || (signature != null && challenge?.signatures?.get(signature).isNullOrBlank()) ||
            (throttle != null && challenge?.throttles?.get(throttle).isNullOrBlank())) null else after
    }
    val muxed = usable(answer.muxedFormats, opened.muxedFormats)
    val adaptive = usable(answer.adaptiveFormats, opened.adaptiveFormats)
    val hls = if (manifest == null) opened.hlsUrl else challenge?.throttles?.get(manifest.value)
        ?.takeIf { it.isNotBlank() }?.let { solved -> manifest.replace(solved) }
    return opened.copy(muxedFormats = muxed, adaptiveFormats = adaptive, muxedUrls = muxed.mapNotNull { it.url },
        adaptiveUrls = adaptive.mapNotNull { it.url }, hasMuxed = muxed.isNotEmpty(),
        hasDirectUrl = muxed.isNotEmpty() || adaptive.isNotEmpty(), hlsUrl = hls, hasHls = hls != null)
}

private data class ManifestThrottle(val url: String, val range: IntRange, val value: String) {
    fun replace(solved: String): String = url.replaceRange(range,
        URLEncoder.encode(solved, "UTF-8").replace("+", "%20"))
}

private fun manifestThrottle(url: String): ManifestThrottle? {
    val pathEnd = url.indexOfAny(charArrayOf('?', '#')).takeIf { it >= 0 } ?: url.length
    val match = Regex("/n/([^/]+)").find(url.substring(0, pathEnd)) ?: return null
    val group = requireNotNull(match.groups[1])
    val value = try {
        URLDecoder.decode(group.value.replace("+", "%2B"), "UTF-8")
    } catch (_: IllegalArgumentException) {
        group.value
    }
    return ManifestThrottle(url, group.range, value)
}
class CachedPlayerSolver(private val delegate:PlayerSolver,private val cache:PlaybackCache=MemoryPlaybackCache()):PlayerSolver {
    override fun solve(request:PlayerChallengeRequest):PlayerChallenge {
        val hash = textDigest(request.script.source)
        val key=request.script.url+"\n"+hash
        val signatures=request.signatures.mapNotNull{value->cache.signature(key,value)?.let{value to it}}.toMap()
        val throttles=request.throttles.mapNotNull{value->cache.throttle(key,value)?.let{value to it}}.toMap()
        val missingSignatures=request.signatures-signatures.keys
        val missingThrottles=request.throttles-throttles.keys
        val fresh=if(missingSignatures.isEmpty()&&missingThrottles.isEmpty()) PlayerChallenge(emptyMap(),emptyMap()) else
            delegate.solve(request.copy(signatures=missingSignatures,throttles=missingThrottles))
        fresh.signatures.filterKeys{it in missingSignatures}.forEach{(value,solved)->cache.saveSignature(key,value,solved)}
        fresh.throttles.filterKeys{it in missingThrottles}.forEach{(value,solved)->cache.saveThrottle(key,value,solved)}
        return PlayerChallenge(signatures+fresh.signatures.filterKeys{it in missingSignatures},throttles+fresh.throttles.filterKeys{it in missingThrottles})
    }
}
class CachedPlayerTokens(private val delegate:PlayerTokenProvider,capacity:Int=128,
    private val now:()->Long=System::currentTimeMillis):PlayerTokenProvider {
    private data class Key(val client:PlayerClient,val video:String,val scope:String,val purpose:TokenPurpose)
    private val cache=TimedCache<Key,PlayerToken>(capacity,now)
    fun clear() = cache.clear()
    override fun token(request:PlayerTokenRequest):PlayerToken? {
        val key=Key(request.client,request.videoId,request.context.scope(),request.purpose)
        cache.get(key)?.let{return it}
        val generation = cache.generation()
        val token=delegate.token(request)?.takeIf{it.value.isNotBlank()&&it.expiresAtMs>now()} ?: return null
        cache.put(key,token,token.expiresAtMs-now(),generation)
        return token
    }
}
