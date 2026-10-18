package com.liskovsoft.youtubeapi.videoinfo.V2
import org.junit.Assert.*
import org.junit.Test
class DislikeReadTest {
    @Test fun keepsZeroAndLargeCountsWithoutFormatting() {
        val data=dislikeData("""{"id":"v","likes":0,"dislikes":9999999999,"viewCount":99999999999,"deleted":false}""")!!
        assertEquals(0L,data.likes);assertEquals(9999999999L,data.dislikes);assertNull(dislikeData("{}")!!.likes)
        val bad=object:HttpClient {override fun execute(request:HttpRequest)=HttpReply(200,"{\"id\":\"other\"}")}
        assertEquals("video_id_mismatch",loadDislikes("v",bad).error!!.code)
    }
}
