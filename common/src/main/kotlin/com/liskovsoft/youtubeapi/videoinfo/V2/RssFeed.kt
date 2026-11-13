package com.liskovsoft.youtubeapi.videoinfo.V2

import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException
import org.xml.sax.SAXException
import java.io.StringReader
import java.time.Instant
import javax.xml.XMLConstants
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

data class RssVideo(val videoId:String,val channelId:String?,val title:String?,val author:String?,val published:String?,val thumbnail:String?)
data class RssFeed(val videos:List<RssVideo>,val failures:Map<String,RequestError>)
fun rssVideos(body:String):ReadResult<List<RssVideo>> {
    return try { parseRssVideos(body, DocumentBuilderFactory.newInstance()) }
    catch (_: Exception) { invalidRss() }
}

/** Factory injection keeps Android's unsupported optional JAXP controls testable on the JVM. */
internal fun parseRssVideos(body: String, factory: DocumentBuilderFactory): ReadResult<List<RssVideo>> {
    try {
        // These guards do not depend on JAXP implementation-specific switches. No declarations
        // may define custom entities, and no include element may trigger an XInclude fetch.
        if (body.contains("<!DOCTYPE", ignoreCase = true) || body.contains("<!ENTITY", ignoreCase = true) ||
            RSS_INCLUDE_ELEMENT.containsMatchIn(body)) return invalidRss()
        factory.isNamespaceAware = true
        factory.isValidating = false
        factory.isExpandEntityReferences = false
        if (!factory.isNamespaceAware || factory.isValidating || factory.isExpandEntityReferences) return invalidRss()
        try { factory.isXIncludeAware = false } catch (_: UnsupportedOperationException) { /* Include syntax was rejected above. */ }
        // Android Harmony rejects these optional controls. The declaration guard and rejecting
        // resolver remain mandatory, while supported controls provide another layer of defense.
        for ((feature, enabled) in listOf(
            "http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false)) {
            try { factory.setFeature(feature, enabled) } catch (_: ParserConfigurationException) { }
        }
        for (attribute in listOf(XMLConstants.ACCESS_EXTERNAL_DTD, XMLConstants.ACCESS_EXTERNAL_SCHEMA)) {
            try { factory.setAttribute(attribute, "") } catch (_: IllegalArgumentException) { }
        }
        val builder=factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> throw SAXException("external_xml_reference_rejected") }
        builder.setErrorHandler(DefaultHandler())
        val document=builder.parse(InputSource(StringReader(body.removePrefix("\uFEFF"))))
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
    } catch(_:Exception) { return invalidRss() }
}
private val RSS_INCLUDE_ELEMENT = Regex("<(?:[^!?\\s<>/]+:)?include(?=[\\s/>])", RegexOption.IGNORE_CASE)
private fun invalidRss() = ReadResult<List<RssVideo>>(error = RequestError(RequestFailure.PARSE, "invalid_rss"))
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
    fun published(video:RssVideo)=try{Instant.parse(video.published).toEpochMilli()}catch(_:Exception){Long.MIN_VALUE}
    return ReadResult(RssFeed(videos.distinctBy {it.videoId}.sortedByDescending(::published),failures))
}
