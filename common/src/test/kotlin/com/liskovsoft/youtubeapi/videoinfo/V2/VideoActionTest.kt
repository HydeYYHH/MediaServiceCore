package com.liskovsoft.youtubeapi.videoinfo.V2
import org.junit.Assert.*
import org.junit.Test
class VideoActionTest {
    @Test fun sendsTheChosenActionAndParsesBusinessFailures() {
        val calls = ArrayList<HttpRequest>()
        val http = object : HttpClient { override fun execute(request: HttpRequest): HttpReply { calls.add(request); return HttpReply(200, "{\"status\":\"STATUS_FAILED\"}") } }
        assertFalse(voteVideo("v", VideoVote.LIKE, RequestContext("a","t"), http).ok)
        assertEquals("v", jsonObject(calls.single().body)!!.obj("target")!!.text("videoId"))
        assertFalse(subscribeChannel("UC1", false, RequestContext("a","t"), http = http).ok)
        assertTrue(calls.last().url.endsWith("/unsubscribe"))
        assertEquals(RequestFailure.AUTH, voteVideo("v", VideoVote.UNLIKE, RequestContext(), http).error!!.kind)
        assertEquals(2, calls.size)
    }
}
