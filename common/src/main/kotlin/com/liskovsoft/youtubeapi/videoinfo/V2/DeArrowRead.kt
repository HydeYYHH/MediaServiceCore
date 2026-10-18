package com.liskovsoft.youtubeapi.videoinfo.V2

data class DeArrowTitle(val title:String,val votes:Double,val locked:Boolean,val original:Boolean)
data class DeArrowThumbnail(val timestamp:Double?,val votes:Double,val locked:Boolean,val original:Boolean)
data class DeArrowData(val titles:List<DeArrowTitle>,val thumbnails:List<DeArrowThumbnail>,val durationSeconds:Double?,val randomTime:Double?) {
    fun preferredTitle()=titles.firstOrNull { it.locked || it.votes>=0 }
    fun preferredThumbnail()=thumbnails.firstOrNull { it.locked || it.votes>=0 }
}
fun deArrowData(body:String):DeArrowData? {
    val root=jsonObject(body) ?: return null
    val titles=root.arr("titles").mapNotNull { it as? Json.Obj }.mapNotNull {
        it.text("title")?.let { title -> DeArrowTitle(title,it.scalar("votes")?.toDoubleOrNull() ?: 0.0,it.bool("locked")==true,it.bool("original")==true) }
    }
    val thumbs=root.arr("thumbnails").mapNotNull { it as? Json.Obj }.map {
        DeArrowThumbnail(it.scalar("timestamp")?.toDoubleOrNull(),it.scalar("votes")?.toDoubleOrNull() ?: 0.0,it.bool("locked")==true,it.bool("original")==true)
    }
    return DeArrowData(titles,thumbs,root.scalar("videoDuration")?.toDoubleOrNull(),root.scalar("randomTime")?.toDoubleOrNull())
}
fun deArrowThumbnailUrl(videoId:String,data:DeArrowData,server:String="https://dearrow-thumb.ajay.app"):String? {
    if(!videoIdAccepted(videoId)) return null
    val selected=data.preferredThumbnail()
    if(selected?.original==true) return null
    val time=selected?.timestamp ?: data.randomTime?.let { fraction -> data.durationSeconds?.takeIf { it>0 }?.times(fraction) } ?: return null
    if(!time.isFinite() || time<0) return null
    return urlWithParameter(urlWithParameter(server.trimEnd('/')+"/api/v1/getThumbnail","videoID",videoId),"time",time.toString())
}
fun loadDeArrow(videoId:String,http:HttpClient=JvmHttpClient(),server:String="https://sponsor.ajay.app"):ReadResult<DeArrowData> {
    if(!videoIdAccepted(videoId)) return ReadResult(error=RequestError(RequestFailure.INVALID,"invalid_video_id"))
    val result=requestBody(HttpRequest("GET",urlWithParameter(server.trimEnd('/')+"/api/branding","videoID",videoId)),http)
    if(result.error?.status==404) return ReadResult(DeArrowData(emptyList(),emptyList(),null,null))
    if(!result.ok) return ReadResult(error=result.error)
    return deArrowData(result.value!!)?.let {ReadResult(it)} ?: ReadResult(error=RequestError(RequestFailure.PARSE,"invalid_branding"))
}
