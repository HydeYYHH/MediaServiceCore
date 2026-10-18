package com.liskovsoft.youtubeapi.videoinfo.V2

data class SponsorSegment(val id:String?,val startSeconds:Double,val endSeconds:Double,val category:String?,val actionType:String?)
fun sponsorSegments(body:String):List<SponsorSegment>? {
    val root=jsonValue(body) as? Json.Arr ?: return null
    return root.items.mapNotNull { it as? Json.Obj }.mapNotNull {
        val times=it.arr("segment").mapNotNull { value -> (value as? Json.Num)?.text?.toDoubleOrNull() }
        if(times.size!=2 || times.any { t -> !t.isFinite() || t<0 } || times[1]<times[0]) return@mapNotNull null
        SponsorSegment(it.text("UUID"),times[0],times[1],it.text("category"),it.text("actionType"))
    }
}
fun loadSponsorSegments(videoId:String,categories:Set<String> = emptySet(),http:HttpClient=JvmHttpClient(),
    server:String="https://sponsor.ajay.app"):ReadResult<List<SponsorSegment>> {
    if(!videoIdAccepted(videoId)) return ReadResult(error=RequestError(RequestFailure.INVALID,"invalid_video_id"))
    var url=urlWithParameter(server.trimEnd('/')+"/api/skipSegments","videoID",videoId)
    if(categories.isNotEmpty()) url=urlWithParameter(url,"categories",categories.sorted().joinToString(",","[","]"){jsonQuote(it)})
    val response=requestBody(HttpRequest("GET",url),http)
    if(response.error?.status==404) return ReadResult(emptyList())
    if(!response.ok) return ReadResult(error=response.error)
    return sponsorSegments(response.value!!)?.let { ReadResult(it) } ?: ReadResult(error=RequestError(RequestFailure.PARSE,"invalid_segments"))
}
