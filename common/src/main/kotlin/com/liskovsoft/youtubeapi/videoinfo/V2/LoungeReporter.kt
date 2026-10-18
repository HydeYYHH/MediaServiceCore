package com.liskovsoft.youtubeapi.videoinfo.V2

class LoungeReporter(private val identity: () -> LoungeIdentity?, private val http: HttpClient,
    private val nextRequest: () -> Int = java.util.concurrent.atomic.AtomicInteger(1337).let { counter -> { counter.incrementAndGet() } },
    private val nonce: () -> String = { java.util.UUID.randomUUID().toString().replace("-","").take(16) }) {
    constructor(connection: LoungeConnection, http: HttpClient) : this(connection::identity,http,connection::nextRequestId)
    private var ofs=0
    private var video:String?=null
    private var cpn=nonce()
    @Synchronized private fun report(build:(LoungeIdentity,Int)->PlayerQuery):ReadResult<Boolean> {
        val current=identity() ?: return ReadResult(error=RequestError(RequestFailure.INVALID,"lounge_not_connected"))
        val query=build(current,ofs++)
        if(query.url.isBlank()) return ReadResult(error=RequestError(RequestFailure.INVALID,"invalid_lounge_report"))
        var url=urlWithParameter(query.url,"RID",nextRequest().toString())
        url=urlWithParameter(url,"AID",maxOf(0,current.acknowledged).toString())
        url=urlWithParameter(url,"zx",java.util.UUID.randomUUID().toString().replace("-",""))
        val result=requestBody(query.copy(url=url).request(),http)
        return if(result.ok) ReadResult(true) else ReadResult(error=result.error)
    }
    @Synchronized fun nowPlaying(videoId:String,positionMs:Long,durationMs:Long,state:Int,playlistId:String?=null,index:String?=null,ctt:String?=null):ReadResult<Boolean> {
        if(video!=videoId){video=videoId;cpn=nonce()}
        return report { i,n -> nowPlayingQuery(i.screenName,i.deviceId,i.screen.loungeToken,i.session.sessionId,i.session.gSessionId,
            videoId,positionMs,durationMs,state,ctt,playlistId,index,n).let { it.copy(body=it.body+"&req0_cpn="+cpn) } }
    }
    fun state(positionMs:Long,durationMs:Long,state:Int)=report { i,n ->
        stateQuery(i.screenName,i.deviceId,i.screen.loungeToken,i.session.sessionId,i.session.gSessionId,positionMs,durationMs,state,n,cpn) }
    fun navigation(hasPrevious:Boolean,hasNext:Boolean)=report { i,n ->
        previousNextQuery(i.screenName,i.deviceId,i.screen.loungeToken,i.session.sessionId,i.session.gSessionId,n,hasPrevious,hasNext) }
    fun volume(volume:Int)=report { i,n -> volumeQuery(i.screenName,i.deviceId,i.screen.loungeToken,i.session.sessionId,i.session.gSessionId,volume,n) }
    fun subtitle(id:String?,language:String?)=report { i,n -> subtitleQuery(i.screenName,i.deviceId,i.screen.loungeToken,i.session.sessionId,i.session.gSessionId,id,language,n) }
    fun quality(videoId:String,quality:String,levels:List<String>)=report { i,n -> qualityQuery(i.screenName,i.deviceId,i.screen.loungeToken,i.session.sessionId,i.session.gSessionId,videoId,quality,levels,n) }
    fun discovery(id:String)=report { i,n -> discoveryQuery(i.screenName,i.deviceId,i.screen.loungeToken,i.session.sessionId,i.session.gSessionId,id,n) }
}
