package com.liskovsoft.youtubeapi.videoinfo.V2
import org.junit.Assert.*
import org.junit.Test
class NoticeReadTest {
    @Test fun readsAllSectionsAndContinues() {
        val page = noticePage("""{"sections":[{"notificationRenderer":{"shortMessage":{"simpleText":"Ada: One"}}},{"notificationRenderer":{"shortMessage":{"simpleText":"Bob: Two"}}},{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"next"}}}}]}""")
        assertEquals(2,page.notices.size); assertEquals("Bob",page.notices.last().author); assertEquals("next",page.nextKey)
    }
    @Test fun feedbackBusinessFailureIsNotAnEmptySuccess() {
        val http = object:HttpClient { override fun execute(request:HttpRequest)=HttpReply(200,"{\"feedbackResponses\":[{\"isProcessed\":false}]}") }
        assertFalse(sendFeedback("key",RequestContext("a","t"),http).ok)
        assertEquals(RequestFailure.AUTH,loadNotices(RequestContext(),http=http).error!!.kind)
    }
}
