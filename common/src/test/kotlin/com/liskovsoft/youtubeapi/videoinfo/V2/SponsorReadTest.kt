package com.liskovsoft.youtubeapi.videoinfo.V2
import org.junit.Assert.*
import org.junit.Test
class SponsorReadTest {
    @Test fun preservesCategoriesAndRejectsInvalidIntervals() {
        val segments=sponsorSegments("""[{"UUID":"a","segment":[1.25,4.5],"category":"sponsor","actionType":"skip"},{"segment":[5,2]}]""")!!
        assertEquals(1,segments.size);assertEquals(1.25,segments.single().startSeconds,0.0)
        val http=object:HttpClient{override fun execute(request:HttpRequest):HttpReply {
            assertEquals("[\"intro\",\"sponsor\"]",urlParameter(request.url,"categories"));assertNull(request.headers["Authorization"]);return HttpReply(404,"")
        }}
        assertTrue(loadSponsorSegments("v",setOf("sponsor","intro"),http).value!!.isEmpty())
    }
    @Test fun preservesRateLimits() {
        val http=object:HttpClient{override fun execute(request:HttpRequest)=HttpReply(429,"",mapOf("Retry-After" to "5"))}
        assertEquals(5000L,loadSponsorSegments("v",http=http).error!!.retryAfterMs)
    }
}
