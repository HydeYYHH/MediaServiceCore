package com.liskovsoft.youtubeapi.videoinfo.V2
import org.junit.Assert.*
import org.junit.Test
class RssFeedTest {
    private fun xml(id:String,date:String)="""<feed xmlns="http://www.w3.org/2005/Atom" xmlns:yt="http://www.youtube.com/xml/schemas/2015"><entry><yt:videoId>$id</yt:videoId><title>Title</title><author><name>Ada</name></author><published>$date</published></entry></feed>"""
    @Test fun mergesAndSortsWithPartialFailures() {
        val http=object:HttpClient{override fun execute(request:HttpRequest):HttpReply=when(urlParameter(request.url,"channel_id")){
            "a"->HttpReply(200,xml("old","2026-01-01T00:00:00Z"))
            "b"->HttpReply(200,xml("new","2026-02-01T00:00:00Z"))
            else->HttpReply(503,"")
        }}
        val result=loadRssFeed(listOf("a","b","a","bad"),http).value!!
        assertEquals(listOf("new","old"),result.videos.map {it.videoId});assertEquals(setOf("bad"),result.failures.keys)
    }
    @Test fun rejectsMalformedXmlAndExternalEntities() {
        assertFalse(rssVideos("<feed>").ok)
        assertFalse(rssVideos("<!DOCTYPE feed [<!ENTITY x SYSTEM 'file:///not-read'>]><feed>&x;</feed>").ok)
    }
}
