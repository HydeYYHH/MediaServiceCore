package com.liskovsoft.youtubeapi.videoinfo.V2

data class DislikeData(val videoId:String?,val likes:Long?,val dislikes:Long?,val views:Long?,val rating:Double?,val deleted:Boolean)
fun dislikeData(body:String):DislikeData? {
    val root=jsonObject(body) ?: return null
    return DislikeData(root.text("id"),root.long("likes"),root.long("dislikes"),root.long("viewCount"),root.scalar("rating")?.toDoubleOrNull(),root.bool("deleted")==true)
}
fun loadDislikes(videoId:String,http:HttpClient=JvmHttpClient(),server:String="https://returnyoutubedislikeapi.com"):ReadResult<DislikeData> {
    if(!videoIdAccepted(videoId)) return ReadResult(error=RequestError(RequestFailure.INVALID,"invalid_video_id"))
    val result=requestBody(HttpRequest("GET",urlWithParameter(server.trimEnd('/')+"/votes","videoId",videoId)),http)
    if(!result.ok) return ReadResult(error=result.error)
    val data=dislikeData(result.value!!) ?: return ReadResult(error=RequestError(RequestFailure.PARSE,"invalid_dislikes"))
    if(data.videoId!=null && data.videoId!=videoId) return ReadResult(error=RequestError(RequestFailure.PARSE,"video_id_mismatch"))
    return ReadResult(data.copy(videoId=videoId))
}
