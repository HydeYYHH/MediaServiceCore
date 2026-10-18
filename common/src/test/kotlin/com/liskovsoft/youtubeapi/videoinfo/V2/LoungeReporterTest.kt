package com.liskovsoft.youtubeapi.videoinfo.V2
import org.junit.Assert.*
import org.junit.Test
class LoungeReporterTest {
    @Test fun reportsRealNavigationAndPlaybackIdentityWithMonotonicRequests() {
        val requests=ArrayList<HttpRequest>()
        val http=object:HttpClient {override fun execute(request:HttpRequest):HttpReply {requests.add(request);return HttpReply(200,"")}}
        val identity=LoungeIdentity("屏幕😀","d",LoungeScreen("s","t"),LoungeSession("sid","gsid","url"),7,1)
        val reporter=LoungeReporter({identity},http,nonce={"nonce"})
        assertTrue(reporter.nowPlaying("v",0,10000,1).ok);assertTrue(reporter.state(5000,10000,2).ok);assertTrue(reporter.navigation(true,false).ok)
        assertEquals("7",urlParameter(requests.last().url,"AID"));assertEquals("屏幕😀",urlParameter(requests.last().url,"name"))
        assertEquals("nonce",urlParameter("x?"+requests[0].body,"req0_cpn"))
        assertEquals("nonce",urlParameter("x?"+requests[1].body,"req0_cpn"))
        assertEquals("true",urlParameter("x?"+requests[2].body,"req0_hasPrevious"))
        assertEquals(listOf("0","1","2"),requests.map {urlParameter("x?"+it.body,"ofs")})
        assertEquals(3,requests.map {urlParameter(it.url,"RID")}.distinct().size)
    }
}
