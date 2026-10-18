package com.liskovsoft.youtubeapi.videoinfo.V2

import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

data class RssVideo(val videoId:String,val channelId:String?,val title:String?,val author:String?,val published:String?,val thumbnail:String?)
data class RssFeed(val videos:List<RssVideo>,val failures:Map<String,RequestError>)
fun rssVideos(body:String):ReadResult<List<RssVideo>> {
    try {
        val factory=DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware=true
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities",false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false)
        factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD,"")
        factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA,"")
        val builder=factory.newDocumentBuilder()
        builder.setErrorHandler(org.xml.sax.helpers.DefaultHandler())
        val document=builder.parse(org.xml.sax.InputSource(java.io.StringReader(body.removePrefix("\uFEFF"))))
        val atom="http://www.w3.org/2005/Atom"
        if(document.documentElement.localName!="feed" || document.documentElement.namespaceURI!=atom)
            return ReadResult(error=RequestError(RequestFailure.PARSE,"invalid_rss_feed"))
        val youtube="http://www.youtube.com/xml/schemas/2015"
        fun Element.text(ns:String,name:String)=getElementsByTagNameNS(ns,name).item(0)?.textContent
        val entries=document.getElementsByTagNameNS(atom,"entry")
        val result=ArrayList<RssVideo>()
        for(i in 0 until entries.length) {
            val entry=entries.item(i) as Element
            val id=entry.text(youtube,"videoId") ?: continue
            if(!videoIdAccepted(id)) continue
            val thumbnail=entry.getElementsByTagNameNS("http://search.yahoo.com/mrss/","thumbnail").item(0) as? Element
            result.add(RssVideo(id,entry.text(youtube,"channelId"),entry.text(atom,"title"),entry.text(atom,"name"),
                entry.text(atom,"published"),thumbnail?.getAttribute("url")))
        }
        return ReadResult(result)
    } catch(_:Exception) { return ReadResult(error=RequestError(RequestFailure.PARSE,"invalid_rss")) }
}
fun loadRssFeed(channelIds:List<String>,http:HttpClient=JvmHttpClient()):ReadResult<RssFeed> {
    if(channelIds.distinct().size>100) return ReadResult(error=RequestError(RequestFailure.INVALID,"too_many_channels"))
    val failures=LinkedHashMap<String,RequestError>();val videos=ArrayList<RssVideo>()
    for(channel in channelIds.distinct()) {
        if(!videoIdAccepted(channel)){failures[channel]=RequestError(RequestFailure.INVALID,"invalid_channel_id");continue}
        val response=requestBody(HttpRequest("GET",urlWithParameter("https://www.youtube.com/feeds/videos.xml","channel_id",channel)),http)
        if(!response.ok){failures[channel]=response.error!!;continue}
        val parsed=rssVideos(response.value!!)
        if(!parsed.ok){failures[channel]=parsed.error!!;continue}
        videos.addAll(parsed.value!!.map {it.copy(channelId=it.channelId ?: channel)})
    }
    fun published(video:RssVideo)=try{java.time.Instant.parse(video.published).toEpochMilli()}catch(_:Exception){Long.MIN_VALUE}
    return ReadResult(RssFeed(videos.distinctBy {it.videoId}.sortedByDescending(::published),failures))
}
