package com.liskovsoft.youtubeapi.videoinfo.V2

internal fun openBoundFormats(answer:PlayerAnswer,script:PlayerScript?,solver:PlayerSolver?):PlayerAnswer {
    var challenge:PlayerChallenge?=null
    val opened=openFormats(answer){signatures,throttles->
        if(script==null||solver==null) PlayerChallenge(emptyMap(),emptyMap())
        else solver.solve(PlayerChallengeRequest(script,signatures,throttles)).also{challenge=it}
    }
    fun usable(original:List<PlayerFormat>,formats:List<PlayerFormat>)=original.zip(formats).mapNotNull{(before,after)->
        val cipher=before.cipher?.let{"x?"+it}
        val signature=cipher?.let{urlParameter(it,"s")}
        val base=before.url ?: cipher?.let{urlParameter(it,"url")}
        val throttle=base?.let{urlParameter(it,"n")}
        if(after.url.isNullOrBlank() || (signature!=null && challenge?.signatures?.get(signature).isNullOrBlank()) ||
            (throttle!=null && challenge?.throttles?.get(throttle).isNullOrBlank())) null else after
    }
    val muxed=usable(answer.muxedFormats,opened.muxedFormats)
    val adaptive=usable(answer.adaptiveFormats,opened.adaptiveFormats)
    return opened.copy(muxedFormats=muxed,adaptiveFormats=adaptive,muxedUrls=muxed.mapNotNull{it.url},
        adaptiveUrls=adaptive.mapNotNull{it.url},hasMuxed=muxed.isNotEmpty(),hasDirectUrl=muxed.isNotEmpty()||adaptive.isNotEmpty())
}
class CachedPlayerSolver(private val delegate:PlayerSolver,private val cache:PlaybackCache=MemoryPlaybackCache()):PlayerSolver {
    override fun solve(request:PlayerChallengeRequest):PlayerChallenge {
        val hash=java.security.MessageDigest.getInstance("SHA-256").digest(request.script.source.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt() and 255)}
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
    override fun token(request:PlayerTokenRequest):PlayerToken? {
        val key=Key(request.client,request.videoId,request.context.scope(),request.purpose)
        cache.get(key)?.let{return it}
        val token=delegate.token(request)?.takeIf{it.value.isNotBlank()&&it.expiresAtMs>now()} ?: return null
        cache.put(key,token,token.expiresAtMs-now())
        return token
    }
}
